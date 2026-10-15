"""Pydantic request/response schemas for the public HTTP API."""
from __future__ import annotations

import re
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


# --- Auth ------------------------------------------------------------------


class RegisterRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=8, max_length=128)
    display_name: str = Field(default="", max_length=64)

    @field_validator("email")
    @classmethod
    def _normalise_email(cls, value: str) -> str:
        value = value.strip().lower()
        if not _EMAIL_RE.match(value):
            raise ValueError("email must be a valid email address")
        return value


class LoginRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=1, max_length=128)

    @field_validator("email")
    @classmethod
    def _normalise_email(cls, value: str) -> str:
        return value.strip().lower()


class FirebaseLoginRequest(BaseModel):
    """Firebase ID token minted by the Android app (email/anonymous/Google)."""

    id_token: str = Field(min_length=20, max_length=4096)
    display_name: str = Field(default="", max_length=64)

    @field_validator("display_name")
    @classmethod
    def _strip_display_name(cls, value: str) -> str:
        return value.strip() if value else value


class UserOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    email: str
    display_name: str
    created_at: datetime


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in: int  # seconds
    user: UserOut


# --- Chat ------------------------------------------------------------------


class CreateSessionRequest(BaseModel):
    title: str = Field(default="", max_length=120)
    # AI persona chosen in the app: "girlfriend" | "boyfriend" | "crush" |
    # "gay_boy" | "gay_girl" (the legacy "gay" id = "gay_boy"; "" = default).
    persona: str = Field(default="", pattern="^(girlfriend|boyfriend|crush|gay|gay_boy|gay_girl|)$")
    # 18+ verified "private" (adult) chat flag - turns on the private-mode rule.
    private_mode: bool = False


class ChatRequest(BaseModel):
    content: str = Field(min_length=1, max_length=4000)

    @field_validator("content")
    @classmethod
    def _strip_content(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("content must not be blank")
        return value


class MessageOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    role: str
    content: str
    created_at: datetime


class SessionOut(BaseModel):
    id: str
    title: str
    owner_id: str
    created_at: datetime
    updated_at: datetime
    persona: str = ""
    private_mode: bool = False


class SessionDetailOut(SessionOut):
    messages: list[MessageOut]


class ChatResponse(BaseModel):
    session_id: str
    user_message: MessageOut
    assistant_message: MessageOut


# --- AI (private mode) -------------------------------------------------------


class PrivateHistoryMessage(BaseModel):
    """One previous turn of a private-mode conversation (stateless replay)."""

    role: str  # "user" | "model"
    content: str

    @field_validator("role")
    @classmethod
    def _normalise_role(cls, value: str) -> str:
        if value not in ("user", "model"):
            raise ValueError("role must be 'user' or 'model'")
        return value


class PrivateAttachment(BaseModel):
    """Attachment metadata for a private-mode message (photo / voice note / etc.).

    For an ``IMAGE`` the Android app also sends the ``data`` base64 payload so
    the backend's vision reader can genuinely *see* the photo before the adult
    model replies. Audio / video / document only carry type + file name.
    """

    type: str = Field(default="", max_length=16)  # "IMAGE" | "AUDIO" | "VIDEO" | "DOC"
    file_name: str = Field(default="", max_length=255)
    # Base64 image bytes (JPEG), sent only when type == "IMAGE".
    data: str = Field(default="", max_length=7_000_000)


class PrivateReplyRequest(BaseModel):
    """Stateless private (18+) reply request from the Android private room."""

    persona: str = Field(default="", pattern="^(girlfriend|boyfriend|crush|gay|gay_boy|gay_girl|)$")
    # Caption of the message; may be empty (e.g. a photo sent without text).
    content: str = Field(default="", min_length=0, max_length=4000)
    history: list[PrivateHistoryMessage] = Field(default_factory=list, max_length=100)
    attachment: PrivateAttachment | None = None

    @field_validator("content")
    @classmethod
    def _strip_content(cls, value: str) -> str:
        return value.strip()


class PrivateReplyOut(BaseModel):
    reply: str
    # One-line "photo read" note (aura + short visual) shown in the app under
    # the photo to prove the vision reader actually saw it. Empty when the
    # message had no readable photo or the vision step was skipped.
    vision_note: str = ""


# --- Call ------------------------------------------------------------------


class StartCallRequest(BaseModel):
    session_id: str = Field(default="", max_length=64)
    persona: str = Field(default="", pattern="^(girlfriend|boyfriend|crush|gay|gay_boy|gay_girl|)$")


class CallResponse(BaseModel):
    call_id: str
    session_id: str
    status: str  # "ringing" | "connected" | "ended"
    persona: str
    message: str