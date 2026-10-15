"""Tests for the /api/v1/auth endpoints."""

from typing import Any

import pytest
from fastapi.testclient import TestClient

API = "/api/v1"


def test_register_returns_token_and_user(client: TestClient) -> None:
    response = client.post(
        f"{API}/auth/register",
        json={"email": "alice@example.com", "password": "super-secret", "display_name": "Alice"},
    )
    assert response.status_code == 201
    body = response.json()
    assert body["token_type"] == "bearer"
    assert body["access_token"]
    assert body["expires_in"] > 0
    user = body["user"]
    assert user["email"] == "alice@example.com"
    assert user["display_name"] == "Alice"
    assert "password" not in user


def test_register_normalises_email(client: TestClient) -> None:
    response = client.post(
        f"{API}/auth/register",
        json={"email": "  Bob@Example.COM ", "password": "super-secret"},
    )
    assert response.status_code == 201
    assert response.json()["user"]["email"] == "bob@example.com"
    assert response.json()["user"]["display_name"] == "bob"  # derived from email


def test_register_rejects_duplicate_email(client: TestClient) -> None:
    payload = {"email": "alice@example.com", "password": "super-secret"}
    assert client.post(f"{API}/auth/register", json=payload).status_code == 201
    response = client.post(f"{API}/auth/register", json=payload)
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "EMAIL_ALREADY_REGISTERED"


def test_register_rejects_invalid_email(client: TestClient) -> None:
    response = client.post(
        f"{API}/auth/register",
        json={"email": "not-an-email", "password": "super-secret"},
    )
    assert response.status_code == 422


def test_register_rejects_short_password(client: TestClient) -> None:
    response = client.post(
        f"{API}/auth/register",
        json={"email": "alice@example.com", "password": "short"},
    )
    assert response.status_code == 422


def test_login_ok(client: TestClient) -> None:
    client.post(
        f"{API}/auth/register",
        json={"email": "alice@example.com", "password": "super-secret"},
    )
    response = client.post(
        f"{API}/auth/login",
        json={"email": "alice@example.com", "password": "super-secret"},
    )
    assert response.status_code == 200
    assert response.json()["access_token"]
    assert response.json()["user"]["email"] == "alice@example.com"


def test_login_wrong_password_rejected(client: TestClient) -> None:
    client.post(
        f"{API}/auth/register",
        json={"email": "alice@example.com", "password": "super-secret"},
    )
    response = client.post(
        f"{API}/auth/login",
        json={"email": "alice@example.com", "password": "wrong-password"},
    )
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "INVALID_CREDENTIALS"


def test_me_requires_token(client: TestClient) -> None:
    assert client.get(f"{API}/auth/me").status_code == 401
    assert client.get(f"{API}/auth/me", headers={"Authorization": "Bearer not-a-jwt"}).status_code == 401


def test_me_returns_profile(client: TestClient, auth_headers: dict[str, str]) -> None:
    response = client.get(f"{API}/auth/me", headers=auth_headers)
    assert response.status_code == 200
    assert response.json()["email"] == "tester@example.com"


# --- Firebase bridge -------------------------------------------------------


def _fake_verify(claims: dict[str, Any]):
    async def fake_verify(token: str, project_id: str) -> dict[str, Any]:
        assert token == "fake-firebase-id-token"
        return claims
    return fake_verify


def test_firebase_login_mints_token(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "app.api.routers.auth.verify_firebase_id_token",
        _fake_verify({"sub": "firebase-uid-123", "email": "fire@example.com", "name": "Fire"}),
    )
    response = client.post(
        f"{API}/auth/firebase",
        json={"id_token": "fake-firebase-id-token", "display_name": "Fire"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["token_type"] == "bearer"
    assert body["access_token"]
    assert body["expires_in"] > 0
    assert body["user"]["id"] == "firebase-uid-123"
    assert body["user"]["email"] == "fire@example.com"
    assert body["user"]["display_name"] == "Fire"


def test_firebase_login_anonymous(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "app.api.routers.auth.verify_firebase_id_token",
        _fake_verify({"sub": "anon-user-1", "exp": 9999999999, "iat": 1, "aud": "x", "iss": "x"}),
    )
    response = client.post(
        f"{API}/auth/firebase",
        json={"id_token": "fake-firebase-id-token", "display_name": "Guest"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["user"]["display_name"] == "Guest"
    assert body["user"]["email"].endswith("@vibemate.local")


def test_firebase_login_requires_server_setup(client: TestClient) -> None:
    # conftest Settings leaves firebase_project_id empty -> clean 401, no network call.
    response = client.post(
        f"{API}/auth/firebase",
        json={"id_token": "x" * 40, "display_name": ""},
    )
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "FIREBASE_NOT_CONFIGURED"


def test_firebase_login_rejects_invalid_token(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    from fastapi import HTTPException

    async def bad_verify(token: str, project_id: str) -> dict[str, Any]:
        raise HTTPException(status_code=401, detail={"code": "INVALID_FIREBASE_TOKEN", "message": "bad"})

    monkeypatch.setattr("app.api.routers.auth.verify_firebase_id_token", bad_verify)
    response = client.post(
        f"{API}/auth/firebase",
        json={"id_token": "obviously-invalid-token", "display_name": ""},
    )
    assert response.status_code == 401