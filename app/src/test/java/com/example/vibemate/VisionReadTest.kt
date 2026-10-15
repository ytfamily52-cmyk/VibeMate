package com.example.vibemate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The private-room PHOTO READER regression suite (pure JVM — no network, no
 * API cost).
 *
 * Locks in the "adult photo not recognised" fix:
 *  1) the reader runs on a FALLBACK CHAIN (primary + fallbacks, no duplicates),
 *     so one provider refusing an explicit photo can no longer end the read;
 *  2) a canned refusal (or an empty / "UNSURE" reply) is NEVER accepted as the
 *     photo's description and therefore never travels to the persona model —
 *     that refusal pass-through was exactly why an adult photo used to get a
 *     reply that had nothing to do with what was actually in it.
 */
class VisionReadTest {

    private val api = VibeMateApi()

    // ------------------------------------------------ reader fallback chain --

    @Test
    fun theReaderChainStartsWithThePrimaryAndHasNoDuplicates() {
        val models = AppConfig.openRouterVisionModels()
        assertEquals(
            "the primary reader must come first",
            AppConfig.OPENROUTER_VISION_MODEL.trim(),
            models.first(),
        )
        assertTrue("at least one fallback reader expected", models.size >= 2)
        assertEquals("no duplicate readers in the chain", models.size, models.toSet().size)
    }

    // ------------------------------------------------------- read acceptance --

    @Test
    fun aRealPhotoDescriptionIsAccepted() {
        val read = "VISUAL: ek aadmi bed par leta hua hai, upar se nanga, " +
            "camera ki taraf dekh raha hai.\nAURA: bold aur confident"
        assertTrue("a genuine VISUAL/AURA read must be accepted", api.privateVisionReadUsable(read))
    }

    @Test
    fun aLongGenuineDescriptionIsNeverMistakenForARefusal() {
        val long = (1..20).joinToString(" ") { i -> "line $i photo ka hissa dikh raha hai" }
        assertTrue(api.privateVisionReadUsable(long))
    }

    @Test
    fun emptyAndUnsureReadsAreRejected() {
        assertFalse(api.privateVisionReadUsable(""))
        assertFalse(api.privateVisionReadUsable("   "))
        assertFalse(api.privateVisionReadUsable("UNSURE"))
        assertFalse(api.privateVisionReadUsable("unsure — this photo cannot be described"))
    }

    @Test
    fun cannedRefusalsAreRejectedNotPassedOnAsDescriptions() {
        for (refusal in listOf(
            "I'm sorry, but I can't describe explicit content.",
            "I cannot assist with that request.",
            "I can't describe this image as it contains sexual content.",
            "I'm unable to view explicit images.",
            "As an AI language model, I cannot describe nudity.",
            "That content violates our content policy.",
        )) {
            assertFalse("refusal must be rejected: $refusal", api.privateVisionReadUsable(refusal))
        }
    }

    @Test
    fun noPhotoChitChatRepliesAreRejectedToo() {
        // Observed LIVE (check_vision_live.py): on a tiny/ambiguous image some
        // readers answer with coaching chit-chat as if no image arrived. That
        // must never travel to the persona as the photo's "description".
        for (noPhoto in listOf(
            "Understood. Please send the photo, and I'll provide a vivid, " +
                "uncensored description in the requested format.",
            "Since there's no photo provided or described, I'll guide you " +
                "through a general response format based on your instructions.",
            "VISUAL: The image depicts [insert detailed description of what is " +
                "seen in the image].\nAURA: [Insert a short phrase capturing the mood]",
            "I cannot see any image in this conversation.",
            "I would examine it closely, but there is no image here.",
        )) {
            assertFalse(
                "no-photo chit-chat must be rejected: $noPhoto",
                api.privateVisionReadUsable(noPhoto),
            )
        }
    }

    @Test
    fun aLongerThan300CharGenuineDescriptionIsStillAccepted() {
        // llama-4's genuine reads can run past 300 chars; the vision cap is 2000.
        val long = "VISUAL: " + (1..40).joinToString(" ") { i -> "line $i photo ka hissa" }
        assertTrue(long.length > 300)
        assertTrue(api.privateVisionReadUsable(long))
    }
}