"""VibeMate backend — FastAPI application entrypoint.

Run locally::

    cd backend
    python -m app.main            # serves http://host:port from settings/.env

Tests inject their own :class:`app.config.Settings` via :func:`create_app`.
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from fastapi import FastAPI

from app.ai import BaseAIEngine
from app.ai.demo import DemoAIEngine
from app.ai.gemini import GeminiAIEngine
from app.ai.openrouter import OpenRouterAIEngine
from app.ai.vision import OpenRouterVisionEngine
from app.api.routers import ai as ai_routes
from app.api.routers import auth as auth_routes
from app.api.routers import call as call_routes
from app.api.routers import chat as chat_routes
from app.config import Settings, load_settings
from app.domain import GAY_BOY_PERSONA, GAY_GIRL_PERSONA
from app.storage.base import BaseStore
from app.storage.firestore import FirestoreStore
from app.storage.memory import MemoryStore

logger = logging.getLogger("vibemate")

API_PREFIX = "/api/v1"


def build_store(settings: Settings) -> BaseStore:
    """Instantiate the storage backend selected by ``VIBEMATE_STORE``."""
    if settings.store == "firestore":
        return FirestoreStore.from_settings(settings)
    if settings.store == "memory":
        return MemoryStore()
    raise ValueError(f"Unknown VIBEMATE_STORE: {settings.store!r}")


def build_ai_engine(settings: Settings) -> BaseAIEngine:
    """Pick the AI engine: Gemini when a key is configured, else the demo."""
    if settings.ai_demo_mode:
        return DemoAIEngine()
    return GeminiAIEngine(
        api_key=settings.gemini_api_key,
        model=settings.gemini_model,
        temperature=settings.ai_temperature,
        max_output_tokens=settings.ai_max_output_tokens,
    )


def _openrouter_fallbacks(settings: Settings) -> list[str]:
    """Comma-separated OPENROUTER_FALLBACK_MODEL -> list of model ids."""
    return [m.strip() for m in settings.openrouter_fallback_model.split(",") if m.strip()]


def _openrouter_engine(
    settings: Settings, model: str, fallbacks: list[str]
) -> OpenRouterAIEngine:
    return OpenRouterAIEngine(
        api_key=settings.openrouter_api_key,
        model=model,
        temperature=settings.ai_temperature,
        max_output_tokens=settings.openrouter_max_output_tokens,
        base_url=settings.openrouter_base_url,
        http_referer=settings.openrouter_http_referer,
        http_title=settings.openrouter_http_title,
        fallback_models=fallbacks,
    )


def _openrouter_enabled(settings: Settings) -> bool:
    return bool(settings.openrouter_api_key.strip()) and not settings.ai_demo_mode


def build_private_ai_engine(settings: Settings) -> BaseAIEngine:
    """Default AI engine for 18+ private sessions.

    Uses OpenRouter whenever a key is configured; otherwise falls back to the
    default engine (demo mode in tests, Gemini for normal deployments). This is
    the seam where a deployment plugs in an uncensored/adult-capable model —
    pick the model id in ``OPENROUTER_MODEL`` and steer its behaviour through
    ``PRIVATE_SYSTEM_PROMPT`` (the app never hard-codes that content).
    """
    if _openrouter_enabled(settings):
        return _openrouter_engine(
            settings, settings.openrouter_model, _openrouter_fallbacks(settings)
        )
    return build_ai_engine(settings)


#: Personas that get their own dedicated private room + model.
#: "Gay" is one relation in the app but two personas (boy gay / girl gay), so
#: both are listed; the legacy "gay" id is folded onto GAY_BOY_PERSONA by
#: app.domain.normalise_persona, so old sessions ride the boy-gay engine.
PRIVATE_PERSONAS = (
    "girlfriend",
    "boyfriend",
    "crush",
    GAY_BOY_PERSONA,
    GAY_GIRL_PERSONA,
)


def build_private_ai_engines(settings: Settings) -> dict[str, BaseAIEngine]:
    """One private engine per persona, so each persona replies with its OWN model.

    ``OPENROUTER_MODEL_GIRLFRIEND`` / ``_BOYFRIEND`` / ``_CRUSH`` / ``_GAY``
    override the model per persona (empty = the shared ``OPENROUTER_MODEL``).
    Both gay personas share ``OPENROUTER_MODEL_GAY``: they are the same model
    family, and the persona prompt (couple behaviour + gender lock) is what keeps
    boy gay and girl gay apart. Returns an empty map when OpenRouter is not
    configured, so tests (no key) keep using the single ``app.state.private_ai_engine``
    seam unchanged.
    """
    if not _openrouter_enabled(settings):
        return {}
    fallbacks = _openrouter_fallbacks(settings)
    overrides = {
        "girlfriend": settings.openrouter_model_girlfriend,
        "boyfriend": settings.openrouter_model_boyfriend,
        "crush": settings.openrouter_model_crush,
        GAY_BOY_PERSONA: settings.openrouter_model_gay,
        GAY_GIRL_PERSONA: settings.openrouter_model_gay,
    }
    engines: dict[str, BaseAIEngine] = {}
    for persona in PRIVATE_PERSONAS:
        model = overrides.get(persona, "").strip() or settings.openrouter_model
        engines[persona] = _openrouter_engine(settings, model, fallbacks)
    return engines


def build_vision_ai_engine(settings: Settings) -> OpenRouterVisionEngine | None:
    """Vision reader for private-room photos.

    Uses the same OpenRouter key with a vision-capable model
    (``OPENROUTER_VISION_MODEL``, default
    ``mistralai/mistral-small-3.2-24b-instruct``) plus a fallback chain
    (``OPENROUTER_VISION_FALLBACK_MODELS``): explicit 18+ photos are exactly
    what providers refuse or moderate, so the reader tries every model before
    giving up. Returns ``None`` when OpenRouter is not configured — the private
    room then falls back to the caption-based hint.
    """
    if not settings.openrouter_api_key.strip():
        return None
    return OpenRouterVisionEngine(
        api_key=settings.openrouter_api_key,
        model=settings.openrouter_vision_model,
        fallback_models=settings.openrouter_vision_fallback_models,
        temperature=0.3,
        max_output_tokens=900,
        base_url=settings.openrouter_base_url,
        http_referer=settings.openrouter_http_referer,
        http_title=settings.openrouter_http_title,
    )


def create_app(settings: Settings | None = None) -> FastAPI:
    """Application factory. Pass ``settings`` explicitly in tests."""
    app_settings = settings or load_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        store = build_store(app_settings)
        ai_engine = build_ai_engine(app_settings)
        private_ai_engine = build_private_ai_engine(app_settings)
        private_ai_engines = build_private_ai_engines(app_settings)
        vision_ai_engine = build_vision_ai_engine(app_settings)
        await store.start()
        await ai_engine.start()
        await private_ai_engine.start()
        for persona_engine in private_ai_engines.values():
            await persona_engine.start()
        if vision_ai_engine is not None:
            await vision_ai_engine.start()
        app.state.store = store
        app.state.ai_engine = ai_engine
        app.state.private_ai_engine = private_ai_engine
        app.state.private_ai_engines = private_ai_engines
        app.state.vision_ai_engine = vision_ai_engine
        logger.info(
            "vibemate backend ready: store=%s ai=%s private_ai=%s personas=%s vision=%s",
            type(store).__name__,
            "demo" if isinstance(ai_engine, DemoAIEngine) else "gemini",
            "openrouter"
            if isinstance(private_ai_engine, OpenRouterAIEngine)
            else "demo"
            if isinstance(private_ai_engine, DemoAIEngine)
            else "gemini",
            {
                persona: engine.models[0]
                for persona, engine in private_ai_engines.items()
                if isinstance(engine, OpenRouterAIEngine)
            }
            or "single",
            "on" if vision_ai_engine is not None else "off",
        )
        yield
        if vision_ai_engine is not None:
            await vision_ai_engine.close()
        for persona_engine in private_ai_engines.values():
            await persona_engine.close()
        await private_ai_engine.close()
        await ai_engine.close()
        await store.close()

    app = FastAPI(
        title="VibeMate API",
        version="1.0.0",
        description=(
            "Backend for the VibeMate Android app: account auth, persisted "
            "chat sessions, and a Gemini-powered AI companion (deterministic "
            "demo mode when no API key is configured)."
        ),
        lifespan=lifespan,
    )
    app.state.settings = app_settings
    app.include_router(auth_routes.router, prefix=API_PREFIX)
    app.include_router(ai_routes.router, prefix=API_PREFIX)
    app.include_router(chat_routes.router, prefix=API_PREFIX)
    app.include_router(call_routes.router, prefix=API_PREFIX)

    @app.get("/health", tags=["meta"])
    async def health() -> dict[str, Any]:
        """Liveness probe describing the active store and AI modes."""
        per_persona: dict[str, list[str]] = {}
        for persona, engine in getattr(app.state, "private_ai_engines", {}).items():
            if isinstance(engine, OpenRouterAIEngine):
                per_persona[persona] = engine.models
        return {
            "status": "ok",
            "store": app_settings.store,
            "ai_mode": "demo" if app_settings.ai_demo_mode else "gemini",
            "private_ai_mode": (
                "openrouter"
                if app_settings.openrouter_api_key.strip()
                else "demo" if app_settings.ai_demo_mode else "gemini"
            ),
            "vision_reader": "on" if app_settings.openrouter_api_key.strip() else "off",
            # Which model each persona's private room actually uses (per-persona).
            "private_models": per_persona,
        }

    return app


# Module-level instance used by `uvicorn app.main:app`.
app = create_app()


if __name__ == "__main__":
    import uvicorn

    logging.basicConfig(level=logging.INFO)
    settings = load_settings()
    uvicorn.run(app, host=settings.host, port=settings.port)