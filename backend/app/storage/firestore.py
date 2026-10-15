"""Firestore-backed store built on :mod:`app.storage.firestore_client`.

Documents:

- ``users/{user_id}`` — ``email``, ``display_name``, ``password_hash``, ``created_at``
- ``chat_sessions/{session_id}`` — ``owner_id``, ``title``, ``created_at``,
  ``updated_at`` and a ``messages`` array of ``{id, role, content, created_at}`` maps.
"""
from __future__ import annotations

from app.config import Settings
from app.domain import ChatSession, Message, User
from app.errors import DuplicateEmailError
from app.storage.base import BaseStore
from app.storage.firestore_client import FirestoreClient


class FirestoreStore(BaseStore):
    def __init__(self, client: FirestoreClient) -> None:
        self._client = client

    @classmethod
    def from_settings(cls, settings: Settings) -> "FirestoreStore":
        path = settings.credentials_path
        if path is None:
            raise RuntimeError(
                "VIBEMATE_STORE=firestore requires GOOGLE_APPLICATION_CREDENTIALS "
                "to point at a service-account JSON file."
            )
        return cls(FirestoreClient(path, settings.gcp_project_id))

    # Lifecycle ---------------------------------------------------------------

    async def start(self) -> None:
        await self._client.start()

    async def close(self) -> None:
        await self._client.close()

    # Users --------------------------------------------------------------------

    @staticmethod
    def _user_to_doc(user: User) -> dict:
        return {
            "email": user.email,
            "display_name": user.display_name,
            "password_hash": user.password_hash,
            "created_at": user.created_at,
        }

    @staticmethod
    def _user_from_doc(user_id: str, doc: dict) -> User:
        return User(
            id=user_id,
            email=doc["email"],
            password_hash=doc["password_hash"],
            display_name=doc.get("display_name", ""),
            created_at=doc.get("created_at"),
        )

    async def create_user(self, user: User) -> None:
        if await self.get_user_by_email(user.email) is not None:
            raise DuplicateEmailError(f"email already registered: {user.email}")
        await self._client.set_document(f"users/{user.id}", self._user_to_doc(user))

    async def get_user_by_id(self, user_id: str) -> User | None:
        doc = await self._client.get_document(f"users/{user_id}")
        return self._user_from_doc(user_id, doc) if doc else None

    async def get_user_by_email(self, email: str) -> User | None:
        rows = await self._client.query_documents("users", ["email"], email.strip().lower())
        if not rows:
            return None
        row = rows[0]
        return self._user_from_doc(row["_id"], row)

    # Chat sessions --------------------------------------------------------------

    @staticmethod
    def _session_to_doc(session: ChatSession) -> dict:
        return {
            "owner_id": session.owner_id,
            "title": session.title,
            "persona": session.persona,
            "private_mode": session.private_mode,
            "created_at": session.created_at,
            "updated_at": session.updated_at,
            "messages": [
                {
                    "id": message.id,
                    "role": message.role.value,
                    "content": message.content,
                    "created_at": message.created_at,
                }
                for message in session.messages
            ],
        }

    @staticmethod
    def _session_from_doc(session_id: str, doc: dict) -> ChatSession:
        messages = [Message(**message) for message in doc.get("messages", [])]
        return ChatSession(
            id=session_id,
            owner_id=doc["owner_id"],
            title=doc.get("title", ""),
            persona=doc.get("persona", ""),
            private_mode=doc.get("private_mode", False),
            created_at=doc.get("created_at"),
            updated_at=doc.get("updated_at"),
            messages=messages,
        )

    async def create_session(self, session: ChatSession) -> None:
        await self._client.set_document(f"chat_sessions/{session.id}", self._session_to_doc(session))

    async def get_session(self, session_id: str) -> ChatSession | None:
        doc = await self._client.get_document(f"chat_sessions/{session_id}")
        return self._session_from_doc(session_id, doc) if doc else None

    async def put_session(self, session: ChatSession) -> None:
        await self._client.set_document(f"chat_sessions/{session.id}", self._session_to_doc(session))

    async def list_sessions(self, owner_id: str) -> list[ChatSession]:
        rows = await self._client.query_documents(
            "chat_sessions",
            ["owner_id"],
            owner_id,
            order_by="updated_at",
            direction="DESCENDING",
        )
        return [self._session_from_doc(row["_id"], row) for row in rows]

    async def delete_session(self, session_id: str) -> None:
        await self._client.delete_document(f"chat_sessions/{session_id}")

    # Call records -------------------------------------------------------------

    @staticmethod
    def _call_to_doc(call_record: dict) -> dict:
        return {
            "user_id": call_record.get("user_id", ""),
            "session_id": call_record.get("session_id", ""),
            "persona": call_record.get("persona", ""),
            "status": call_record.get("status", ""),
            "started_at": call_record.get("started_at"),
            "ended_at": call_record.get("ended_at"),
        }

    async def create_call(self, call_record: dict) -> None:
        await self._client.set_document(
            f"call_records/{call_record['id']}", self._call_to_doc(call_record)
        )

    async def update_call(self, call_record: dict) -> None:
        await self._client.set_document(
            f"call_records/{call_record['id']}", self._call_to_doc(call_record)
        )

    async def list_calls(self, user_id: str) -> list[dict]:
        rows = await self._client.query_documents(
            "call_records",
            ["user_id"],
            user_id,
            order_by="started_at",
            direction="DESCENDING",
        )
        calls: list[dict] = []
        for row in rows:
            call = dict(row)
            call["id"] = row["_id"]
            calls.append(call)
        return calls