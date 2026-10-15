package com.example.vibemate

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * THE REAL VOICE TABLE OF THIS PHONE.
 *
 * A voice call can only sound natural if the phone actually HAS a natural voice
 * for the language the AI speaks — and that is a per-device fact the JVM tests
 * can never see (LiveCallVoice decides with a plain [VoiceInfo] snapshot, so it
 * stays unit-testable). This instrumentation test therefore runs ON THE DEVICE:
 *
 *   * it prints every voice of the installed TTS engine (name / locale /
 *     quality / latency / network / features / gender marker),
 *   * it prints which voice the OLD code path used to get (plain
 *     setLanguage(hi-IN) — the robotic default),
 *   * and it checks that [LiveCallVoice] picks a usable, higher-quality voice
 *     for the persona languages.
 *
 * Read the output with:
 *   adb logcat -d -s VibeMateVoice:I
 */
@RunWith(AndroidJUnit4::class)
class LiveCallVoiceDeviceTest {

    @Test
    fun dumpsEveryVoiceOfThePhone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        var status = Int.MIN_VALUE
        val tts = TextToSpeech(context) { s ->
            status = s
            ready.countDown()
        }
        assertTrue("the TTS engine did not answer in 10s", ready.await(10, TimeUnit.SECONDS))
        assertTrue("no usable TTS engine on this phone (status=$status)", status == TextToSpeech.SUCCESS)

        val voices: Set<Voice> = tts.voices ?: emptySet()
        Log.i(TAG, "ENGINE=${tts.defaultEngine} VOICES=${voices.size}")

        // 1) What the old code got: plain setLanguage(hi-IN) -> engine default.
        val oldResult = tts.setLanguage(Locale.forLanguageTag(AppConfig.LIVE_CALL_LANGUAGE))
        Log.i(
            TAG,
            "OLD_PATH setLanguage(${AppConfig.LIVE_CALL_LANGUAGE})=$oldResult " +
                "-> voice=${tts.voice?.name} quality=${tts.voice?.quality}",
        )

        // 2) Everything the engine offers, grouped by locale, best first.
        for (voice in voices.sortedBy { it.locale.toLanguageTag() }) {
            val info = LiveCallVoice.info(voice)
            Log.i(
                TAG,
                "VOICE name=${info.name} tag=${info.localeTag} q=${info.quality} " +
                    "lat=${info.latency} net=${info.needsNetwork} " +
                    "gender=${LiveCallVoice.genderOfName(info.name)} feats=${info.features}",
            )
        }

        // 3) The languages the call actually speaks, with the picked voice.
        val wanted = listOf(
            AppConfig.LIVE_CALL_LANGUAGE,
            AppConfig.LIVE_CALL_LANGUAGE_FALLBACK,
            AppConfig.LIVE_CALL_TTS_LATIN_TAG,
        ).distinct()
        for (tag in wanted) {
            val pool = voices.map { LiveCallVoice.info(it) }
            val picked = LiveCallVoice.pick(pool, listOf(tag), MateGender.FEMALE)
            Log.i(TAG, "PICK tag=$tag -> ${picked?.name ?: "NONE"} (q=${picked?.quality})")
        }
        Log.i(TAG, "PICK hi-IN male -> ${LiveCallVoice.pick(voices.map { LiveCallVoice.info(it) }, listOf("hi-IN"), MateGender.MALE)?.name}")

        val hi = voices.filter { it.locale.language == "hi" }
        assertTrue(
            "this phone has no Hindi voice at all — the call would fall back to English",
            hi.isNotEmpty(),
        )

        // The regression that mattered: the OLD code spoke with whatever plain
        // setLanguage(hi-IN) returned — on this phone the legacy, pre-neural voice
        // "hi-IN-language" — while the real Hindi voices sat unused. The picker
        // must never choose that voice while a real one exists.
        val pickedHindi = LiveCallVoice.pick(
            voices.map { LiveCallVoice.info(it) },
            listOf(AppConfig.LIVE_CALL_LANGUAGE),
            MateGender.FEMALE,
        )
        assertTrue("the picker found no Hindi voice at all", pickedHindi != null)
        assertFalse(
            "the legacy setLanguage voice is the robot the call used to speak with",
            pickedHindi!!.features.contains("legacySetLanguageVoice"),
        )
        assertFalse(
            "a voice whose data is not on the phone must not be the first pick",
            pickedHindi.features.contains("notInstalled"),
        )
        Log.i(TAG, "CHECK pickedHindi=${pickedHindi.name} (old path was ${tts.voice?.name})")
        tts.shutdown()
    }

    private companion object {
        const val TAG = "VibeMateVoice"
    }
}
