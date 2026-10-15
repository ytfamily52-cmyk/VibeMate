package com.example.vibemate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the detector that tells an explicit-content SAFETY block in NORMAL chat
 * apart from every other Gemini error. When it matches, MainActivity shows a
 * "Join Private Chat" invite instead of the raw "Unexpected Gemini response"
 * error (see `VibeMateApi.isExplicitContentBlock`).
 */
class ExplicitContentBlockTest {

    private val api = VibeMateApi()

    @Test
    fun detectsTheSexualHarmCategoryInsideAGeminiResponse() {
        val raw = "Unexpected Gemini response: " +
            "{\"candidates\":[{\"content\":{\"parts\":[],\"role\":\"model\"}," +
            "\"finishReason\":\"SAFETY\",\"safetyRatings\":[" +
            "{\"category\":\"HARM_CATEGORY_SEXUALLY_EXPLICIT\",\"probability\":\"HIGH\"}]}]," +
            "\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"
        assertTrue(api.isExplicitContentBlock(raw))
    }

    @Test
    fun detectsADirectSexuallyExplicitBlockReason() {
        assertTrue(api.isExplicitContentBlock("{\"promptFeedback\":{\"blockReason\":\"SEXUALLY_EXPLICIT\"}}"))
    }

    @Test
    fun doesNotFlagOrdinaryErrors() {
        assertFalse(api.isExplicitContentBlock("Gemini API key is not set."))
        assertFalse(api.isExplicitContentBlock("Quota exceeded for metric generate requests, limit: 20"))
        assertFalse(api.isExplicitContentBlock("network-request-failed"))
    }

    @Test
    fun doesNotFlagANonSexualSafetyBlock() {
        // A harassment / hate-speech block is NOT a reason to offer the 18+ room.
        val raw = "Unexpected Gemini response: " +
            "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]," +
            "\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"
        assertFalse(api.isExplicitContentBlock(raw))
    }
}
