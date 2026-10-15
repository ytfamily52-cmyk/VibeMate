"""Firebase Authentication verification for the VibeMate backend.

The Android app authenticates with Firebase directly (email, anonymous or
Google sign-in) and sends the Firebase **ID token** to ``POST /auth/firebase``.
This module verifies that token against Google's public RSA keys — the same
algorithm Firebase's own SDKs use — so no Firebase service-account file is
needed. On success the caller mints the usual VibeMate JWT, which keeps every
existing chat/call endpoint unchanged.

Token format: RS256 JWT, ``aud`` = Firebase project id and
``iss`` = https://securetoken.google.com/<project_id>.
Public keys are fetched (with a short TTL cache) from Google's JWKS endpoint.
"""

from __future__ import annotations

import base64
import datetime as _datetime
import time
from typing import Any

import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicNumbers
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

from app.errors import unauthorized

_CERTS_URL = (
    "https://www.googleapis.com/service_accounts/v1/jwk/"
    "securetoken@system.gserviceaccount.com"
)
_ISSUER_TMPL = "https://securetoken.google.com/{project_id}"
_CACHE_TTL_SECONDS = 300

# Firebase ID tokens are minted by Google's servers. If this host's clock is a
# little behind, a freshly minted token looks "not yet valid" (its ``iat`` is in
# the future) and PyJWT rejects it with "The token is not yet valid (iat)". A
# few minutes of leeway absorbs that skew so a slightly-off system clock never
# breaks sign-in; long-lived tokens (exp) are still enforced.
_CLOCK_SKEW_LEEWAY = _datetime.timedelta(minutes=5)

# module-level cache: {"fetched_at": monotonic-ts, "keys": {kid: jwk-dict}}
_certs_cache: dict[str, Any] = {"fetched_at": 0.0, "keys": {}}


def _jwk_to_pem(jwk: dict[str, Any]) -> str:
    """Convert a Google JWK ({kty,e,n}) into a PEM public key for PyJWT.

    PyJWT's ``RSAAlgorithm`` expects a PEM/derived key rather than a bare JWK
    dict, so we materialise the RSA public key ourselves (cryptography).
    """
    e = int.from_bytes(base64.urlsafe_b64decode(jwk["e"] + "=="), "big")
    n = int.from_bytes(base64.urlsafe_b64decode(jwk["n"] + "=="), "big")
    public_key = RSAPublicNumbers(e, n).public_key()
    return public_key.public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo).decode()


async def _fetch_certs(client: httpx.AsyncClient) -> dict[str, Any]:
    """Return the {kid: jwk} map, re-fetching at most every 5 minutes."""
    now = time.monotonic()
    if _certs_cache["keys"] and now - _certs_cache["fetched_at"] < _CACHE_TTL_SECONDS:
        return _certs_cache["keys"]
    response = await client.get(_CERTS_URL)
    response.raise_for_status()
    keys = {key["kid"]: key for key in response.json().get("keys", [])}
    _certs_cache["keys"] = keys
    _certs_cache["fetched_at"] = now
    return keys


async def verify_firebase_id_token(token: str, project_id: str) -> dict[str, Any]:
    """Verify a Firebase ID token and return its decoded claims.

    Claims include ``sub`` (the Firebase uid) and, when the provider provides
    them, ``email``, ``email_verified`` and ``name``.

    Raises a 401 HTTPException via FastAPI's error machinery on any failure
    (bad signature, wrong audience/issuer, expired token, network error).
    """
    if not project_id.strip():
        raise unauthorized(
            "FIREBASE_NOT_CONFIGURED",
            "Firebase sign-in is not configured on the server (set FIREBASE_PROJECT_ID).",
        )
    try:
        header = jwt.get_unverified_header(token)
        kid = header.get("kid")
        if not kid:
            raise ValueError("ID token is missing a 'kid' header.")

        async with httpx.AsyncClient(timeout=15.0) as client:
            keys = await _fetch_certs(client)
        signing_key = keys.get(kid)
        if signing_key is None:
            raise ValueError("ID token was signed with an unknown key.")

        claims = jwt.decode(
            token,
            _jwk_to_pem(signing_key),
            algorithms=["RS256"],
            audience=project_id,
            issuer=_ISSUER_TMPL.format(project_id=project_id),
            options={"require": ["exp", "iat", "sub", "aud", "iss"]},
            leeway=_CLOCK_SKEW_LEEWAY,
        )
        return claims
    except jwt.InvalidTokenError as exc:
        raise unauthorized(
            "INVALID_FIREBASE_TOKEN", f"Firebase token is invalid: {exc}"
        ) from None
    except Exception as exc:  # network or key-fetch failures
        raise unauthorized(
            "FIREBASE_VERIFY_FAILED", f"Could not verify Firebase token: {exc}"
        ) from None