"""Thread-safe in-memory store (demo / tests only).

Data is process-local and lost on restart, exactly as documented in
``.env.example`` (``VIBEMATE_STORE=memory``).
"""
from __future__ import annotations

import threading

from app.domain import ChatSession, User
from app.errors import DuplicateEmailError
from app.storage.base import BaseStore


class MemoryStore(BaseStore):
    def __init__(self) -> None:
        self._users_by_id: dict[str, User] = {}
        self._ids_by_email: dict[str, str] = {}
        self._sessions: dict[str, ChatSession] = {}
        self._calls: dict[str, dict] = {}  # call_id -> call_record
        self._lock = threading.RLock()

    # --- Users -------------------------------------------------------------

    async def create_user(self, user: User) -> None:
        with self._lock:
            if self._ids_by_email.get(user.email):
                raise DuplicateEmailError(f"email already registered: {user.email}")
            self._users_by_id[user.id] = user
            self._ids_by_email[user.email] = user.id

    async def get_user_by_id(self, user_id: str) -> User | None:
        with self._lock:
            return self._users_by_id.get(user_id)

    async def get_user_by_email(self, email: str) -> User | None:
        with self._lock:
            user_id = self._ids_by_email.get(email.strip().lower())
            return self._users_by_id.get(user_id) if user_id else None

    # --- Chat sessions -----------------------------------------------------

    async def create_session(self, session: ChatSession) -> None:
        with self._lock:
            self._sessions[session.id] = session

    async def get_session(self, session_id: str) -> ChatSession | None:
        with self._lock:
            return self._sessions.get(session_id)

    async def put_session(self, session: ChatSession) -> None:
        with self._lock:
            self._sessions[session.id] = session

    async def list_sessions(self, owner_id: str) -> list[ChatSession]:
        with self._lock:
            owned = [s for s in self._sessions.values() if s.owner_id == owner_id]
            return sorted(owned, key=lambda s: s.updated_at, reverse=True)

    async def delete_session(self, session_id: str) -> None:
        with self._lock:
            self._sessions.pop(session_id, None)

    # --- Calls -------------------------------------------------------------

    async def create_call(self, call_record: dict) -> None:
        """Persist a new call record."""
        with self._lock:
            self._calls[call_record["id"]] = call_record

    async def update_call(self, call_record: dict) -> None:
        """Update an existing call record."""
        with self._lock:
            if call_record["id"] in self._calls:
                self._calls[call_record["id"]] = call_record

    async def list_calls(self, user_id: str) -> list[dict]:
        """List calls for a user, most recent first."""
        with self._lock:
            user_calls = [
                c for c in self._calls.values() if c.get("user_id") == user_id
            ]
            return sorted(user_calls, key=lambda c: c.get("started_at", ""), reverse=True)