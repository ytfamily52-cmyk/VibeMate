"""Internal domain models shared by the storage and API layers."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field


def new_id() -> str:
    """Generate a short URL-safe unique identifier (32 hex chars)."""
    return uuid.uuid4().hex


def utc_now() -> datetime:
    """TZ-aware UTC now, suitable for Firestore timestamps."""
    return datetime.now(timezone.utc)


class MessageRole(str, Enum):
    user = "user"
    assistant = "assistant"
    system = "system"


# --- Personas ---------------------------------------------------------------
# "Gay" is ONE relation in the app's dropdown (a same-sex couple, so the mate's
# gender is the user's own gender) but TWO personas, because the two couples
# behave — and must be prompted — differently:
#   GAY_BOY_PERSONA  -> mate = MAN,   user = MAN   ("Boy Gay")
#   GAY_GIRL_PERSONA -> mate = WOMAN, user = WOMAN ("Girl Gay" / lesbian)
# LEGACY_GAY_PERSONA ("gay") is the old single (boy-gay) id: sessions already
# stored with persona = "gay" keep working, because normalise_persona() folds
# them onto GAY_BOY_PERSONA before any prompt / model lookup.
LEGACY_GAY_PERSONA = "gay"
GAY_BOY_PERSONA = "gay_boy"
GAY_GIRL_PERSONA = "gay_girl"
GAY_PERSONAS = (LEGACY_GAY_PERSONA, GAY_BOY_PERSONA, GAY_GIRL_PERSONA)


def normalise_persona(persona: str) -> str:
    """Fold the legacy "gay" id onto the exact boy-gay persona id.

    Every other id (exact personas, blank) is returned trimmed and unchanged, so
    callers can use the result directly as a prompt / engine key.
    """
    cleaned = (persona or "").strip()
    return GAY_BOY_PERSONA if cleaned == LEGACY_GAY_PERSONA else cleaned


class User(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str
    email: str
    password_hash: str
    display_name: str
    created_at: datetime


class Message(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str
    role: MessageRole
    content: str
    created_at: datetime


class ChatSession(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str
    owner_id: str
    title: str
    created_at: datetime
    updated_at: datetime
    messages: list[Message] = Field(default_factory=list)
    # AI persona for this session:
    # "girlfriend" | "boyfriend" | "crush" | "gay_boy" | "gay_girl" ("" = default).
    # The legacy "gay" id is still accepted and means "gay_boy".
    persona: str = ""
    # 18+ verified private (adult) chat mode for this session.
    private_mode: bool = False