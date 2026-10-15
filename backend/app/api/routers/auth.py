"""Auth routes: register, login, and current-user info."""
from __future__ import annotations

from fastapi import APIRouter

from app.api.deps import CurrentUser, SettingsDep, StoreDep
from app.domain import User, new_id, utc_now
from app.errors import conflict, unauthorized
from app.firebase_auth import verify_firebase_id_token
from app.schemas import (
    FirebaseLoginRequest,
    LoginRequest,
    RegisterRequest,
    TokenResponse,
    UserOut,
)
from app.security import create_access_token, hash_password, verify_password
from app.storage.base import BaseStore
from app.errors import DuplicateEmailError

router = APIRouter(prefix="/auth", tags=["auth"])


def _token_response(user: User, settings) -> TokenResponse:
    token = create_access_token(
        subject=user.id,
        secret=settings.jwt_secret,
        algorithm=settings.jwt_algorithm,
        expires_minutes=settings.jwt_access_token_expire_minutes,
    )
    return TokenResponse(
        access_token=token,
        expires_in=settings.jwt_access_token_expire_minutes * 60,
        user=UserOut.model_validate(user),
    )


@router.post("/register", response_model=TokenResponse, status_code=201)
async def register(payload: RegisterRequest, store: StoreDep, settings: SettingsDep) -> TokenResponse:
    """Create an account and return a JWT access token for immediate use."""
    user = User(
        id=new_id(),
        email=payload.email,
        password_hash=hash_password(payload.password),
        display_name=payload.display_name.strip() or payload.email.split("@")[0],
        created_at=utc_now(),
    )
    try:
        await store.create_user(user)
    except DuplicateEmailError:
        raise conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists.")
    return _token_response(user, settings)


@router.post("/login", response_model=TokenResponse)
async def login(payload: LoginRequest, store: StoreDep, settings: SettingsDep) -> TokenResponse:
    """Exchange valid credentials for a JWT access token."""
    user = await store.get_user_by_email(payload.email)
    if user is None or not verify_password(payload.password, user.password_hash):
        raise unauthorized("INVALID_CREDENTIALS", "Email or password is incorrect.")
    return _token_response(user, settings)


@router.post("/firebase", response_model=TokenResponse)
async def firebase_login(
    payload: FirebaseLoginRequest, store: StoreDep, settings: SettingsDep
) -> TokenResponse:
    """Verify a Firebase ID token (email / anonymous / Google) and mint a VibeMate JWT.

    Users are keyed by their Firebase uid, so the same person keeps their chat
    history across login methods. A legacy password account with the same email
    is adopted when present.
    """
    claims = await verify_firebase_id_token(payload.id_token, settings.firebase_project_id)
    uid = str(claims["sub"])
    email = str(claims.get("email") or "").strip().lower()
    fallback_name = str(claims.get("name") or "").strip()
    display_name = (
        payload.display_name
        or fallback_name
        or (email.split("@")[0] if email else "Guest")
    )

    user = await store.get_user_by_id(uid)
    if user is None and email:
        user = await store.get_user_by_email(email)  # adopt legacy account
    if user is None:
        user = User(
            id=uid,
            email=email or f"anon-{uid[:10]}@vibemate.local",
            password_hash="",  # identity is managed by Firebase
            display_name=display_name,
            created_at=utc_now(),
        )
        try:
            await store.create_user(user)
        except DuplicateEmailError:
            existing = await store.get_user_by_email(user.email)
            if existing is None:
                raise conflict(
                    "EMAIL_ALREADY_REGISTERED",
                    "An account with this email already exists.",
                )
            user = existing  # already keyed to another Firebase uid; adopt it
    return _token_response(user, settings)


@router.get("/me", response_model=UserOut)
async def me(user: CurrentUser) -> UserOut:
    """Return the profile of the authenticated user."""
    return UserOut.model_validate(user)