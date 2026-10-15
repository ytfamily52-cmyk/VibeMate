"""Unit tests for security helpers and the demo AI engine."""

import asyncio

import pytest
from fastapi import HTTPException

from app.ai.demo import DemoAIEngine
from app.domain import Message, MessageRole, utc_now
from app.security import create_access_token, decode_access_token, hash_password, verify_password


def test_password_hash_roundtrip() -> None:
    encoded = hash_password("correct horse battery staple")
    assert encoded.startswith("scrypt$")
    assert verify_password("correct horse battery staple", encoded) is True
    assert verify_password("wrong password", encoded) is False


def test_password_hash_is_salted() -> None:
    first = hash_password("same password")
    second = hash_password("same password")
    assert first != second
    assert verify_password("same password", first) is True
    assert verify_password("same password", second) is True


def test_jwt_roundtrip() -> None:
    secret = "0" * 64
    token = create_access_token("user-123", secret, expires_minutes=5)
    assert decode_access_token(token, secret) == "user-123"


def test_jwt_bad_secret_rejected() -> None:
    token = create_access_token("user-123", "0" * 64)
    with pytest.raises(HTTPException) as excinfo:
        decode_access_token(token, "f" * 64)
    assert excinfo.value.status_code == 401


def test_demo_engine_is_deterministic_and_offline() -> None:
    history = [
        Message(id="1", role=MessageRole.user, content="hello there", created_at=utc_now()),
        Message(id="2", role=MessageRole.assistant, content="hi", created_at=utc_now()),
        Message(id="3", role=MessageRole.user, content="tell me a joke", created_at=utc_now()),
    ]
    engine = DemoAIEngine()
    first = asyncio.run(engine.generate_reply(history))
    second = asyncio.run(engine.generate_reply(history))
    assert first == second
    assert first.startswith("[demo]")
    assert "tell me a joke" in first