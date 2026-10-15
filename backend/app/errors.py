"""Shared error types and API error helpers."""
from __future__ import annotations

from typing import Any

from fastapi import HTTPException, status
from starlette import status as starlette_status


def api_error(status_code: int, code: str, message: str, **extra: Any) -> HTTPException:
    """Build a consistent ``{"detail": {"code", "message", ...}}`` error."""
    detail: dict[str, Any] = {"code": code, "message": message}
    detail.update(extra)
    return HTTPException(status_code=status_code, detail=detail)


def bad_request(code: str, message: str, **extra: Any) -> HTTPException:
    return api_error(status.HTTP_400_BAD_REQUEST, code, message, **extra)


def unauthorized(code: str = "UNAUTHORIZED", message: str = "Authentication required", **extra: Any) -> HTTPException:
    return api_error(status.HTTP_401_UNAUTHORIZED, code, message, **extra)


def not_found(code: str = "NOT_FOUND", message: str = "Resource not found", **extra: Any) -> HTTPException:
    return api_error(status.HTTP_404_NOT_FOUND, code, message, **extra)


def conflict(code: str, message: str, **extra: Any) -> HTTPException:
    return api_error(status.HTTP_409_CONFLICT, code, message, **extra)


def service_unavailable(code: str, message: str, **extra: Any) -> HTTPException:
    return api_error(starlette_status.HTTP_502_BAD_GATEWAY, code, message, **extra)


class StoreError(Exception):
    """Base class for storage layer failures."""


class DuplicateEmailError(StoreError):
    """Raised when creating a user whose email is already registered."""