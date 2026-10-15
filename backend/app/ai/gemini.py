"""Google Gemini engine backed by the REST ``generateContent`` endpoint.

The official Python SDK is intentionally avoided (matching
``requirements.txt``): this backend talks to
``generativelanguage.googleapis.com`` over HTTPS with ``httpx``.
"""
from __future__ import annotations

import httpx

from app.ai import AIEngineError, BaseAIEngine
from app.domain import Message, MessageRole

_GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


class GeminiAIEngine(BaseAIEngine):
    """Gemini engine with automatic API-key rotation.

    Accepts one or more API keys. Every outgoing request uses the current
    ("active") key; when Gemini answers 429 (rate limit / quota exhausted for
    that key), the engine transparently rotates to the next key and retries —
    so users never see a 429 unless *all* configured keys are exhausted.
    """

    def __init__(
        self,
        api_key: str,
        model: str = "gemini-3.6-flash",
        temperature: float = 0.9,
        max_output_tokens: int = 1024,
        timeout: float = 60.0,
    ) -> None:
        self._api_keys: list[str] = [k.strip() for k in api_key.split(",") if k.strip()]
        self._active_index = 0
        self._model = model
        self._temperature = temperature
        self._max_output_tokens = max_output_tokens
        self._timeout = timeout
        self._client: httpx.AsyncClient | None = None

    @property
    def _api_key(self) -> str:
        return self._api_keys[self._active_index]

    def _scrub(self, text: str) -> str:
        """Remove every configured key from an error string (never leak keys)."""
        for key in self._api_keys:
            text = text.replace(key, "***")
        return text

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
            raise RuntimeError("GeminiAIEngine.start() must be called first")

        contents = [
            {
                "role": "model" if message.role == MessageRole.assistant else "user",
                "parts": [{"text": message.content}],
            }
            for message in messages
            if message.role != MessageRole.system
        ]
        payload = {
            "contents": contents,
            "generationConfig": {
                "temperature": self._temperature,
                "maxOutputTokens": self._max_output_tokens,
            },
        }
        # Persona support: Gemini's systemInstruction shapes the assistant's
        # character (girlfriend / boyfriend / crush) for the whole conversation.
        if system_prompt:
            payload["systemInstruction"] = {"parts": [{"text": system_prompt}]}
        url = _GEMINI_ENDPOINT.format(model=self._model)
        # Key-rotation loop: try each configured key once per call. On 429 the
        # key's quota is exhausted, so rotate to the next one and retry; any
        # other HTTP error aborts with a scrubbed AIEngineError.
        total_keys = len(self._api_keys)
        for _ in range(total_keys):
            try:
                response = await self._client.post(
                    url, params={"key": self._api_key}, json=payload
                )
                if response.status_code == 429:
                    raise httpx.HTTPStatusError(
                        "429 Too Many Requests",
                        request=response.request,
                        response=response,
                    )
                response.raise_for_status()
            except httpx.HTTPStatusError as exc:
                if exc.response is not None and exc.response.status_code == 429:
                    if self._active_index < total_keys - 1:
                        self._active_index += 1
                        last_error = AIEngineError(
                            self._scrub(f"Gemini request failed: {exc}")
                        )
                        continue
                    # All keys exhausted — surface the rate limit.
                    raise AIEngineError(
                        "Gemini rate limit reached on all configured API keys. "
                        "Please try again in a minute."
                    ) from exc
                raise AIEngineError(self._scrub(f"Gemini request failed: {exc}")) from exc
            except httpx.HTTPError as exc:
                raise AIEngineError(self._scrub(f"Gemini request failed: {exc}")) from exc
            break

        data = response.json()
        try:
            text = data["candidates"][0]["content"]["parts"][0]["text"].strip()
        except (KeyError, IndexError, TypeError) as exc:
            raise AIEngineError(f"Unexpected Gemini response: {data}") from exc
        if not text:
            raise AIEngineError("Gemini returned an empty reply")
        return text