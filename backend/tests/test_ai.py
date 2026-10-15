"""Tests for the private (18+) AI path: OpenRouter engine + /ai/private route."""

from __future__ import annotations

import httpx
import pytest
from fastapi.testclient import TestClient

from app.ai.openrouter import OpenRouterAIEngine
from app.domain import Message, MessageRole
from app.main import API_PREFIX


class FakePrivateEngine:
    """Replaces the private engine in route tests with a deterministic marker."""

    async def generate_reply(
        self,
        messages: list[Message],
        system_prompt: str | None = None,
    ) -> str:
        last = messages[-1].content if messages else ""
        return f"PRIV_MARKER:{last}"


class FakeVisionEngine:
    """Deterministic vision reader for route tests."""

    def __init__(self, output: str = "VISUAL: red silk dress, playful pose\nAURA: Seductive"):
        self.output = output
        self.calls: list[tuple[str, str]] = []

    async def describe_image(self, image_data_uri: str, prompt: str) -> str:
        self.calls.append((image_data_uri, prompt))
        return self.output


class FakeResponse:
    def __init__(self, status_code: int, json_data: dict):
        self.status_code = status_code
        self._json = json_data

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise httpx.HTTPStatusError(
                "request failed", request=httpx.Request("POST", "http://mock"), response=self
            )

    def json(self) -> dict:
        return self._json


class FakeAsyncClient:
    """Minimal async stand-in for httpx.AsyncClient used by OpenRouterAIEngine."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, dict, dict]] = []

    async def post(self, url: str, headers: dict, json: dict) -> FakeResponse:
        self.sent.append((url, headers, json))
        return FakeResponse(
            200,
            {"choices": [{"message": {"content": "open-router hello"}}]},
        )


async def _verify_engine_call(client: FakeAsyncClient, engine: OpenRouterAIEngine) -> None:
    engine._client = client  # skip start(); inject the fake transport
    messages = [
        Message(id="1", role=MessageRole.user, content="hi", created_at="2026-01-01T00:00:00Z"),
        Message(id="2", role=MessageRole.assistant, content="hello", created_at="2026-01-01T00:00:01Z"),
    ]
    text = await engine.generate_reply(messages, system_prompt="PRIVATE MODE")
    assert text == "open-router hello"

    url, headers, payload = client.sent[-1]
    assert url == "https://openrouter.ai/api/v1/chat/completions"
    assert headers["Authorization"] == "Bearer sk-test-123"
    assert headers["HTTP-Referer"] == "https://vibemate.example.com"
    assert payload["model"] == "sao10k/l3.3-euryale-70b"
    assert payload["messages"][0] == {"role": "system", "content": "PRIVATE MODE"}
    # OpenRouter is OpenAI-compatible: assistant turns use the "assistant" role
    # (NOT Gemini's "model"), otherwise the provider rejects the request.
    assert [m["role"] for m in payload["messages"][1:]] == ["user", "assistant"]
    assert payload["messages"][1]["content"] == "hi"


def test_engine_posts_to_openrouter_with_headers_and_roles() -> None:
    import asyncio

    engine = OpenRouterAIEngine(
        api_key="sk-test-123",
        model="sao10k/l3.3-euryale-70b",
        http_referer="https://vibemate.example.com",
    )
    asyncio.run(_verify_engine_call(FakeAsyncClient(), engine))


def test_engine_raises_on_rate_limit() -> None:
    import asyncio

    from app.ai import AIEngineError

    class LimitedClient(FakeAsyncClient):
        async def post(self, url: str, headers: dict, json: dict) -> FakeResponse:
            return FakeResponse(429, {"error": {"message": "rate limited"}})

    engine = OpenRouterAIEngine(api_key="sk-test-123")
    engine._client = LimitedClient()

    async def run() -> None:
        with pytest.raises(AIEngineError):
            await engine.generate_reply([], system_prompt="PRIVATE MODE")

    asyncio.run(run())


# ---- /api/v1/ai/private route ------------------------------------------------


def test_private_reply_uses_private_engine(client: TestClient, auth_headers: dict[str, str]) -> None:
    client.app.state.private_ai_engine = FakePrivateEngine()
    response = client.post(
        f"{API_PREFIX}/ai/private",
        json={
            "persona": "girlfriend",
            "content": "hey cutie",
            "history": [{"role": "user", "content": "hi"}],
        },
        headers=auth_headers,
    )
    assert response.status_code == 200, response.text
    assert response.json()["reply"] == "PRIV_MARKER:hey cutie"


def test_private_reply_requires_auth(client: TestClient) -> None:
    response = client.post(f"{API_PREFIX}/ai/private", json={"content": "hi"})
    assert response.status_code == 401


def test_private_reply_image_attachment_builds_photo_hint(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    captured: dict[str, str] = {}

    class CapturingEngine(FakePrivateEngine):
        async def generate_reply(
            self,
            messages: list[Message],
            system_prompt: str | None = None,
        ) -> str:
            captured["last"] = messages[-1].content if messages else ""
            return "ok"

    client.app.state.private_ai_engine = CapturingEngine()
    response = client.post(
        f"{API_PREFIX}/ai/private",
        json={
            "persona": "girlfriend",
            "content": "ek gori photo dekho jaan",
            "attachment": {"type": "IMAGE", "file_name": "pic.jpg"},
            "history": [],
        },
        headers=auth_headers,
    )
    assert response.status_code == 200, response.text
    # An IMAGE whose read failed gets the NO-INVENT turn: the persona has no
    # image bytes, so it must react without claiming what the photo shows.
    assert (
        "[The user just sent you a photo (pic.jpg). No description of it is "
        "available" in captured["last"]
    )
    assert "do NOT invent or claim what it shows" in captured["last"]
    assert "ek gori photo dekho jaan" in captured["last"]


def test_private_reply_accepts_photo_without_caption(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    # A photo sent without any caption must still produce a reply (content may
    # be empty when an attachment is present).
    response = client.post(
        f"{API_PREFIX}/ai/private",
        json={
            "persona": "boyfriend",
            "content": "",
            "attachment": {"type": "IMAGE", "file_name": "p.jpg"},
            "history": [],
        },
        headers=auth_headers,
    )
    assert response.status_code == 200, response.text


def test_private_reply_photo_is_visually_read(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    captured: dict[str, str] = {}
    vision = FakeVisionEngine()

    class CapturingEngine(FakePrivateEngine):
        async def generate_reply(
            self,
            messages: list[Message],
            system_prompt: str | None = None,
        ) -> str:
            captured["last"] = messages[-1].content if messages else ""
            return "ohh baby, I saw everything 😉"

    client.app.state.vision_ai_engine = vision
    client.app.state.private_ai_engine = CapturingEngine()
    response = client.post(
        f"{API_PREFIX}/ai/private",
        json={
            "persona": "girlfriend",
            "content": "jaan ye photo dekh",
            "attachment": {"type": "IMAGE", "file_name": "pic.jpg", "data": "AAAA"},
            "history": [],
        },
        headers=auth_headers,
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["reply"] == "ohh baby, I saw everything 😉"
    # The adult model received the VISION description, not the caption hint.
    assert "red silk dress" in captured["last"]
    assert "The user sent this photo with the caption: jaan ye photo dekh" in captured["last"]
    # The vision reader received the photo as a base64 data-URI + the prompt.
    assert vision.calls[0][0].startswith("data:image/jpeg;base64,")
    assert "VISUAL:" in vision.calls[0][1]
    # The app-facing note proves the photo was really read.
    assert "Seductive" in body["vision_note"]


def test_private_reply_vision_failure_falls_back_to_hint(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    captured: dict[str, str] = {}

    class BoomVision(FakeVisionEngine):
        async def describe_image(self, image_data_uri: str, prompt: str) -> str:
            raise RuntimeError("vision down")

    class CapturingEngine(FakePrivateEngine):
        async def generate_reply(
            self,
            messages: list[Message],
            system_prompt: str | None = None,
        ) -> str:
            captured["last"] = messages[-1].content if messages else ""
            return "ok"

    client.app.state.vision_ai_engine = BoomVision()
    client.app.state.private_ai_engine = CapturingEngine()
    response = client.post(
        f"{API_PREFIX}/ai/private",
        json={
            "persona": "boyfriend",
            "content": "dekh",
            "attachment": {"type": "IMAGE", "file_name": "p.jpg", "data": "AAAA"},
            "history": [],
        },
        headers=auth_headers,
    )
    assert response.status_code == 200, response.text
    # Vision failed -> fall back to the NO-INVENT photo turn, no note shown.
    assert response.json()["vision_note"] == ""
    assert (
        "[The user just sent you a photo (p.jpg). No description of it is "
        "available" in captured["last"]
    )


def test_private_reply_system_prompt_override(client: TestClient, auth_headers: dict[str, str]) -> None:
    captured: dict[str, str] = {}

    class CapturingEngine(FakePrivateEngine):
        async def generate_reply(
            self,
            messages: list[Message],
            system_prompt: str | None = None,
        ) -> str:
            captured["prompt"] = system_prompt or ""
            return "ok"

    client.app.state.private_ai_engine = CapturingEngine()
    # PRIVATE_SYSTEM_PROMPT override takes precedence over the persona rule.
    old = client.app.state.settings.private_system_prompt
    client.app.state.settings.private_system_prompt = "OVERRIDE PROMPT"
    try:
        response = client.post(
            f"{API_PREFIX}/ai/private",
            json={"persona": "boyfriend", "content": "hey"},
            headers=auth_headers,
        )
    finally:
        client.app.state.settings.private_system_prompt = old
    assert response.status_code == 200
    assert captured["prompt"] == "OVERRIDE PROMPT"


# ---- private chat-session routing (POST /chat/sessions/{id}/messages) ---------


def test_private_chat_session_routes_to_private_engine(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    client.app.state.private_ai_engine = FakePrivateEngine()
    created = client.post(
        f"{API_PREFIX}/chat/sessions/",
        json={"title": "Private", "persona": "boyfriend", "private_mode": True},
        headers=auth_headers,
    )
    assert created.status_code == 201, created.text
    session_id = created.json()["id"]

    sent = client.post(
        f"{API_PREFIX}/chat/sessions/{session_id}/messages",
        json={"content": "private hello"},
        headers=auth_headers,
    )
    assert sent.status_code == 200, sent.text
    # Private engine marker proves the private_ai (OpenRouter seam) answered,
    # NOT the default demo engine.
    assert sent.json()["assistant_message"]["content"].startswith("PRIV_MARKER")


def test_normal_chat_session_uses_default_engine(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    # Default engine in tests is the DemoAIEngine -> "[demo]" replies.
    created = client.post(
        f"{API_PREFIX}/chat/sessions/",
        json={"title": "Normal", "persona": "girlfriend", "private_mode": False},
        headers=auth_headers,
    )
    session_id = created.json()["id"]
    sent = client.post(
        f"{API_PREFIX}/chat/sessions/{session_id}/messages",
        json={"content": "hello"},
        headers=auth_headers,
    )
    assert sent.status_code == 200
    assert sent.json()["assistant_message"]["content"].startswith("[demo]")


def test_demo_engine_labels_every_persona_including_both_gay_rooms() -> None:
    # Demo mode echoes the persona it detected from the system prompt. "Gay" is
    # ONE relation in the app but TWO personas, and the private gender-lock
    # addendum names BOTH of them, so this pins that a girl-gay room is not
    # mislabelled as boy gay (and vice versa).
    import asyncio
    from datetime import datetime, timezone

    from app.ai.demo import DemoAIEngine
    from app.api.routers.chat import _persona_system_prompt

    async def label(persona: str, private: bool) -> str:
        reply = await DemoAIEngine().generate_reply(
            [
                Message(
                    id="1",
                    role=MessageRole.user,
                    content="hi",
                    created_at=datetime.now(timezone.utc),
                )
            ],
            system_prompt=_persona_system_prompt(persona, private=private),
        )
        return reply.rsplit("(persona: ", 1)[-1].rstrip(")")

    for persona in ("girlfriend", "boyfriend", "crush", "gay_boy", "gay_girl"):
        assert asyncio.run(label(persona, False)) == persona, persona
        assert asyncio.run(label(persona, True)) == persona, persona
    # The legacy "gay" id is boy gay, in normal AND private mode.
    assert asyncio.run(label("gay", False)) == "gay_boy"
    assert asyncio.run(label("gay", True)) == "gay_boy"