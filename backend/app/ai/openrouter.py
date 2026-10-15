"""OpenRouter AI engine (OpenAI-compatible ``/chat/completions``).

OpenRouter (https://openrouter.ai) is a generic LLM router: one API key gives
access to hundreds of models from many providers. The private (18+) chat mode
uses this engine so a deployment can pick its own model for adult conversation
without touching any other code. The default model is Sao10K Euryale 3.3 70B
(an unmoderated role-play model) so the private room produces adult output out
of the box.

Only the privacy-friendliest headers are sent. No key is ever logged.
"""
from __future__ import annotations

import httpx

from app.ai import AIEngineError, BaseAIEngine
from app.domain import Message, MessageRole

_DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"


def _error_detail(response: "httpx.Response") -> str:
    """Best-effort extraction of OpenRouter's error message from a response.

    OpenRouter nests the useful reason (e.g. "temporarily rate-limited upstream")
    under ``error.metadata.raw``, so include it along with the top-level message.
    """
    try:
        data = response.json()
        if isinstance(data, dict):
            err = data.get("error")
            if isinstance(err, dict):
                message = str(err.get("message") or "").strip()
                raw = ""
                meta = err.get("metadata")
                if isinstance(meta, dict):
                    raw = str(meta.get("raw") or "").strip()
                    provider = meta.get("provider_name")
                    if not raw and provider:
                        raw = f"provider={provider}"
                combined = " ".join(part for part in (message, raw) if part)
                return combined[:400] if combined else str(err)[:400]
            if err:
                return str(err)[:400]
        return str(data)[:400]
    except Exception:
        return (response.text or "")[:400]


def _is_fatal_error(exc: Exception) -> bool:
    """Errors that affect every model equally (auth / credits / config).

    Anything else (upstream provider errors, rate limits, 5xx, timeouts) is
    worth retrying on the next configured model.
    """
    msg = str(exc).lower()
    return any(
        token in msg
        for token in (
            "402",
            "credits exhausted",
            "insufficient",
            "unauthorized",
            "401",
            "api key is not configured",
        )
    )


# Some OpenRouter providers behind an uncensored model still apply their own
# safety filter and answer with a short canned refusal instead of an error.
# These markers let the engine treat that as a soft failure and try the next
# model (which usually is not filtered).
_REFUSAL_MARKERS = (
    "i can't respond",
    "i cannot respond",
    "i can't create explicit",
    "i cannot create explicit",
    "i can't help with that",
    "i cannot help with that",
    "i can't assist with that",
    "i cannot assist with that",
    "i'm unable to help",
    "i am unable to help",
    "as an ai language model",
    "i'm sorry, but i can't",
    "i am sorry, but i cannot",
)


def _looks_like_refusal(text: str) -> bool:
    """True when a short reply is a canned content refusal, not an in-character reply."""
    stripped = text.strip()
    if not stripped or len(stripped) > 300:
        return False
    lowered = stripped.lower()
    return any(marker in lowered for marker in _REFUSAL_MARKERS)


class OpenRouterAIEngine(BaseAIEngine):
    """Chat-completions engine delegating to one or more OpenRouter models.

    ``model`` is the primary. ``fallback_models`` are tried, in order, when the
    primary fails with a transient upstream error. OpenRouter's free shared pool
    is frequently rate-limited, and different models route through different
    providers, so a secondary model often still answers.
    """

    def __init__(
        self,
        api_key: str,
        model: str = "sao10k/l3.3-euryale-70b",
        temperature: float = 0.9,
        max_output_tokens: int = 2048,
        base_url: str = _DEFAULT_BASE_URL,
        http_referer: str = "",
        http_title: str = "",
        timeout: float = 90.0,
        fallback_models: list[str] | None = None,
    ) -> None:
        self._api_key = api_key.strip()
        primary = model.strip() or "sao10k/l3.3-euryale-70b"
        self._models: list[str] = [primary]
        for extra in fallback_models or []:
            candidate = extra.strip()
            if candidate and candidate not in self._models:
                self._models.append(candidate)
        self._temperature = temperature
        self._max_output_tokens = max_output_tokens
        self._base_url = base_url.strip().rstrip("/") or _DEFAULT_BASE_URL
        self._http_referer = http_referer
        self._http_title = http_title
        self._timeout = timeout
        self._client: httpx.AsyncClient | None = None

    @property
    def models(self) -> list[str]:
        """Primary model first, then any fallback models."""
        return list(self._models)

    async def start(self) -> None:
        self._client = httpx.AsyncClient(timeout=self._timeout)

    async def close(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None

    @property
    def is_demo(self) -> bool:
        return False

    async def generate_reply(
        self,
        messages: list[Message],
        system_prompt: str | None = None,
    ) -> str:
        if self._client is None:
            raise RuntimeError("OpenRouterAIEngine.start() must be called first")
        if not self._api_key:
            raise AIEngineError("OpenRouter API key is not configured.")

        chat: list[dict[str, str]] = []
        if system_prompt:
            chat.append({"role": "system", "content": system_prompt})
        for message in messages:
            if message.role == MessageRole.system:
                continue
            # OpenRouter is OpenAI-compatible: assistant turns MUST use the role
            # "assistant" (Gemini's "model" role is rejected by its API with a
            # 400/502 — that broke every multi-turn private chat).
            chat.append(
                {
                    "role": "assistant" if message.role == MessageRole.assistant else "user",
                    "content": message.content,
                }
            )

        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        if self._http_referer:
            headers["HTTP-Referer"] = self._http_referer
        if self._http_title:
            headers["X-Title"] = self._http_title

        last_exc: AIEngineError | None = None
        for model in self._models:
            try:
                return await self._generate_with(model, chat, headers)
            except AIEngineError as exc:
                last_exc = exc
                if _is_fatal_error(exc):
                    raise  # auth/credits: every model would fail the same way
                continue  # this provider failed -> try the next model
        assert last_exc is not None
        raise last_exc

    async def _generate_with(
        self,
        model: str,
        chat: list[dict[str, str]],
        headers: dict[str, str],
    ) -> str:
        payload = {
            "model": model,
            "messages": chat,
            "temperature": self._temperature,
            "max_tokens": self._max_output_tokens,
        }
        assert self._client is not None
        response = await self._client.post(
            f"{self._base_url}/chat/completions",
            headers=headers,
            json=payload,
        )
        if response.status_code == 429:
            raise AIEngineError(
                f"OpenRouter model '{model}' is rate-limited (HTTP 429). "
                + _error_detail(response)
            )
        if response.status_code == 402:
            raise AIEngineError(
                "OpenRouter credits exhausted. Please top up your OpenRouter account."
            )
        if response.status_code >= 400:
            raise AIEngineError(
                f"OpenRouter error (HTTP {response.status_code}): " + _error_detail(response)
            )

        data = response.json()
        try:
            text = data["choices"][0]["message"]["content"].strip()
        except (KeyError, IndexError, TypeError) as exc:
            raise AIEngineError(f"Unexpected OpenRouter response: {data}") from exc
        if not text:
            raise AIEngineError("OpenRouter returned an empty reply")
        if _looks_like_refusal(text):
            raise AIEngineError(
                f"OpenRouter model '{model}' refused the request: {text[:120]}"
            )
        return text