package com.example.vibemate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 18+ engine's KEY-VALIDATION regression suite (pure JVM — no network, no
 * API cost).
 *
 * Locks in the "18+ engine is not configured" bug: the check used to require an
 * OpenRouter-shaped key (`startsWith("sk-or-")`), so a perfectly valid VENICE
 * key — the provider the app now talks to — was rejected and the Private room
 * showed "engine is not configured" even though the key WAS in BuildConfig.
 *
 * The rule is provider-agnostic now: only an EMPTY value or the placeholder from
 * `secrets.properties.example` counts as "not configured".
 */
class PrivateEngineKeyTest {

    // ------------------------------------------------- real keys are accepted --

    @Test
    fun aVeniceKeyIsAccepted() {
        assertTrue(
            "a real Venice key must count as configured (this was the bug)",
            AppConfig.isUsablePrivateKey(
                "VENICE_INFERENCE_KEY_Re_4cJKEXeZeDoyO3jKJKkJ3bC2vUmhUJlO-DA6Fmq",
            ),
        )
    }

    @Test
    fun anOpenRouterKeyIsStillAccepted() {
        assertTrue(AppConfig.isUsablePrivateKey("sk-or-v1-" + "a".repeat(64)))
    }

    @Test
    fun anyOpenAiCompatibleProvidersKeyIsAccepted() {
        for (key in listOf(
            "sk-abcdef0123456789abcdef0123456789", // OpenAI-shaped
            "tgp_v1_abcdefghijklmnopqrstuvwxyz", // Together-shaped
            "di_abcdefghijklmnopqrstuvwxyz", // DeepInfra-shaped
        )) {
            assertTrue("must accept provider key: $key", AppConfig.isUsablePrivateKey(key))
        }
    }

    @Test
    fun surroundingWhitespaceDoesNotBreakIt() {
        assertTrue(AppConfig.isUsablePrivateKey("  VENICE_INFERENCE_KEY_Re_abc123  "))
        assertTrue(AppConfig.isUsablePrivateKey("\nsk-or-v1-abc123\n"))
    }

    // -------------------------------------------- missing / placeholder keys --

    @Test
    fun anEmptyKeyIsNotConfigured() {
        assertFalse(AppConfig.isUsablePrivateKey(""))
        assertFalse(AppConfig.isUsablePrivateKey("   "))
        assertFalse(AppConfig.isUsablePrivateKey("\n\t"))
    }

    @Test
    fun theExamplePlaceholderIsNotConfigured() {
        assertFalse(AppConfig.isUsablePrivateKey("sk-or-v1-PASTE_YOUR_OPENROUTER_KEY_HERE"))
        assertFalse(AppConfig.isUsablePrivateKey("PASTE_YOUR_GEMINI_KEY_HERE"))
        assertFalse(AppConfig.isUsablePrivateKey("paste_your_key_here"))
    }

    // ------------------------------------------------------ shipped key check --

    @Test
    fun aNonEmptyShippedKeyIsAlwaysTreatedAsConfigured() {
        // Reads the key injected into BuildConfig from secrets.properties. It is
        // empty only in a checkout without a secrets file — then there is nothing
        // to assert.
        if (AppConfig.OPENROUTER_API_KEY.trim().isEmpty()) return
        assertTrue(
            "a non-empty shipped key must never be reported as 'not configured'",
            AppConfig.openRouterConfigured(),
        )
    }
}
