"""Deterministic offline AI engine used when ``GEMINI_API_KEY`` is empty.

Used for demo mode and tests: replies are pure functions of the last user
message, so behaviour is fully predictable and no network calls are made.
"""
from __future__ import annotations

import hashlib

from app.ai import BaseAIEngine
from app.domain import Message, MessageRole

_REPLY_TEMPLATES = (
    "[demo] Got it — “{snippet}”. Once a Gemini API key is configured, I'll "
    "reply with real generative AI. In the meantime you're talking to "
    "VibeMate's deterministic demo engine.",
    "[demo] “{snippet}” — that's the vibe! Connect a GEMINI_API_KEY in "
    "backend/.env and I'll respond with full generative smarts.",
    "[demo] Thanks for sharing “{snippet}”. The demo engine keeps things "
    "simple: set GEMINI_API_KEY to unlock real conversation.",
    "[demo] Noted: “{snippet}”. I'm in demo mode right now, so my replies are "
    "canned — head over to backend/.env and add a Gemini API key for the "
    "full experience.",
)


class DemoAIEngine(BaseAIEngine):
    """Mock engine with stable, input-derived replies (zero network I/O)."""

    async def generate_reply(
        self,
        messages: list[Message],
        system_prompt: str | None = None,
    ) -> str:
        user_texts = [m.content for m in messages if m.role == MessageRole.user]
        last = user_texts[-1] if user_texts else ""
        snippet = (last if len(last) <= 80 else last[:77] + "...") or "(no message yet)"
        # Persona-flavoured demo replies keep demo mode distinguishable too.
        # Ordered most-specific first: ONLY the two same-sex prompts say "BOTH
        # men" / "BOTH women", and they still say it in Private Mode — where the
        # gender-lock addendum names EVERY persona (a girl-gay room mentions
        # "boyfriend" and vice versa), so a plain keyword scan of the private
        # prompt would mislabel both gay rooms.
        markers = (
            # Same-sex couples first: ONLY those two prompts say "BOTH men" /
            # "BOTH women" (and the legacy "gay" id folds onto gay_boy, which
            # then says "BOTH men" too).
            ("both women", "gay_girl"),
            ("both men", "gay_boy"),
            # Then one phrase that lives in exactly ONE base persona prompt: the
            # private (18+) addendum names EVERY persona, so the bare relationship
            # words below are only a last-resort fallback for a custom prompt.
            ("texting her girlfriend", "gay_girl"),
            ("texting his boyfriend", "gay_boy"),
            ("loving, playful girlfriend", "girlfriend"),
            ("loving, protective boyfriend", "boyfriend"),
            ("shy but flirty crush", "crush"),
            ("boy-gay", "gay_boy"),
            ("girl-gay", "gay_girl"),
            ("lesbian", "gay_girl"),
            ("boyfriend", "boyfriend"),
            ("crush", "crush"),
            ("girlfriend", "girlfriend"),
        )
        persona = "unknown"
        if system_prompt:
            lowered = system_prompt.lower()
            for marker, candidate in markers:
                if marker in lowered:
                    persona = candidate
                    break
        index = int.from_bytes(hashlib.sha256(last.encode("utf-8")).digest()[:2], "big")
        template = _REPLY_TEMPLATES[index % len(_REPLY_TEMPLATES)]
        return template.format(snippet=snippet) + (
            f" (persona: {persona})" if persona != "unknown" else ""
        )