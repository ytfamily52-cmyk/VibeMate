"""Tests for the /api/v1/chat/sessions endpoints (demo AI mode)."""

from fastapi.testclient import TestClient

API = "/api/v1"


def test_create_session_requires_auth(client: TestClient) -> None:
    assert client.post(f"{API}/chat/sessions/", json={"title": "Hi"}).status_code == 401


def test_create_session_with_private_mode(client: TestClient, auth_headers: dict[str, str]) -> None:
    response = client.post(
        f"{API}/chat/sessions/",
        json={"title": "Private chat", "persona": "boyfriend", "private_mode": True},
        headers=auth_headers,
    )
    assert response.status_code == 201, response.text
    session_id = response.json()["id"]
    detail = client.get(f"{API}/chat/sessions/{session_id}", headers=auth_headers).json()
    assert detail["private_mode"] is True
    assert detail["persona"] == "boyfriend"


def test_private_mode_prompt_applies_to_every_persona() -> None:
    from app.api.routers.chat import _persona_system_prompt

    # Every persona gets the private (18+) rule — each has its own private room.
    for persona in ("girlfriend", "boyfriend", "crush", "gay_boy", "gay_girl"):
        assert "PRIVATE MODE" in (
            _persona_system_prompt(persona, private=True) or ""
        ), persona
    # ... the legacy "gay" id included: it is folded onto gay_boy.
    assert "PRIVATE MODE" in (_persona_system_prompt("gay", private=True) or "")
    # The private rule is a full adult-role-play OVERRIDE: it explicitly permits
    # explicit sexual content and supersedes the "fade to black" persona clauses.
    gf_private = _persona_system_prompt("girlfriend", private=True) or ""
    assert "OVERRIDE" in gf_private
    assert "18+" in gf_private
    assert "explicit erotic role-play" in gf_private
    assert "consenting adults" in gf_private
    # It also locks the couple's gender, so an explicit scene never swaps bodies.
    assert "same-sex couple, both male" in (_persona_system_prompt("gay_boy", private=True) or "")
    assert "same-sex couple, both female" in (
        _persona_system_prompt("gay_girl", private=True) or ""
    )
    # Normal (non-private) sessions never get the private rule.
    assert "PRIVATE MODE" not in (_persona_system_prompt("girlfriend", private=False) or "")
    assert "PRIVATE MODE" not in (_persona_system_prompt("crush", private=False) or "")
    assert "PRIVATE MODE" not in (_persona_system_prompt("gay", private=False) or "")
    assert "PRIVATE MODE" not in (_persona_system_prompt("gay_girl", private=False) or "")
    # Unknown persona -> no prompt at all.
    assert _persona_system_prompt("", private=True) is None


def test_gay_boy_persona_prompt_is_same_sex_men_and_not_explicit_by_default() -> None:
    from app.api.routers.chat import _persona_system_prompt

    prompt = _persona_system_prompt("gay_boy", private=False) or ""
    assert prompt, "gay_boy persona must have its own prompt"
    # The mate is a MAN and the user is a MAN: the model must never fall back to
    # the straight girlfriend script.
    assert "BOTH men" in prompt
    assert "boy-gay" in prompt
    assert "boyfriend" in prompt
    assert "never call the user a girl" in prompt
    # Normal-mode gay chat is still the tasteful persona (private rule adds 18+).
    assert "fade to black" in prompt
    assert "PRIVATE MODE" not in prompt


def test_gay_girl_persona_prompt_is_same_sex_women_and_not_explicit_by_default() -> None:
    from app.api.routers.chat import _persona_system_prompt

    prompt = _persona_system_prompt("gay_girl", private=False) or ""
    assert prompt, "gay_girl persona must have its own prompt"
    # Mirror image of boy gay: BOTH are women (a lesbian couple), so the model
    # must never fall back to the straight boyfriend script.
    assert "BOTH women" in prompt
    assert "lesbian" in prompt
    assert "girlfriend" in prompt
    assert "never call the user a man" in prompt
    assert "fade to black" in prompt
    assert "PRIVATE MODE" not in prompt
    # The two Gay rows are different personas, never one shared prompt.
    assert prompt != (_persona_system_prompt("gay_boy", private=False) or "")


def test_legacy_gay_id_still_means_boy_gay() -> None:
    from app.api.routers.chat import _persona_system_prompt
    from app.domain import GAY_BOY_PERSONA, normalise_persona

    assert normalise_persona("gay") == GAY_BOY_PERSONA
    assert normalise_persona(" gay ") == GAY_BOY_PERSONA
    assert normalise_persona(GAY_BOY_PERSONA) == GAY_BOY_PERSONA
    assert normalise_persona("gay_girl") == "gay_girl"
    assert normalise_persona("") == ""
    # An old "gay" session behaves EXACTLY like boy gay — prompt and 18+ rule.
    assert _persona_system_prompt("gay") == _persona_system_prompt(GAY_BOY_PERSONA)
    assert _persona_system_prompt("gay", private=True) == _persona_system_prompt(
        GAY_BOY_PERSONA, private=True
    )


def test_create_session_accepts_every_persona_id_and_rejects_unknown_ones(
    client: TestClient, auth_headers: dict[str, str]
) -> None:
    # gay_boy / gay_girl are the two rows of the app's "Gay" relation; "gay" is
    # the legacy id (old sessions) that must keep working as boy gay.
    for persona in ("gay_boy", "gay_girl", "gay"):
        created = client.post(
            f"{API}/chat/sessions/",
            json={"title": "Gay chat", "persona": persona, "private_mode": False},
            headers=auth_headers,
        )
        assert created.status_code == 201, created.text
        detail = client.get(
            f"{API}/chat/sessions/{created.json()['id']}", headers=auth_headers
        ).json()
        assert detail["persona"] == persona

    # The persona pattern stays closed: anything unknown is a 422, not a silently
    # accepted session with a persona the engine cannot prompt.
    rejected = client.post(
        f"{API}/chat/sessions/",
        json={"title": "Nope", "persona": "partner"},
        headers=auth_headers,
    )
    assert rejected.status_code == 422


def _create_session(client: TestClient, headers: dict[str, str], title: str = "") -> str:
    response = client.post(f"{API}/chat/sessions/", json={"title": title}, headers=headers)
    assert response.status_code == 201, response.text
    return response.json()["id"]


def test_create_and_get_session(client: TestClient, auth_headers: dict[str, str]) -> None:
    session_id = _create_session(client, auth_headers, title="First chat")
    response = client.get(f"{API}/chat/sessions/{session_id}", headers=auth_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["title"] == "First chat"
    assert body["owner_id"]
    assert body["messages"] == []


def test_send_message_returns_demo_reply_and_persists(client: TestClient, auth_headers: dict[str, str]) -> None:
    session_id = _create_session(client, auth_headers)
    response = client.post(
        f"{API}/chat/sessions/{session_id}/messages",
        json={"content": "Tell me something fun"},
        headers=auth_headers,
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["session_id"] == session_id
    assert body["user_message"]["role"] == "user"
    assert body["user_message"]["content"] == "Tell me something fun"
    assert body["assistant_message"]["role"] == "assistant"
    assert body["assistant_message"]["content"].startswith("[demo]")

    # Both messages are persisted on the session.
    detail = client.get(f"{API}/chat/sessions/{session_id}", headers=auth_headers).json()
    assert [m["role"] for m in detail["messages"]] == ["user", "assistant"]
    # First user message becomes the session title.
    assert detail["title"] == "Tell me something fun"


def test_send_message_requires_valid_session(client: TestClient, auth_headers: dict[str, str]) -> None:
    response = client.post(
        f"{API}/chat/sessions/does-not-exist/messages",
        json={"content": "hi"},
        headers=auth_headers,
    )
    assert response.status_code == 404
    assert response.json()["detail"]["code"] == "SESSION_NOT_FOUND"


def test_users_cannot_see_each_others_sessions(client: TestClient, auth_headers: dict[str, str]) -> None:
    from tests.conftest import register_user

    first_session = _create_session(client, auth_headers, title="Private")
    other_headers = register_user(client, "other@example.com", name="Other")

    # Other user cannot read or delete the first user's session.
    assert client.get(f"{API}/chat/sessions/{first_session}", headers=other_headers).status_code == 404
    assert client.post(
        f"{API}/chat/sessions/{first_session}/messages", json={"content": "hi"}, headers=other_headers
    ).status_code == 404
    assert client.delete(f"{API}/chat/sessions/{first_session}", headers=other_headers).status_code == 404

    # And the list only contains the caller's own sessions.
    _create_session(client, other_headers, title="Other chat")
    first_list = client.get(f"{API}/chat/sessions/", headers=auth_headers).json()
    other_list = client.get(f"{API}/chat/sessions/", headers=other_headers).json()
    assert [s["title"] for s in first_list] == ["Private"]
    assert [s["title"] for s in other_list] == ["Other chat"]


def test_delete_session(client: TestClient, auth_headers: dict[str, str]) -> None:
    session_id = _create_session(client, auth_headers)
    response = client.delete(f"{API}/chat/sessions/{session_id}", headers=auth_headers)
    assert response.status_code == 204
    assert client.get(f"{API}/chat/sessions/{session_id}", headers=auth_headers).status_code == 404


def test_blank_message_rejected(client: TestClient, auth_headers: dict[str, str]) -> None:
    session_id = _create_session(client, auth_headers)
    response = client.post(
        f"{API}/chat/sessions/{session_id}/messages", json={"content": "   "}, headers=auth_headers
    )
    assert response.status_code == 422