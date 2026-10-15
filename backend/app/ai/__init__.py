"""AI engines (Gemini REST and deterministic demo mode)."""
from __future__ import annotations

from abc import ABC, abstractmethod

from app.domain import Message


class AIEngineError(Exception):
    """Raised when the AI engine cannot produce a reply."""


class BaseAIEngine(ABC):
    """Contract for reply generation. Instances are async and lifecycle-managed."""

    @abstractmethod
    async def generate_reply(
        self,
        messages: list[Message],
        system_prompt: str | None = None,
    ) -> str:
        """Generate the next assistant message given the conversation history.

        ``system_prompt`` optionally customises the assistant's persona
        (e.g. girlfriend / boyfriend / crush) — engines that support it apply
        it as the model's system instruction, others may ignore it.
        """

    async def start(self) -> None:
        """Open connections / resources (optional hook)."""

    async def close(self) -> None:
        """Close connections / resources (optional hook)."""