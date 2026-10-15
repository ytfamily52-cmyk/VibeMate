"""Call routes: voice call initiation with AI companion."""
from __future__ import annotations

from fastapi import APIRouter

from app.api.deps import CurrentUser, StoreDep
from app.domain import new_id, utc_now
from app.schemas import CallResponse, StartCallRequest

router = APIRouter(prefix="/call", tags=["call"])


@router.post("/start", response_model=CallResponse, status_code=201)
async def start_call(
    payload: StartCallRequest,
    user: CurrentUser,
    store: StoreDep,
) -> CallResponse:
    """Initiate a voice call with the VibeMate AI companion.

    Creates a call record for history. Real voice streaming would sit behind a
    separate WebSocket/SIP service in production; this simulates the handshake
    so the app can show a fully working call screen end-to-end.
    """
    call_id = new_id()[:16]
    session_id = payload.session_id or ""

    # If a session_id is provided, verify the caller owns it.
    if session_id:
        session = await store.get_session(session_id)
        if session is None or session.owner_id != user.id:
            session_id = ""

    persona = payload.persona or "VibeMate"
    now = utc_now()

    # Persist a call record for history (non-critical: ignore failures).
    call_record = {
        "id": call_id,
        "user_id": user.id,
        "session_id": session_id,
        "persona": persona,
        "status": "connected",
        "started_at": now.isoformat(),
        "ended_at": None,
    }
    try:
        await store.create_call(call_record)
    except Exception:
        pass

    return CallResponse(
        call_id=call_id,
        session_id=session_id or "new-call",
        status="connected",
        persona=persona,
        message="📞 Connected to " + persona + "! AI voice call is now active (demo — no real voice streaming).",
    )


@router.post("/end", response_model=CallResponse, status_code=200)
async def end_call(
    user: CurrentUser,
    store: StoreDep,
) -> CallResponse:
    """End the caller's most recent active call."""
    try:
        calls = await store.list_calls(user.id)
        for call in calls:
            if call.get("status") == "connected":
                call["status"] = "ended"
                call["ended_at"] = utc_now().isoformat()
                await store.update_call(call)
                break
    except Exception:
        pass

    return CallResponse(
        call_id="",
        session_id="",
        status="ended",
        persona="",
        message="Call ended.",
    )


@router.get("/history", response_model=list[dict])
async def call_history(
    user: CurrentUser,
    store: StoreDep,
    limit: int = 50,
) -> list[dict]:
    """Return the authenticated user's call history (most recent first)."""
    calls = await store.list_calls(user.id)
    return calls[:limit]