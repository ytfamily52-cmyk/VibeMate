"""Pytest fixtures: a clean in-memory app instance per test."""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

API = "/api/v1"


@pytest.fixture()
def client() -> TestClient:
    """App with memory storage + deterministic demo AI + fixed JWT secret."""
    app = create_app(
        Settings(
            store="memory",
            gemini_api_key="",
            jwt_secret="test-secret-0123456789abcdef0123456789abcdef",
            chat_history_window=15,
        )
    )
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture()
def auth_headers(client: TestClient) -> dict[str, str]:
    """Register a fresh user and return Bearer auth headers."""
    response = client.post(
        f"{API}/auth/register",
        json={
            "email": "tester@example.com",
            "password": "password123",
            "display_name": "Tester",
        },
    )
    assert response.status_code == 201, response.text
    token = response.json()["access_token"]
    return {"Authorization": f"Bearer {token}"}


def register_user(client: TestClient, email: str, password: str = "password123", name: str = "") -> dict[str, str]:
    """Register a user and return their auth headers (test helper)."""
    response = client.post(
        f"{API}/auth/register",
        json={"email": email, "password": password, "display_name": name},
    )
    assert response.status_code == 201, response.text
    return {"Authorization": f"Bearer {response.json()['access_token']}"}