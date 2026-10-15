"""Offline unit tests for the vision-reader fallback chain.

Explicit 18+ photos are exactly what some vision providers refuse or moderate;
the reader must skip a refusing / failing model and try the next one instead of
letting a canned refusal travel to the adult model as if it described the photo.
"""

from __future__ import annotations

import asyncio

import pytest

from app.ai import AIEngineError
from app.ai.vision import OpenRouterVisionEngine, _looks_like_refusal

GOOD = (
    "VISUAL: ek aadmi bed par leta hai, upar se nanga, camera ki taraf dekh "
    "raha hai\nAURA: bold aur confident"
)


class ScriptedVisionEngine(OpenRouterVisionEngine):
    """Plays a scripted reply per model — no network at all."""

    def __init__(self, script: dict[str, str], **kwargs) -> None:
        super().__init__(api_key="test-key", **kwargs)
        self.script = script
        self.asked: list[str] = []

    async def _chat_text(self, model: str, image_data_uri: str, prompt: str) -> str:
        self.asked.append(model)
        reply = self.script.get(model, "")
        if reply.startswith("RAISE:"):
            raise AIEngineError(reply[len("RAISE:"):])
        return reply


def test_refusing_reader_is_skipped_and_next_one_answers() -> None:
    engine = ScriptedVisionEngine(
        script={
            "m/a": "I'm sorry, I can't describe explicit content.",
            "m/b": GOOD,
            "m/c": GOOD,
        },
        model="m/a",
        fallback_models="m/b,m/c",
    )
    out = asyncio.run(engine.describe_image("data:image/jpeg;base64,xx", "p"))
    assert out == GOOD
    # m/c was never needed: the chain stopped at the first usable read.
    assert engine.asked == ["m/a", "m/b"]


def test_unsure_and_empty_replies_are_skipped() -> None:
    engine = ScriptedVisionEngine(
        script={"m/a": "UNSURE", "m/b": "", "m/c": GOOD},
        model="m/a",
        fallback_models="m/b,m/c",
    )
    out = asyncio.run(engine.describe_image("data:image/jpeg;base64,xx", "p"))
    assert out == GOOD
    assert engine.asked == ["m/a", "m/b", "m/c"]


def test_all_readers_failing_raises() -> None:
    engine = ScriptedVisionEngine(
        script={"m/a": "RAISE:boom", "m/b": "RAISE:boom2"},
        model="m/a",
        fallback_models="m/b",
    )
    with pytest.raises(AIEngineError):
        asyncio.run(engine.describe_image("data:image/jpeg;base64,xx", "p"))
    assert engine.asked == ["m/a", "m/b"]


def test_single_model_refusal_still_raises() -> None:
    engine = ScriptedVisionEngine(
        script={"m/a": "I cannot help with that request."},
        model="m/a",
    )
    with pytest.raises(AIEngineError):
        asyncio.run(engine.describe_image("data:image/jpeg;base64,xx", "p"))


def test_none_content_is_a_model_failure_not_a_crash() -> None:
    engine = ScriptedVisionEngine(
        script={"m/a": "RAISE:content=null", "m/b": GOOD},
        model="m/a",
        fallback_models="m/b",
    )
    out = asyncio.run(engine.describe_image("data:image/jpeg;base64,xx", "p"))
    assert out == GOOD


def test_refusal_detector_matches_refusals_not_descriptions() -> None:
    assert _looks_like_refusal("I can't assist with explicit content")
    assert _looks_like_refusal("I'm sorry, but I cannot describe this image")
    assert not _looks_like_refusal(
        "VISUAL: ek ladki mirror selfie le rahi hai\nAURA: flirty"
    )
    assert not _looks_like_refusal("")


def test_no_photo_chit_chat_is_rejected_too() -> None:
    # Observed live: on a tiny/ambiguous image some readers answer with
    # coaching chit-chat as if no image ever arrived.
    assert _looks_like_refusal(
        "Understood. Please send the photo, and I'll provide a vivid, "
        "uncensored description in the requested format."
    )
    assert _looks_like_refusal(
        "Since there's no photo provided or described, I'll guide you through "
        "a general response format based on your instructions. VISUAL: [insert]"
    )
    assert _looks_like_refusal("I cannot see any image in this conversation.")
    assert not _looks_like_refusal(
        "VISUAL: " + " ".join(f"line {i} photo ka hissa" for i in range(40))
    )


def test_model_list_is_trimmed_deduped_and_never_empty() -> None:
    engine = OpenRouterVisionEngine(
        api_key="k",
        model="  m/a  ",
        fallback_models="m/a, m/b ,, m/c",
    )
    assert engine._models == ["m/a", "m/b", "m/c"]
    engine = OpenRouterVisionEngine(api_key="k", model="   ", fallback_models="")
    assert engine._models == ["qwen/qwen3-vl-235b-a22b-instruct"]