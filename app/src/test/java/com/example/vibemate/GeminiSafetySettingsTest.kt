package com.example.vibemate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the Gemini `safetySettings` categories.
 *
 * THE BUG THIS PINS: the app used to send the category
 * `"HARM_CATEGORY_SEXUALLY_EXPLICIT_CONTENT"`, which is NOT a real Gemini
 * `HarmCategory` (the correct value is `HARM_CATEGORY_SEXUALLY_EXPLICIT`, with
 * no trailing `_CONTENT`). Gemini then rejected the WHOLE request with:
 *
 *   HTTP 400 "Invalid value at 'safety_settings[3].category' ...
 *             HARM_CATEGORY_SEXUALLY_EXPLICIT_CONTENT"
 *
 * Because that payload is built for EVERY normal-mode turn (see
 * `VibeMateApi.geminiReply`), every normal chat failed — even a plain "hello"
 * — and the surfaced error mentioned SEXUALLY_EXPLICIT, so users reported it as
 * an "explicit content error". These tests keep the exact enum names pinned.
 */
class GeminiSafetySettingsTest {

    private val api = VibeMateApi()

    @Test
    fun everyCategoryIsARealGeminiHarmCategory() {
        val valid = setOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_CIVIC_INTEGRITY",
        )
        for (category in api.geminiSafetyCategories) {
            assertTrue(
                "not a real Gemini HarmCategory (would 400 every normal turn): $category",
                category in valid,
            )
        }
    }

    @Test
    fun theSexualCategoryHasNoTrailingContentSuffix() {
        val categories = api.geminiSafetyCategories
        assertTrue(
            "the sexual category must be sent, else adult photos stay blocked",
            categories.contains("HARM_CATEGORY_SEXUALLY_EXPLICIT"),
        )
        // The exact typo that broke normal chat must never come back. (NOTE:
        // HARM_CATEGORY_DANGEROUS_CONTENT legitimately ends with _CONTENT, so
        // this checks the EXACT bad string, not just the suffix.)
        assertFalse(
            "HARM_CATEGORY_SEXUALLY_EXPLICIT_CONTENT is invalid and 400s the request",
            categories.contains("HARM_CATEGORY_SEXUALLY_EXPLICIT_CONTENT"),
        )
    }

    @Test
    fun allFourCoreCategoriesArePresentWithoutDuplicates() {
        val categories = api.geminiSafetyCategories
        assertEquals(
            "Gemini rejects duplicates but the app must cover all core categories",
            categories.size,
            categories.distinct().size,
        )
        assertEquals(
            listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_DANGEROUS_CONTENT",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            ),
            categories,
        )
    }
}
