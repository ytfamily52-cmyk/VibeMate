"""Shared FastAPI dependencies."""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Header, Request

from app.ai import BaseAIEngine
from app.ai.vision import OpenRouterVisionEngine
from app.config import Settings
from app.domain import User, normalise_persona
from app.errors import unauthorized
from app.security import decode_access_token
from app.storage.base import BaseStore


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_store(request: Request) -> BaseStore:
    return request.app.state.store


def get_ai_engine(request: Request) -> BaseAIEngine:
    return request.app.state.ai_engine


class PrivateEngineResolver:
    """Picks the private (18+) engine for a given persona.

    Each persona (girlfriend / boyfriend / crush / gay_boy / gay_girl) can run its
    OWN OpenRouter model, so their private rooms reply with different voices.
    The legacy "gay" id is folded onto "gay_boy" first, so an old session still
    reaches the boy-gay engine. Falls back to ``app.state.private_ai_engine`` when
    no per-persona engines are configured (e.g. tests, or OpenRouter not set up).
    """

    def __init__(self, default: BaseAIEngine, by_persona: dict[str, BaseAIEngine]) -> None:
        self._default = default
        self._by_persona = by_persona

    def for_persona(self, persona: str) -> BaseAIEngine:
        return self._by_persona.get(normalise_persona(persona)) or self._default


def get_private_ai_engine(request: Request) -> PrivateEngineResolver:
    """Resolver for the 18+ private engine (per-persona when configured)."""
    by_persona = getattr(request.app.state, "private_ai_engines", None) or {}
    return PrivateEngineResolver(request.app.state.private_ai_engine, by_persona)


def get_vision_ai_engine(request: Request) -> OpenRouterVisionEngine | None:
    """Vision reader for private-room photos; None when not configured.

    The private-room photo flow is a two-engine pipeline: the vision reader
    first "sees" the photo (describes what is actually visible), then the adult
    model replies in character from that description.
    """
    return request.app.state.vision_ai_engine


async def get_current_user(
    request: Request,
    authorization: Annotated[str | None, Header()] = None,
) -> User:
    """Resolve the authenticated user from the ``Authorization: Bearer`` header."""
    if not authorization or not authorization.lower().startswith("bearer "):
        raise unauthorized("UNAUTHORIZED", "Missing or malformed Authorization header.")
    token = authorization.split(" ", 1)[1].strip()
    if not token:
        raise unauthorized("UNAUTHORIZED", "Missing or malformed Authorization header.")

    settings: Settings = request.app.state.settings
    store: BaseStore = request.app.state.store
    subject = decode_access_token(token, settings.jwt_secret, settings.jwt_algorithm)
    user = await store.get_user_by_id(subject)
    if user is None:
        raise unauthorized("USER_NOT_FOUND", "The account for this token no longer exists.")
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]
StoreDep = Annotated[BaseStore, Depends(get_store)]
SettingsDep = Annotated[Settings, Depends(get_settings)]
AIEngineDep = Annotated[BaseAIEngine, Depends(get_ai_engine)]
PrivateAIEngineDep = Annotated[PrivateEngineResolver, Depends(get_private_ai_engine)]
VisionAIEngineDep = Annotated[OpenRouterVisionEngine | None, Depends(get_vision_ai_engine)]