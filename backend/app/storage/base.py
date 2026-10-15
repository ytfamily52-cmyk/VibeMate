"""Storage abstraction used by the API layer."""
from __future__ import annotations

from abc import ABC, abstractmethod

from app.domain import ChatSession, User


class BaseStore(ABC):
    """Minimal persistence contract for users and chat sessions.

    Backends are expected to be *async* (instantiated per application
    lifecycle) and to raise :class:`app.errors.DuplicateEmailError` when
    ``create_user`` is called with an already-registered email.
    """

    # --- Users -------------------------------------------------------------

    @abstractmethod
    async def create_user(self, user: User) -> None:
        """Persist a new user (raises DuplicateEmailError on email clash)."""

    @abstractmethod
    async def get_user_by_id(self, user_id: str) -> User | None:
        """Return the user with ``user_id`` or ``None``."""

    @abstractmethod
    async def get_user_by_email(self, email: str) -> User | None:
        """Return the user with ``email`` (case-insensitive lookup) or ``None``."""

    # --- Chat sessions -----------------------------------------------------

    @abstractmethod
    async def create_session(self, session: ChatSession) -> None:
        """Persist a new chat session."""

    @abstractmethod
    async def get_session(self, session_id: str) -> ChatSession | None:
        """Return a chat session (with messages) or ``None``."""

    @abstractmethod
    async def put_session(self, session: ChatSession) -> None:
        """Replace an existing chat session (used after appending messages)."""

    @abstractmethod
    async def list_sessions(self, owner_id: str) -> list[ChatSession]:
        """List a user's sessions, newest ``updated_at`` first."""

    @abstractmethod
    async def delete_session(self, session_id: str) -> None:
        """Delete a chat session (no-op when it does not exist)."""

    # --- Call records -------------------------------------------------------

    @abstractmethod
    async def create_call(self, call_record: dict) -> None:
        """Persist a new call record (id, user_id, session_id, persona, ...)."""

    @abstractmethod
    async def update_call(self, call_record: dict) -> None:
        """Update an existing call record (e.g. flip status to "ended")."""

    @abstractmethod
    async def list_calls(self, user_id: str) -> list[dict]:
        """List a user's call records, most recent first."""

    # --- Lifecycle ---------------------------------------------------------

    async def start(self) -> None:
        """Open connections / resources (optional hook)."""

    async def close(self) -> None:
        """Close connections / resources (optional hook)."""