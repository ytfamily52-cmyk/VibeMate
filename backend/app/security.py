"""Password hashing (scrypt) and JWT access-token helpers.

- Passwords are hashed with ``scrypt`` from the ``cryptography`` package.
- Access tokens are JSON Web Tokens signed with ``PyJWT`` (HS256 by default).
"""
from __future__ import annotations

import base64
import os
import time

import jwt
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

from app.errors import api_error, unauthorized

_SCRYPT_N = 2**14
_SCRYPT_R = 8
_SCRYPT_P = 1
_SALT_BYTES = 16
_DERIVED_BYTES = 32
_PREFIX = "scrypt"
_B64 = base64.urlsafe_b64encode


def hash_password(password: str) -> str:
    """Hash a plaintext password with salted scrypt (as ``scrypt$<salt>$<key>``)."""
    salt = os.urandom(_SALT_BYTES)
    kdf = Scrypt(salt=salt, length=_DERIVED_BYTES, n=_SCRYPT_N, r=_SCRYPT_R, p=_SCRYPT_P)
    derived = kdf.derive(password.encode("utf-8"))
    parts = (_PREFIX, _B64(salt).decode("ascii"), _B64(derived).decode("ascii"))
    return "$".join(parts)


def verify_password(password: str, encoded: str) -> bool:
    """Verify a plaintext password against a stored ``hash_password`` value."""
    try:
        prefix, salt_b64, derived_b64 = encoded.split("$")
        if prefix != _PREFIX:
            return False
        salt = base64.urlsafe_b64decode(salt_b64.encode("ascii"))
        expected = base64.urlsafe_b64decode(derived_b64.encode("ascii"))
        kdf = Scrypt(salt=salt, length=len(expected), n=_SCRYPT_N, r=_SCRYPT_R, p=_SCRYPT_P)
        kdf.verify(password.encode("utf-8"), expected)
        return True
    except Exception:
        # Wrong format, bad salt, or derivation mismatch -> invalid credentials.
        return False


def create_access_token(
    subject: str,
    secret: str,
    algorithm: str = "HS256",
    expires_minutes: int = 60 * 24 * 7,
) -> str:
    """Create a signed JWT access token for ``subject`` (a user id)."""
    now = int(time.time())
    payload = {
        "sub": subject,
        "iat": now,
        "exp": now + expires_minutes * 60,
    }
    return jwt.encode(payload, secret, algorithm=algorithm)


def decode_access_token(token: str, secret: str, algorithm: str = "HS256") -> str:
    """Decode + verify a JWT and return its subject (user id).

    Raises a ``401`` HTTPException (via FastAPI's exception machinery) when the
    token is missing, expired, or tampered with.
    """
    try:
        payload = jwt.decode(token, secret, algorithms=[algorithm])
    except jwt.ExpiredSignatureError:
        raise unauthorized("TOKEN_EXPIRED", "Access token has expired.") from None
    except jwt.InvalidTokenError:
        raise unauthorized("INVALID_TOKEN", "Access token is invalid.") from None

    subject = payload.get("sub")
    if not subject:
        raise unauthorized("INVALID_TOKEN", "Access token is missing a subject.")
    return str(subject)