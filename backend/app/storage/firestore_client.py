"""Firestore REST client (httpx) with Google service-account auth.

Implements the subset of the Firestore REST API
(https://firebase.google.com/docs/firestore/use-rest-api) needed by the
VibeMate storage layer. Service-account credentials are exchanged for an
OAuth2 access token using a signed JWT bearer grant — which is why both
``PyJWT`` and ``cryptography`` are runtime dependencies.
"""
from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

import httpx
import jwt

_SCOPE = "https://www.googleapis.com/auth/datastore"
_TOKEN_URL = "https://oauth2.googleapis.com/token"
_FIRESTORE_BASE = "https://firestore.googleapis.com/v1/projects/{project}/databases/(default)/documents"
_TOKEN_TTL_SECONDS = 3600


# --- Value (de)serialisation ------------------------------------------------


def _rfc3339(value: datetime) -> str:
    """Format a datetime as an RFC3339 UTC string (Firestore timestampValue)."""
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def encode_value(value: Any) -> dict[str, Any]:
    """Convert a Python value into a Firestore REST ``Value`` object."""
    if value is None:
        return {"nullValue": None}
    if isinstance(value, bool):
        return {"booleanValue": value}
    if isinstance(value, int):
        return {"integerValue": str(value)}
    if isinstance(value, float):
        return {"doubleValue": value}
    if isinstance(value, datetime):
        return {"timestampValue": _rfc3339(value)}
    if isinstance(value, str):
        return {"stringValue": value}
    if isinstance(value, dict):
        return {"mapValue": {"fields": {k: encode_value(v) for k, v in value.items()}}}
    if isinstance(value, (list, tuple)):
        return {"arrayValue": {"values": [encode_value(v) for v in value]}}
    raise TypeError(f"cannot encode {type(value).__name__} for Firestore")


def encode_fields(data: dict[str, Any]) -> dict[str, Any]:
    return {k: encode_value(v) for k, v in data.items()}


def decode_value(value: dict[str, Any]) -> Any:
    """Convert a Firestore REST ``Value`` object back to a Python value."""
    if "nullValue" in value:
        return None
    if "booleanValue" in value:
        return value["booleanValue"]
    if "integerValue" in value:
        return int(value["integerValue"])
    if "doubleValue" in value:
        return value["doubleValue"]
    if "timestampValue" in value:
        raw = value["timestampValue"]
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    if "stringValue" in value:
        return value["stringValue"]
    if "bytesValue" in value:
        return value["bytesValue"]
    if "referenceValue" in value:
        return value["referenceValue"]
    if "geoPointValue" in value:
        return value["geoPointValue"]
    if "arrayValue" in value:
        return [decode_value(item) for item in value["arrayValue"].get("values", [])]
    if "mapValue" in value:
        fields = value["mapValue"].get("fields", {})
        return {k: decode_value(v) for k, v in fields.items()}
    return None


def decode_fields(fields: dict[str, Any]) -> dict[str, Any]:
    return {k: decode_value(v) for k, v in fields.items()}


# --- Service-account auth ----------------------------------------------------


class ServiceAccount:
    """A Google service account capable of minting OAuth2 access tokens."""

    def __init__(self, credentials: dict[str, Any]) -> None:
        self.client_email: str = credentials["client_email"]
        self.private_key: str = credentials["private_key"]
        self.token_uri: str = credentials.get("token_uri", _TOKEN_URL)
        self.project_id: str = credentials.get("project_id", "")

    def signed_assertion(self) -> str:
        now = int(time.time())
        payload = {
            "iss": self.client_email,
            "scope": _SCOPE,
            "aud": self.token_uri,
            "iat": now,
            "exp": now + _TOKEN_TTL_SECONDS,
        }
        return jwt.encode(
            payload,
            self.private_key,
            algorithm="RS256",
            headers={"alg": "RS256", "typ": "JWT"},
        )

    async def access_token(self, client: httpx.AsyncClient) -> str:
        response = await client.post(
            self.token_uri,
            data={
                "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion": self.signed_assertion(),
            },
        )
        response.raise_for_status()
        return response.json()["access_token"]


# --- REST client -------------------------------------------------------------


class FirestoreClient:
    """Tiny async wrapper around the Firestore REST document API."""

    def __init__(self, service_account_path: Path, project_id: str = "", timeout: float = 30.0) -> None:
        self._credentials = json.loads(service_account_path.read_text(encoding="utf-8"))
        self._project_id = (project_id or self._credentials.get("project_id", "")).strip()
        if not self._project_id:
            raise ValueError(
                "GCP_PROJECT_ID must be set (or included in the service-account JSON)."
            )
        self._service_account = ServiceAccount(self._credentials)
        self._timeout = timeout
        self._client: httpx.AsyncClient | None = None
        self._token: str | None = None
        self._token_expires_at: float = 0.0

    @property
    def project_id(self) -> str:
        return self._project_id

    # Lifecycle --------------------------------------------------------------

    async def start(self) -> None:
        self._client = httpx.AsyncClient(timeout=self._timeout)

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    # Auth --------------------------------------------------------------------

    async def _request_headers(self) -> dict[str, str]:
        if self._client is None:
            raise RuntimeError("FirestoreClient.start() must be called first")
        if self._token is None or time.time() >= self._token_expires_at - 60:
            self._token = await self._service_account.access_token(self._client)
            self._token_expires_at = time.time() + _TOKEN_TTL_SECONDS
        return {"Authorization": f"Bearer {self._token}"}

    # Low-level helpers -------------------------------------------------------

    def _document_url(self, doc_path: str) -> str:
        return f"{_FIRESTORE_BASE.format(project=self._project_id)}/{doc_path}"

    async def get_document(self, doc_path: str) -> dict[str, Any] | None:
        """Fetch a document and decode it to a plain dict (or ``None``)."""
        if self._client is None:
            raise RuntimeError("FirestoreClient.start() must be called first")
        response = await self._client.get(
            self._document_url(doc_path), headers=await self._request_headers()
        )
        if response.status_code == 404:
            return None
        response.raise_for_status()
        return decode_fields(response.json().get("fields", {}))

    async def set_document(self, doc_path: str, data: dict[str, Any]) -> None:
        """Create/merge a document (Firestore REST ``PATCH`` semantics)."""
        if self._client is None:
            raise RuntimeError("FirestoreClient.start() must be called first")
        response = await self._client.patch(
            self._document_url(doc_path),
            json={"fields": encode_fields(data)},
            headers=await self._request_headers(),
        )
        response.raise_for_status()

    async def delete_document(self, doc_path: str) -> None:
        """Delete a document, ignoring 404s."""
        if self._client is None:
            raise RuntimeError("FirestoreClient.start() must be called first")
        response = await self._client.delete(
            self._document_url(doc_path), headers=await self._request_headers()
        )
        if response.status_code != 404:
            response.raise_for_status()

    async def query_documents(
        self,
        collection: str,
        field_path: Iterable[str],
        value: Any,
        order_by: str | None = None,
        direction: str = "DESCENDING",
    ) -> list[dict[str, Any]]:
        """Run a structured query and decode matching documents.

        Each returned dict includes the document id under the ``"_id"`` key.
        """
        if self._client is None:
            raise RuntimeError("FirestoreClient.start() must be called first")
        query: dict[str, Any] = {
            "from": [{"collectionId": collection}],
            "where": {
                "fieldFilter": {
                    "op": "EQUAL",
                    "field": {"reference": {"fieldPath": list(field_path)}},
                    "value": encode_value(value),
                }
            },
        }
        if order_by:
            query["orderBy"] = [
                {
                    "field": {"reference": {"fieldPath": [order_by]}},
                    "direction": direction,
                }
            ]
        response = await self._client.post(
            f"{_FIRESTORE_BASE.format(project=self._project_id)}:runQuery",
            json={"structuredQuery": query},
            headers=await self._request_headers(),
        )
        response.raise_for_status()

        documents: list[dict[str, Any]] = []
        for document in response.json().get("documents", []):
            doc_id = document.get("name", "").rsplit("/", 1)[-1]
            fields = decode_fields(document.get("fields", {}))
            fields["_id"] = doc_id
            documents.append(fields)
        return documents