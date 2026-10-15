"""Application configuration loaded from environment variables (and ``.env``).

The defaults mirror ``backend/.env.example``. Build a settings object with
:func:`load_settings` (respects a local ``.env`` file) or
``Settings.from_env(dict)`` (used by tests for hermetic configuration).
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from dotenv import load_dotenv

DEFAULT_JWT_SECRET = "dev-insecure-secret-change-me"


def _as_bool(value: str | None, default: bool) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _as_int(value: str | None, default: int) -> int:
    if value is None or not value.strip():
        return default
    return int(value)


def _as_float(value: str | None, default: float) -> float:
    if value is None or not value.strip():
        return default
    return float(value)


@dataclass(slots=True)
class Settings:
    """Runtime settings for the VibeMate backend."""

    # --- Firebase Auth bridge ------------------------------------------------
    # Firebase project id used to verify Android Firebase ID tokens
    # (https://console.firebase.google.com → Project settings → Project ID).
    firebase_project_id: str = ""

    # --- Storage -----------------------------------------------------------
    # "firestore" | "memory"
    store: str = "memory"
    # Path to a Google service-account JSON (required for firestore).
    google_application_credentials: str = ""
    # Firestore Google Cloud project id (falls back to the credentials file).
    gcp_project_id: str = ""

    # --- AI Engine (Google Gemini) -----------------------------------------
    # Single key or multiple comma-separated keys (auto-rotation on 429).
    gemini_api_key: str = ""
    gemini_model: str = "gemini-3.6-flash"
    ai_temperature: float = 0.9
    ai_max_output_tokens: int = 1024

    # --- AI Engine (OpenRouter) — used by 18+ private sessions --------------
    # OpenRouter is a generic LLM router API (OpenAI-compatible /chat/completions).
    # Private-mode chat (private_mode sessions + POST /ai/private) runs on this
    # engine so a separate model/provider can be picked per deployment.
    # Create a key at https://openrouter.ai/keys.
    openrouter_api_key: str = ""
    # Any model id served by OpenRouter. Default is an UNMODERATED role-play model
    # (Sao10K Euryale 3.3 70B, creator of the famous Fimbulvet3) so the private
    # room can actually produce adult output. Alternatives:
    #   "thedrummer/cydonia-24b-v4.1" — uncensored creative writer, cheaper,
    #   "cognitivecomputations/dolphin-mistral-24b-venice-edition" — Venice uncensored.
    openrouter_model: str = "sao10k/l3.3-euryale-70b"
    # Comma-separated fallback models, tried in order when the primary model's
    # provider is rate-limited (OpenRouter's free shared pool is often throttled
    # and different models route through different providers).
    openrouter_fallback_model: str = ""
    # Per-persona model overrides so the girlfriend / boyfriend / crush private
    # rooms each reply with their OWN model (distinct voice + personality).
    # Empty = fall back to OPENROUTER_MODEL for that persona.
    openrouter_model_girlfriend: str = ""
    openrouter_model_boyfriend: str = ""
    openrouter_model_crush: str = ""
    # Gay mode — BOTH gay personas ("Gay" is one relation in the app: a same-sex
    # couple, so the mate's gender is the user's own gender) share this ONE
    # override, exactly like the app does:
    #   gay_boy  -> mate = a MAN,   user = a man too   ("Boy Gay")
    #   gay_girl -> mate = a WOMAN, user = a woman too ("Girl Gay" / lesbian)
    # The legacy "gay" id (old sessions) folds onto gay_boy. The persona prompt
    # (couple behaviour + gender lock) is what keeps the two couples apart.
    openrouter_model_gay: str = ""
    # Longer max output for private replies (explicit role-play tends to run long).
    openrouter_max_output_tokens: int = 2048
    # VISION READER — the photo sent in a private room is actually "seen" by a
    # vision-capable OpenRouter model, whose description is then handed to the
    # adult LOVER model (OPENROUTER_MODEL) so the persona reacts to what is
    # really visible in the photo. The reader runs a DEEP whole-image scan
    # (scene / body parts / pose / look / clothing / lighting). VERIFIED LIVE:
    # qwen3-vl-235b-a22b (the biggest unmoderated, non-reasoning vision model)
    # named explicit content precisely. google/gemini-2.5-pro looked good on a
    # *drawing* but Google's real-photo filter renames explicit content into
    # "wall/door frame" — so gemini is NOT used as the primary reader.
    openrouter_vision_model: str = "qwen/qwen3-vl-235b-a22b-instruct"
    # Tried in order after the primary vision model when it refuses / moderates /
    # errors on a photo.
    openrouter_vision_fallback_models: str = (
        "mistralai/mistral-small-3.2-24b-instruct,meta-llama/llama-4-maverick,"
        "qwen/qwen2.5-vl-72b-instruct"
    )
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    openrouter_http_referer: str = "https://vibemate.example.com"
    openrouter_http_title: str = "VibeMate"
    # Optional system-prompt override for private (18+ adult) sessions. Leave
    # empty to use the normal persona prompts plus the built-in 18+ private-mode
    # override rule from app/api/routers/chat.py. Set this when you deploy a
    # custom adult-capable model whose behaviour you want to steer explicitly.
    private_system_prompt: str = ""

    # --- Behaviour ---------------------------------------------------------
    # Short-term memory window (number of history messages sent to Gemini).
    chat_history_window: int = 15

    # --- Security ----------------------------------------------------------
    jwt_secret: str = DEFAULT_JWT_SECRET
    jwt_algorithm: str = "HS256"
    # Access-token lifetime in minutes (default: 7 days).
    jwt_access_token_expire_minutes: int = 60 * 24 * 7

    # --- Server ------------------------------------------------------------
    host: str = "0.0.0.0"
    port: int = 8000

    # Derived flags ---------------------------------------------------------
    @property
    def ai_demo_mode(self) -> bool:
        """True when no Gemini API key is configured (deterministic mock)."""
        return not self.gemini_api_key.strip()

    @property
    def credentials_path(self) -> Path | None:
        """Absolute path of the service-account JSON, or ``None``."""
        raw = self.google_application_credentials.strip()
        if not raw:
            return None
        path = Path(raw).expanduser()
        return path if path.is_file() else None

    # Factories -------------------------------------------------------------
    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> "Settings":
        env = os.environ if environ is None else environ

        def get(name: str, default: str | None = None) -> str:
            value = env.get(name) if hasattr(env, "get") else None
            if value is None and default is not None:
                return default
            return (value or "").strip()

        return cls(
            store=get("VIBEMATE_STORE", "memory").lower(),
            google_application_credentials=get("GOOGLE_APPLICATION_CREDENTIALS"),
            gcp_project_id=get("GCP_PROJECT_ID"),
            firebase_project_id=get("FIREBASE_PROJECT_ID"),
            gemini_api_key=get("GEMINI_API_KEY"),
            gemini_model=get("GEMINI_MODEL", "gemini-3.6-flash"),
            ai_temperature=_as_float(get("AI_TEMPERATURE"), 0.9),
            ai_max_output_tokens=_as_int(get("AI_MAX_OUTPUT_TOKENS"), 1024),
            openrouter_api_key=get("OPENROUTER_API_KEY"),
            openrouter_model=get("OPENROUTER_MODEL", "sao10k/l3.3-euryale-70b"),
            openrouter_fallback_model=get("OPENROUTER_FALLBACK_MODEL"),
            openrouter_model_girlfriend=get("OPENROUTER_MODEL_GIRLFRIEND"),
            openrouter_model_boyfriend=get("OPENROUTER_MODEL_BOYFRIEND"),
            openrouter_model_crush=get("OPENROUTER_MODEL_CRUSH"),
            openrouter_model_gay=get("OPENROUTER_MODEL_GAY"),
            openrouter_max_output_tokens=_as_int(get("OPENROUTER_MAX_OUTPUT_TOKENS"), 2048),
            openrouter_vision_model=get(
                "OPENROUTER_VISION_MODEL", "qwen/qwen3-vl-235b-a22b-instruct"
            ),
            openrouter_vision_fallback_models=get(
                "OPENROUTER_VISION_FALLBACK_MODELS",
                "mistralai/mistral-small-3.2-24b-instruct,meta-llama/llama-4-maverick,"
                "qwen/qwen2.5-vl-72b-instruct",
            ),
            openrouter_base_url=get("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"),
            openrouter_http_referer=get("OPENROUTER_HTTP_REFERER", "https://vibemate.example.com"),
            openrouter_http_title=get("OPENROUTER_HTTP_TITLE", "VibeMate"),
            private_system_prompt=get("PRIVATE_SYSTEM_PROMPT"),
            chat_history_window=_as_int(get("CHAT_HISTORY_WINDOW"), 15),
            jwt_secret=get("JWT_SECRET", DEFAULT_JWT_SECRET),
            jwt_algorithm=get("JWT_ALGORITHM", "HS256"),
            jwt_access_token_expire_minutes=_as_int(
                get("JWT_ACCESS_TOKEN_EXPIRE_MINUTES"), 60 * 24 * 7
            ),
            host=get("VIBEMATE_HOST", "0.0.0.0"),
            port=_as_int(get("VIBEMATE_PORT"), 8000),
        )


def load_settings(environ: Mapping[str, str] | None = None) -> Settings:
    """Load settings, honouring an ``.env`` file next to this package."""
    load_dotenv()
    return Settings.from_env(environ)