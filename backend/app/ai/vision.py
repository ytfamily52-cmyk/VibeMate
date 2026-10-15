"""OpenRouter vision reader — the eyes of VibeMate's private room.

The private-mode photo flow is a small two-engine pipeline:

    1. VISION READER   :class:`OpenRouterVisionEngine` — a vision-capable
       OpenRouter model (default ``mistralai/mistral-small-3.2-24b-instruct``,
       with a fallback chain) receives the photo as a base64 data-URI and
       describes what is actually visible in it (plus a one-line "aura").
    2. ADULT LOVER     :class:`app.ai.openrouter.OpenRouterAIEngine` (Euryale) —
       receives that description as its user turn and answers fully in
       character, so the girlfriend/boyfriend genuinely "sees" the photo the
       user sent instead of guessing from the caption alone.

Explicit 18+ photos are exactly what a vision provider may refuse or
moderate, so the reader tries EVERY configured model (primary +
``OPENROUTER_VISION_FALLBACK_MODELS``) and skips canned refusals — a refusal
must never travel to the adult model as if it described the photo. If the
whole chain refuses, or the reader is not configured, the caller in
``app/api/routers/ai.py`` falls back to the caption-based hint.

Only the privacy-friendliest headers are sent. No key is ever logged.
"""
from __future__ import annotations

import httpx

from app.ai import AIEngineError

_DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

# Default vision reader — the PRIMARY: qwen3-vl-235b-a22b-instruct, the
# biggest unmoderated + non-reasoning vision model here. VERIFIED LIVE
# (test_deep_readers.py): named explicit content precisely ("…human penis and
# scrotum… glans… shaft… testicles…"). google/gemini-2.5-pro looked great on a
# *drawing* proxy but Google's real-photo safety filter RENAMES a real explicit
# photo into "wall/door frame" — the "still not identifying" bug. The
# whole-image scan prompt (see _VISION_PROMPT in ai.py) itemises every detail.
_DEFAULT_VISION_MODEL = "qwen/qwen3-vl-235b-a22b-instruct"

# Tried in order after the primary when it refuses / moderates / errors.
_DEFAULT_VISION_FALLBACKS = (
    "mistralai/mistral-small-3.2-24b-instruct,meta-llama/llama-4-maverick,"
    "qwen/qwen2.5-vl-72b-instruct"
)

# Canned refusals a vision provider answers with instead of a description when
# the photo is explicit. Treated as "this reader declined" — the next vision
# model in the chain is tried instead of letting a refusal travel to the adult
# model as if it described the photo. Image-specific phrasing only, so a
# genuine VISUAL/AURA description is never mistaken for a refusal.
_VISION_REFUSAL_MARKERS = (
    "i can't describe", "i cannot describe",
    "i can't view", "i cannot view", "unable to view",
    "i can't see this", "i cannot see this",
    "i can't see the image", "i cannot see the image",
    "i can't identify", "i cannot identify",
    "i can't process", "i cannot process",
    "i can't help", "i cannot help",
    "i can't assist", "i cannot assist",
    "i can't provide", "i cannot provide",
    "i can't share", "i cannot share",
    "i can't comply", "i must decline",
    "i'm sorry", "i am sorry", "i apologize", "i apologise",
    "i'm unable", "i am unable", "i'm not able", "i am not able",
    "won't be able to", "will not be able to",
    "as an ai", "content policy", "community guidelines",
    "terms of service", "acceptable use",
    "explicit content", "sexual content", "adult content",
    "nsfw content", "inappropriate content",
    # "Invisible image" replies: some readers answer a photo with coaching
    # chit-chat as if NO image arrived ("Understood. Please send the photo...",
    # "Since there's no photo provided... [insert description]" — observed
    # live). That is not a description of the photo either — skip this reader.
    "please send the photo", "please share the photo", "please upload",
    "provide the image", "provide the photo", "share the image",
    "no photo", "no image", "no picture",
    "there is no image", "there's no image", "there is no picture",
    "i would examine", "[insert", "absence of an actual image",
    "without an image", "can't see any image", "cannot see any image",
)


def _looks_like_refusal(text: str) -> bool:
    """True when a vision reply is a canned refusal, not a photo description.

    The cap is generous (2000 chars): the reader is asked for 2 short lines
    (~150-500 chars), and llama-4's ~430-char "no photo provided... [insert
    description]" reply must ALSO be caught, which a 300-char cap let slip.
    """
    stripped = (text or "").strip()
    if not stripped or len(stripped) > 2000:
        return False
    lowered = stripped.lower()
    return any(marker in lowered for marker in _VISION_REFUSAL_MARKERS)


class OpenRouterVisionEngine:
    """Single-photo vision reader with a fallback chain of vision models."""

    def __init__(
        self,
        api_key: str,
        model: str = _DEFAULT_VISION_MODEL,
        fallback_models: str = _DEFAULT_VISION_FALLBACKS,
        temperature: float = 0.3,
        max_output_tokens: int = 600,
        base_url: str = _DEFAULT_BASE_URL,
        http_referer: str = "",
        http_title: str = "",
        timeout: float = 90.0,
    ) -> None:
        self._api_key = api_key.strip()
        # Primary reader first, then the fallbacks — trimmed, deduped, and never
        # empty (a blank primary falls back to the built-in default).
        models: list[str] = []
        for candidate in f"{model},{fallback_models}".split(","):
            cleaned = candidate.strip()
            if cleaned and cleaned not in models:
                models.append(cleaned)
        self._models = models or [_DEFAULT_VISION_MODEL]
        self._temperature = temperature
        self._max_output_tokens = max_output_tokens
        self._base_url = base_url.strip().rstrip("/") or _DEFAULT_BASE_URL
        self._http_referer = http_referer
        self._http_title = http_title
        self._timeout = timeout
        self._client: httpx.AsyncClient | None = None

    @property
    def is_demo(self) -> bool:
        return False

    async def start(self) -> None:
        self._client = httpx.AsyncClient(timeout=self._timeout)

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    async def describe_image(self, image_data_uri: str, prompt: str) -> str:
        """Describe the photo with the first vision model that actually does.

        Tries every configured model in order (primary + fallbacks). A model is
        skipped when it errors, returns an empty/None reply (reasoning models
        that spend the whole budget thinking), answers "UNSURE", or answers with
        a canned refusal — explicit 18+ photos are exactly what providers
        moderate, and a refusal must never travel on as if it described the
        photo. Raises :class:`AIEngineError` only when EVERY model failed.
        """
        if not self._api_key:
            raise AIEngineError("OpenRouter API key is not configured.")
        last_error: Exception | None = None
        for model in self._models:
            try:
                text = (await self._chat_text(model, image_data_uri, prompt)).strip()
            except (AIEngineError, httpx.HTTPError) as exc:
                # This reader failed (error / rate limit / empty reply) — the
                # next one in the chain may still describe the photo.
                last_error = exc
                continue
            if not text or text.upper().startswith("UNSURE") or _looks_like_refusal(text):
                last_error = AIEngineError(
                    f"vision model {model} did not describe the photo"
                )
                continue
            return text
        raise AIEngineError(
            f"every vision model failed or refused the photo; last: {last_error}"
        ) from last_error

    async def _chat_text(self, model: str, image_data_uri: str, prompt: str) -> str:
        """One HTTP round-trip against ONE vision model. Raises on failure."""
        if self._client is None:
            raise RuntimeError("OpenRouterVisionEngine.start() must be called first")
        payload = {
            "model": model,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {"type": "image_url", "image_url": {"url": image_data_uri}},
                    ],
                }
            ],
            "temperature": self._temperature,
            "max_tokens": self._max_output_tokens,
        }
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        if self._http_referer:
            headers["HTTP-Referer"] = self._http_referer
        if self._http_title:
            headers["X-Title"] = self._http_title

        response = await self._client.post(
            f"{self._base_url}/chat/completions",
            headers=headers,
            json=payload,
        )
        if response.status_code == 429:
            raise AIEngineError(
                "OpenRouter rate limit reached. Please try again in a minute."
            )
        if response.status_code == 402:
            raise AIEngineError(
                "OpenRouter credits exhausted. Please top up your OpenRouter account."
            )
        response.raise_for_status()

        data = response.json()
        try:
            content = data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise AIEngineError(f"Unexpected OpenRouter vision response: {data}") from exc
        if content is None:
            # Reasoning models can burn the whole budget thinking and return
            # content = null — that is THIS model failing, not a description.
            raise AIEngineError(
                f"vision model {model} returned an empty reply (content=null)"
            )
        return str(content).strip()