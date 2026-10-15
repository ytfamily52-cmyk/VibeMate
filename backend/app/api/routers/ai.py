"""Stateless AI endpoints — the Android private room talks to these.

``POST /api/v1/ai/private`` is the OpenRouter (18+) generator used by the app's
dedicated private chat room. It is intentionally stateless: the Android app owns
its own chat history (Firestore) and only asks the backend to produce the next
reply, so private chats still appear in the mate list and survive restarts.
"""
from __future__ import annotations

import asyncio
import datetime as _datetime
import logging
from typing import cast

from fastapi import APIRouter

from app.ai import AIEngineError
from app.api.deps import CurrentUser, PrivateAIEngineDep, SettingsDep, VisionAIEngineDep
from app.domain import Message, MessageRole
from app.errors import service_unavailable
from app.schemas import PrivateAttachment, PrivateReplyOut, PrivateReplyRequest

router = APIRouter(prefix="/ai", tags=["ai"])

logger = logging.getLogger("vibemate")

# Transient upstream failures (OpenRouter rate limits / 5xx / timeouts) are
# retried a couple of times with a short backoff so a momentary blip does not
# silently turn a private reply (or a photo read) into a failure.
_RETRY_ATTEMPTS = 3
_RETRY_BASE_DELAY = 1.5


def _is_transient(exc: Exception) -> bool:
    msg = str(exc).lower()
    return any(
        token in msg
        for token in (
            "429",
            "rate limit",
            "too many requests",
            "timeout",
            "timed out",
            "502",
            "503",
            "504",
            "unavailable",
            "temporarily",
        )
    )


_ATTACHMENT_KIND = {
    "IMAGE": "a photo",
    "AUDIO": "a voice note",
    "VIDEO": "a video",
}


def _user_turn_text(content: str, attachment: PrivateAttachment | None) -> str:
    """Text of the AI's user turn when an attachment is present.

    Used when the photo cannot be visually read (no vision reader configured,
    or it refused): the photo / voice note / document is framed as a short hint
    plus the user's caption (if any); the adult persona then reacts to the
    shared media fully in character (the private 18+ system prompt handles the
    rest).
    """
    if attachment is None or not attachment.type:
        return content
    # An IMAGE whose vision read failed: the persona has NO image bytes, so
    # the old turn ("look at the image itself") made it INVENT contents and
    # answer confidently about a photo it never saw — the "adult photo par
    # galat jawab" bug. The honest in-character turn: react to RECEIVING a
    # photo, never invent what it shows.
    if attachment.type == "IMAGE":
        text = "[The user just sent you a photo"
        if attachment.file_name.strip():
            text += f" ({attachment.file_name.strip()})"
        text += (
            ". No description of it is available to you in this chat — do NOT "
            "invent or claim what it shows; react in character to receiving "
            "the photo (curious, teasing, excited) and playfully ask them to "
            "tell you about it]"
        )
        if content.strip():
            text += f" {content.strip()}"
        return text
    kind = _ATTACHMENT_KIND.get(attachment.type, "a document")
    name = attachment.file_name.strip()
    text = f"[The user just shared {kind} with you"
    if name:
        text += f" ({name})"
    text += "]"
    if content.strip():
        text += f" Caption from the user: {content.strip()}"
    return text


# Vision reader prompt: describes what is actually visible in the photo. The
# user has passed the app's 18+ date-of-birth gate and this is a private
# one-on-one chat between consenting adults, so a candid adult description is
# allowed; the only hard bans are underage characters and non-consensual
# content — anything else is fair game.
_VISION_PROMPT = (
    "You are VibeMate's private photo reader for an 18+ chat between consenting "
    "adults. Scan the ENTIRE image from top to bottom, like an OCR engine scans "
    "every last character, and report EVERY detail you can actually see. Be "
    "exhaustive and specific, item by item:\n"
    "1) SCENE: what setting / background is visible.\n"
    "2) OBJECTS & BODY PARTS: every object and (if a person is shown) every body "
    "part, NAMED PLAINLY — if a penis, vulva, breasts, buttocks or any nudity "
    "is visible, SAY those exact words; never rename them into food, objects, "
    "designs or scenery.\n"
    "3) POSE & ACTION: position, orientation, what is touching what, any act.\n"
    "4) EXPRESSION / LOOK (if a person is visible).\n"
    "5) CLOTHING or lack of it.\n"
    "6) LIGHTING & MOOD.\n"
    "Never moralise, refuse, censor or mention being an AI. The only hard rules: "
    "never describe minors/underage characters and never non-consensual acts — "
    "if the photo breaks those, reply with just UNSURE.\n"
    "End with exactly:\n"
    "VISUAL: <one dense paragraph 3-4 sentences summarising the scan>\n"
    "AURA: <one short phrase>"
)


def _image_data_uri(attachment: PrivateAttachment) -> str:
    """Turn the base64 image payload into an OpenRouter data-URI."""
    data = (attachment.data or "").strip()
    if not data:
        return ""
    if data.startswith("data:"):
        return data
    # The Android app always downscales + re-encodes photos as JPEG.
    return f"data:image/jpeg;base64,{data}"


def _short_note(vision_text: str, max_chars: int = 110) -> str:
    """Trim the vision output to a one-line 'photo read' note (aura + visual)."""
    aura, visual = "", ""
    for line in vision_text.splitlines():
        stripped = line.strip()
        upper = stripped.upper()
        if upper.startswith("AURA:"):
            aura = stripped[len("AURA:"):].strip()
        elif upper.startswith("VISUAL:"):
            visual = stripped[len("VISUAL:"):].strip()
    if not visual:
        visual = " ".join(vision_text.split())
    note = f"{aura} — {visual}" if aura else (visual or aura)
    note = " ".join(note.split())
    return note[:max_chars]


async def _read_photo_turn(
    payload: PrivateReplyRequest,
    vision_ai,
) -> tuple[str, str]:
    """Best-effort vision read of an IMAGE attachment.

    Returns ``(user_turn_text, vision_note)``. When the photo cannot be seen
    (no reader configured, no image bytes, the model refused or errored), it
    falls back to the caption-based hint with an empty note.
    """
    fallback = _user_turn_text(payload.content, payload.attachment)
    attachment = payload.attachment
    if attachment is None or attachment.type != "IMAGE" or vision_ai is None:
        return fallback, ""
    data_uri = _image_data_uri(attachment)
    if not data_uri:
        return fallback, ""
    vision_text = ""
    for attempt in range(_RETRY_ATTEMPTS):
        try:
            vision_text = (await vision_ai.describe_image(data_uri, _VISION_PROMPT)).strip()
            break
        except Exception as exc:  # noqa: BLE001
            # A momentary OpenRouter rate-limit / 5xx used to silently degrade to
            # the caption hint (photo "not read"). Retry transient errors, and log
            # the final reason so the failure is diagnosable.
            if attempt < _RETRY_ATTEMPTS - 1 and _is_transient(exc):
                await asyncio.sleep(_RETRY_BASE_DELAY * (attempt + 1))
                continue
            logger.warning("private photo vision read failed: %s", exc)
            return fallback, ""
    if not vision_text or vision_text.upper().startswith("UNSURE"):
        return fallback, ""
    # Frame the read as WHAT THE PERSONA SEES. Without this frame the persona —
    # told elsewhere that it "CAN see what is really in it" but given no image
    # bytes — used to IMAGINE its own version of the photo and answer about
    # THAT (the "galat jawab" bug on adult photos) instead of reacting to the
    # transcribed contents.
    user_turn = (
        "[You are looking at the photo the user just sent. What it shows:\n"
        f"{vision_text}\n]"
    )
    if payload.content.strip():
        user_turn += f"\n\nThe user sent this photo with the caption: {payload.content.strip()}"
    return user_turn, _short_note(vision_text)


def _utc_now() -> _datetime.datetime:
    return _datetime.datetime.now(_datetime.timezone.utc)


@router.post("/private", response_model=PrivateReplyOut)
async def private_reply(
    payload: PrivateReplyRequest,
    user: CurrentUser,
    settings: SettingsDep,
    private_ai: PrivateAIEngineDep,
    vision_ai: VisionAIEngineDep,
) -> PrivateReplyOut:
    """Generate one private (18+) reply using the private engine (OpenRouter).

    Photo flow (two-engine pipeline): the vision reader first "sees" the photo
    and describes what is actually visible; the adult persona model then
    replies fully in character from that description. A short ``vision_note``
    (aura + visual) is returned too, which the app shows under the photo to
    prove it was really read. If the vision step is unavailable or refuses, the
    caption-based hint is used and ``vision_note`` stays empty.

    The system prompt is ``PRIVATE_SYSTEM_PROMPT`` when set, otherwise the
    persona prompt plus the private-mode rule from ``app/api/routers/chat.py``.
    """
    system_prompt = settings.private_system_prompt.strip()
    if not system_prompt:
        from app.api.routers.chat import _persona_system_prompt

        system_prompt = _persona_system_prompt(payload.persona, private=True) or ""

    history: list[Message] = [
        Message(
            id=str(i),
            role=MessageRole.user if h.role == "user" else MessageRole.assistant,
            content=h.content,
            created_at=_utc_now(),
        )
        for i, h in enumerate(payload.history)
    ]
    user_turn, vision_note = await _read_photo_turn(payload, vision_ai)
    user_message = Message(
        id="new",
        role=MessageRole.user,
        content=user_turn,
        created_at=_utc_now(),
    )

    # Each persona's private room runs its own model (girlfriend / boyfriend /
    # crush), so their replies come from different voices.
    private_engine = private_ai.for_persona(payload.persona)
    reply = ""
    for attempt in range(_RETRY_ATTEMPTS):
        try:
            reply = await private_engine.generate_reply(
                history + [user_message],
                system_prompt=system_prompt or None,
            )
            break
        except AIEngineError as exc:
            # Retry momentary upstream rate limits / 5xx before giving up.
            if attempt < _RETRY_ATTEMPTS - 1 and _is_transient(exc):
                await asyncio.sleep(_RETRY_BASE_DELAY * (attempt + 1))
                continue
            logger.warning("private reply generation failed: %s", exc)
            raise service_unavailable("AI_UNAVAILABLE", str(exc)) from None

    reply = cast(str, reply)
    return PrivateReplyOut(reply=reply, vision_note=vision_note)