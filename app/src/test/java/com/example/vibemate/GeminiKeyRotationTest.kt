package com.example.vibemate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Key-rotation regression suite.
 *
 * The bodies below are the REAL 429 responses captured from
 * `generativelanguage.googleapis.com/v1beta/models/<model>:generateContent` for
 * the two keys in [AppConfig]:
 *
 *  - key 1 (`GEMINI_API_KEY`) is a FREE-TIER key: `limit: 20` requests per
 *    `GenerateRequestsPerDayPerProjectPerModel-FreeTier` — a DAILY quota, so it
 *    cannot recover in a minute.
 *  - key 2 (`GEMINI_API_KEY_2`) answers `/v1beta/models` fine but every
 *    `generateContent` call fails with "Your prepayment credits are depleted" —
 *    a permanently dead key.
 *
 * That combination is why the rotation "did not work": both keys were rested for
 * only 60 s, so on every single message the app re-picked a key that could not
 * possibly answer, burned the round-trip and only then fell through to the key
 * that still had quota. These tests lock in the per-day / per-wallet aware rests.
 */
class GeminiKeyRotationTest {

    private val api = VibeMateApi()

    /** key 1 (free tier, daily limit) on gemini-3.5-flash. */
    private val freeTierDailyBody = """
        {"error":{"code":429,"message":"You exceeded your current quota, please check your
        plan and billing details. For more information on this error, head to:
        https://ai.google.dev/gemini-api/docs/rate-limits. Quota exceeded for metric:
        generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20,
        model: gemini-3.5-flash Please retry in 29.593136186s.","status":"RESOURCE_EXHAUSTED",
        "details":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier",
        "quotaValue":"20"},{"retryDelay":"29s"}]}}
    """.trimIndent()

    /** key 2 on EVERY model. */
    private val creditsDeadBody = """
        {"error":{"code":429,"message":"Your prepayment credits are depleted. Please go to
        AI Studio at https://ai.studio/projects to manage your project and billing.
        Learn more at https://ai.google.dev/gemini-api/docs/billing#prepay. ",
        "status":"RESOURCE_EXHAUSTED"}}
    """.trimIndent()

    /** A plain per-minute 429 (retryDelay only). */
    private val perMinuteBody = """
        {"error":{"code":429,"message":"Resource has been exhausted, please retry in 12.5s",
        "status":"RESOURCE_EXHAUSTED"}}
    """.trimIndent()

    // ------------------------------------------------------------------ rest length

    @Test
    fun freeTierDailyQuotaRestsLongInsteadOfOneMinute() {
        val rest = api.geminiQuotaRestMs(freeTierDailyBody)
        assertEquals(AppConfig.GEMINI_KEY_EXHAUSTED_REST_MS, rest)
        assertTrue("a daily quota must not be retried every minute", rest >= 10 * 60_000L)
    }

    @Test
    fun depletedCreditsKeyRestsLong() {
        assertEquals(AppConfig.GEMINI_KEY_EXHAUSTED_REST_MS, api.geminiQuotaRestMs(creditsDeadBody))
    }

    @Test
    fun serverRetryDelayIsHonouredForAPlainPerMinuteLimit() {
        val rest = api.geminiQuotaRestMs(perMinuteBody)
        assertTrue("expected ~12.5s + margin, got " + rest, rest in 12_000L..20_000L)
        assertTrue("a per-minute limit must not rest for half an hour", rest < AppConfig.GEMINI_KEY_EXHAUSTED_REST_MS)
    }

    @Test
    fun retryDelayJsonFieldIsParsed() {
        val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"retryDelay":"29s"}]}}"""
        assertEquals(31_000L, api.geminiQuotaRestMs(body))
    }

    @Test
    fun unknownQuotaErrorFallsBackToTheShortDefault() {
        assertEquals(
            AppConfig.GEMINI_KEY_COOLDOWN_MS,
            api.geminiQuotaRestMs("Gemini error 429 (RESOURCE_EXHAUSTED)"),
        )
    }

    // ------------------------------------------------------------------ diagnosis

    @Test
    fun depletedCreditsIsRecognisedButAFreeTierQuotaIsNot() {
        assertTrue(api.isCreditsError(creditsDeadBody))
        // The free-tier body also says "check your plan and billing details", so a
        // naive "billing" check would misreport it as an empty wallet.
        assertFalse(api.isCreditsError(freeTierDailyBody))
        assertTrue(api.isDailyQuotaError(freeTierDailyBody))
        assertFalse(api.isDailyQuotaError(creditsDeadBody))
    }

    @Test
    fun rotationMessageNamesTheRealCause() {
        val credits = api.geminiQuotaMessage(2, anyCreditsDead = true, anyDailyExhausted = false)
        assertTrue(credits, credits.contains("credits are depleted"))

        val daily = api.geminiQuotaMessage(2, anyCreditsDead = false, anyDailyExhausted = true)
        assertTrue(daily, daily.contains("DAILY limit"))

        val both = api.geminiQuotaMessage(2, anyCreditsDead = true, anyDailyExhausted = true)
        assertTrue(both, both.contains("credits") && both.contains("DAILY"))

        val plain = api.geminiQuotaMessage(2, anyCreditsDead = false, anyDailyExhausted = false)
        assertTrue(plain, plain.contains("rate-limited"))

        // A single key must not be described as "Both API keys".
        val single = api.geminiQuotaMessage(1, anyCreditsDead = false, anyDailyExhausted = false)
        assertTrue(single, single.startsWith("The Gemini API key"))
    }

    @Test
    fun emptyKeySlotsAreIgnoredByTheRotation() {
        val keys = AppConfig.geminiKeys()
        assertTrue("the rotation needs at least one key", keys.isNotEmpty())
        assertTrue("a blank key slot leaked into the rotation: " + keys, keys.none { it.isBlank() })
        assertTrue("duplicate keys would be tried twice", keys.size == keys.distinct().size)
    }
}
