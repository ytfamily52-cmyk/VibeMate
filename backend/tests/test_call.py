"""Tests for the /api/v1/call endpoints (demo AI mode)."""

from fastapi.testclient import TestClient

API = "/api/v1"


def test_start_call_requires_auth(client: TestClient) -> None:
    response = client.post(f"{API}/call/start", json={"persona": "girlfriend"})
    assert response.status_code == 401


def test_start_and_end_call(client: TestClient, auth_headers: dict[str, str]) -> None:
    response = client.post(
        f"{API}/call/start",
        json={"persona": "girlfriend"},
        headers=auth_headers,
    )
    assert response.status_code == 201, response.text
    body = response.json()
    assert body["status"] == "connected"
    assert body["call_id"]
    assert body["persona"] == "girlfriend"

    end = client.post(f"{API}/call/end", headers=auth_headers)
    assert end.status_code == 200, end.text
    assert end.json()["status"] == "ended"

    history = client.get(f"{API}/call/history", headers=auth_headers)
    assert history.status_code == 200
    calls = history.json()
    assert len(calls) >= 1
    assert calls[0]["status"] == "ended"


def test_call_history_requires_auth(client: TestClient) -> None:
    assert client.get(f"{API}/call/history").status_code == 401