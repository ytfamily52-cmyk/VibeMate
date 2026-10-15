package com.example.vibemate

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VOICE half of the live call, locked down on the JVM.
 *
 * The bug this file locks in: the call only ever called `setLanguage(hi-IN)` and
 * never chose a voice, so the phone spoke with its low-quality LOCAL default and
 * the code pushed the pitch +8% on top of it. The result was the squeaky, robotic
 * voice the user described as "natural nahi lag raha, sound ajeeb lag raha hai".
 * [LiveCallVoice] now ranks the real voice table of the phone (see the adapter
 * [LiveCallVoice.info] and LiveCallVoiceDeviceTest for the on-device dump) and
 * only shifts the pitch when a voice of the right gender is missing.
 */
class LiveCallVoiceTest {

    private fun voice(
        name: String,
        tag: String,
        quality: Int = Voice.QUALITY_HIGH,
        latency: Int = Voice.LATENCY_NORMAL,
        network: Boolean = false,
    ) = VoiceInfo(name, tag, quality, latency, network)

    // ------------------------------------------------- the voice ranking

    @Test
    fun theNeuralVoiceBeatsTheRoboticLocalOne() {
        // What Google TTS actually ships: a low-quality offline voice and the
        // neural cloud voice of the same language.
        val local = voice("hi-in-x-hid-local", "hi-IN", Voice.QUALITY_LOW)
        val neural = voice("hi-in-x-hid-network", "hi-IN", Voice.QUALITY_VERY_HIGH, network = true)
        val picked = LiveCallVoice.pick(listOf(local, neural), listOf("hi-IN"), MateGender.FEMALE)
        assertEquals("the natural (neural) voice must win", neural, picked)
    }

    @Test
    fun thePersonaGenderDecidesWhichVoiceIsPicked() {
        val female = voice("hi-in-x-hia#female_1-network", "hi-IN", network = true)
        val male = voice("hi-in-x-hid#male_1-network", "hi-IN", network = true)
        val all = listOf(female, male)
        assertEquals(female, LiveCallVoice.pick(all, listOf("hi-IN"), MateGender.FEMALE))
        assertEquals(male, LiveCallVoice.pick(all, listOf("hi-IN"), MateGender.MALE))
    }

    @Test
    fun theLanguageAskedForAlwaysBeatsASecondChoice() {
        // Both are perfect "female" voices, but the reply is Hindi: the Hindi one
        // wins even though the English voice is better quality.
        val hindi = voice("hi-in-x-hia#female_1-local", "hi-IN", Voice.QUALITY_NORMAL)
        val english = voice("en-in-x-ene#female_1-network", "en-IN", Voice.QUALITY_VERY_HIGH, network = true)
        val picked = LiveCallVoice.pick(listOf(hindi, english), listOf("hi-IN", "en-IN"), MateGender.FEMALE)
        assertEquals("the first language of the chain is not negotiable", hindi, picked)
    }

    @Test
    fun aLanguageThePhoneDoesNotHaveIsNotPicked() {
        val tamil = voice("ta-in-x-tac#female_1-network", "ta-IN", network = true)
        assertNull(LiveCallVoice.pick(listOf(tamil), listOf("hi-IN", "en-IN"), MateGender.FEMALE))
        assertEquals(
            LiveCallVoice.UNUSABLE,
            LiveCallVoice.score(tamil, listOf("hi-IN"), MateGender.FEMALE),
        )
    }

    @Test
    fun aVoiceThatAlreadyFailedIsNeverPickedAgain() {
        val neural = voice("hi-in-x-hia#female_1-network", "hi-IN", Voice.QUALITY_VERY_HIGH, network = true)
        val local = voice("hi-in-x-hia#female_1-local", "hi-IN")
        val picked = LiveCallVoice.pick(
            listOf(neural, local),
            listOf("hi-IN"),
            MateGender.FEMALE,
            exclude = setOf(neural.name),
        )
        assertEquals("a dead voice must be dropped for the rest of the call", local, picked)
    }

    @Test
    fun aNotFoundGenderCostsTheVoicePointsButStaysUsable() {
        val anonymous = voice("hi-in-x-abc", "hi-IN", Voice.QUALITY_VERY_HIGH, network = true)
        val wrongGender = voice("hi-in-x-hid#male_1", "hi-IN", Voice.QUALITY_VERY_HIGH)
        val picked = LiveCallVoice.pick(listOf(anonymous, wrongGender), listOf("hi-IN"), MateGender.FEMALE)
        assertEquals("an unnamed voice is safer than a man's voice", anonymous, picked)
        assertTrue(
            "a gender mismatch must not be free",
            LiveCallVoice.score(wrongGender, listOf("hi-IN"), MateGender.FEMALE) <
                LiveCallVoice.score(anonymous, listOf("hi-IN"), MateGender.FEMALE),
        )
    }

    @Test
    fun anEmptyVoiceTableIsNotACrash() {
        assertNull(LiveCallVoice.pick(emptyList(), listOf("hi-IN"), MateGender.FEMALE))
    }

    // ------------------------- the voice table of a REAL phone
    // Names + features below are copied from VibeMate's own device dump
    // (realme RMX3085, Google TTS, 473 voices — see LiveCallVoiceDeviceTest).

    @Test
    fun theLegacySetLanguageVoiceIsNeverPickedWhileRealVoicesExist() {
        // This is what plain setLanguage(hi-IN) used to hand out, right next to
        // the four REAL Hindi voices that were never looked at — the robot voice.
        val legacy = VoiceInfo(
            "hi-IN-language", "hi-IN", Voice.QUALITY_HIGH, Voice.LATENCY_LOW, false,
            setOf("networkTimeoutMs", "legacySetLanguageVoice", "networkRetriesCount"),
        )
        val neural = VoiceInfo(
            "hi-in-x-hie-network", "hi-IN", Voice.QUALITY_HIGH, Voice.LATENCY_LOW, true,
            setOf("networkTimeoutMs", "networkRetriesCount"),
        )
        val local = VoiceInfo(
            "hi-in-x-hia-local", "hi-IN", Voice.QUALITY_HIGH, Voice.LATENCY_LOW, false,
            setOf("networkTimeoutMs", "networkRetriesCount"),
        )
        val all = listOf(legacy, neural, local)
        assertEquals("a real voice must always win", neural, LiveCallVoice.pick(all, listOf("hi-IN"), MateGender.FEMALE))
        // ...and with the neural one gone (offline + blacklisted) the LOCAL voice
        // still beats the legacy robot.
        assertEquals(
            local,
            LiveCallVoice.pick(all, listOf("hi-IN"), MateGender.FEMALE, exclude = setOf(neural.name)),
        )
        // A phone that only has the legacy voice must still be able to speak.
        assertEquals(legacy, LiveCallVoice.pick(listOf(legacy), listOf("hi-IN"), MateGender.FEMALE))
    }

    @Test
    fun aVoiceWhoseDataIsNotDownloadedLosesAgainstAnInstalledOne() {
        val missing = VoiceInfo(
            "en-in-x-ena-local", "en-IN", Voice.QUALITY_HIGH, Voice.LATENCY_LOW, false,
            setOf("networkTimeoutMs", "notInstalled", "networkRetriesCount"),
        )
        val installed = VoiceInfo(
            "en-in-x-ene-local", "en-IN", Voice.QUALITY_HIGH, Voice.LATENCY_LOW, false,
            setOf("networkTimeoutMs", "networkRetriesCount"),
        )
        assertEquals(
            installed,
            LiveCallVoice.pick(listOf(missing, installed), listOf("en-IN"), MateGender.FEMALE),
        )
    }

    @Test
    fun voiceNamesTellTheGenderWhenTheyCan() {
        assertEquals(MateGender.FEMALE, LiveCallVoice.genderOfName("hi-in-x-hia#female_1-network"))
        assertEquals(MateGender.MALE, LiveCallVoice.genderOfName("hi-in-x-hid#male_1-network"))
        assertEquals(
            "the 'female' check must run before the 'male' one",
            MateGender.FEMALE,
            LiveCallVoice.genderOfName("voice-female-alpha"),
        )
        assertNull("a name that says nothing must not guess", LiveCallVoice.genderOfName("hi-in-x-abc-local"))
    }

    @Test
    fun languageTagsMatchRegionOrJustTheLanguage() {
        assertTrue(LiveCallVoice.languageMatches("hi-IN", "hi-IN"))
        assertTrue(LiveCallVoice.languageMatches("hi-in", "hi-IN"))
        assertTrue(LiveCallVoice.languageMatches("hi", "hi-IN"))
        assertFalse(LiveCallVoice.languageMatches("en-IN", "hi-IN"))
        assertFalse(LiveCallVoice.languageMatches("", "hi-IN"))
    }

    // ------------------------------------------------- which language to speak

    @Test
    fun devanagariRepliesGoToTheHindiVoice() {
        val hindi = "\u0915\u0932 \u0915\u094d\u092f\u093e \u0915\u0930 \u0930\u0939\u0947 \u0925\u0947?"
        assertEquals(AppConfig.LIVE_CALL_LANGUAGE, LiveCallVoice.localeTagForText(hindi))
        assertTrue(LiveCallVoice.isDevanagariText(hindi))
        // A Hindi line with English loan words is still a Hindi line: the Hindi
        // voice reads "office" perfectly well, the English one cannot read the rest.
        val hinglish = "\u0906\u091c office \u092e\u0947\u0902 bahut kaam tha"
        assertTrue("one Devanagari word is enough", LiveCallVoice.devanagariRatio(hinglish) > 0f)
        assertEquals(AppConfig.LIVE_CALL_LANGUAGE, LiveCallVoice.localeTagForText(hinglish))
    }

    @Test
    fun latinRepliesGoToTheHinglishVoice() {
        assertEquals(0f, LiveCallVoice.devanagariRatio("haan jaan, ab batao?"), 0.0001f)
        assertEquals(
            AppConfig.LIVE_CALL_TTS_LATIN_TAG,
            LiveCallVoice.localeTagForText("haan jaan, ab batao?"),
        )
        // The knob must stay a real language the engine can be asked for.
        assertFalse(AppConfig.LIVE_CALL_TTS_LATIN_TAG.isEmpty())
        // A purely English line is not Hindi just because it is Hinglish-shaped.
        assertEquals(
            AppConfig.LIVE_CALL_TTS_LATIN_TAG,
            LiveCallVoice.localeTagForText("I am fine, what about you?"),
        )
    }

    // ------------------------------------------------- prosody

    @Test
    fun aMatchingVoiceKeepsTheNaturalPitch() {
        // The old code pushed the pitch +8% on EVERY line, which is what made the
        // voice sound synthetic. A real female voice must NOT be pushed anywhere.
        val girlfriend = LiveCallVoice.profileFor("girlfriend")
        assertEquals(MateGender.FEMALE, girlfriend.gender)
        assertEquals(girlfriend.pitch, LiveCallVoice.pitchFor(girlfriend, MateGender.FEMALE), 0.0001f)
        val boyfriend = LiveCallVoice.profileFor("boyfriend")
        assertEquals(MateGender.MALE, boyfriend.gender)
        assertEquals(boyfriend.pitch, LiveCallVoice.pitchFor(boyfriend, MateGender.MALE), 0.0001f)
    }

    @Test
    fun thePitchOnlyMovesWhenTheVoiceCannotGiveTheGender() {
        val girlfriend = LiveCallVoice.profileFor("girlfriend")
        // A man's voice has to be lifted to pass as a woman...
        assertTrue(LiveCallVoice.pitchFor(girlfriend, MateGender.MALE) > girlfriend.pitch)
        // ...and an unnamed voice is only nudged.
        val unknown = LiveCallVoice.pitchFor(girlfriend, null)
        val wrong = LiveCallVoice.pitchFor(girlfriend, MateGender.MALE)
        assertTrue(unknown > girlfriend.pitch && unknown < wrong)
        // TextToSpeech refuses anything outside 0.5 .. 2.0.
        for (profile in listOf(girlfriend, LiveCallVoice.profileFor("boyfriend"))) {
            for (spokenBy in listOf(null, MateGender.FEMALE, MateGender.MALE)) {
                val pitch = LiveCallVoice.pitchFor(profile, spokenBy)
                assertTrue("pitch out of range: $pitch", pitch in 0.5f..2.0f)
            }
        }
    }

    @Test
    fun everyPersonaGetsAGender() {
        assertEquals(MateGender.FEMALE, LiveCallVoice.genderOfPersona("girlfriend"))
        assertEquals(MateGender.FEMALE, LiveCallVoice.genderOfPersona("crush"))
        assertEquals(MateGender.FEMALE, LiveCallVoice.genderOfPersona(PersonaPrompts.GAY_GIRL))
        assertEquals(MateGender.MALE, LiveCallVoice.genderOfPersona("boyfriend"))
        assertEquals(MateGender.MALE, LiveCallVoice.genderOfPersona(PersonaPrompts.GAY_BOY))
        // The friend persona is a male buddy (default name Rahul), so it gets the
        // male voice on a call — see PersonaPrompts.isMaleMate.
        assertEquals(MateGender.MALE, LiveCallVoice.genderOfPersona("friend"))
        assertEquals(
            "an old 'gay' session is a boy couple",
            MateGender.MALE,
            LiveCallVoice.genderOfPersona("gay"),
        )
    }

    @Test
    fun friendVoiceFollowsTheName() {
        // The gender-neutral FRIEND persona takes its voice gender from the name.
        assertEquals(MateGender.FEMALE, LiveCallVoice.genderOfPersona("friend", "Priya"))
        assertEquals(MateGender.MALE, LiveCallVoice.genderOfPersona("friend", "Rahul"))
        assertEquals(MateGender.FEMALE, LiveCallVoice.profileFor("friend", "Priya").gender)
        assertEquals(MateGender.MALE, LiveCallVoice.profileFor("friend", "Rahul").gender)
        // A fixed-gender persona ignores the name.
        assertEquals(MateGender.FEMALE, LiveCallVoice.genderOfPersona("girlfriend", "Rahul"))
    }

    // ------------------------------------------------- the turn itself

    @Test
    fun aShortLineIsSpokenInOnePiece() {
        val line = "haan jaan, ab batao?"
        assertEquals(listOf(line), LiveCallVoice.splitForSpeech(line))
    }

    @Test
    fun aLongLineStartsEarlyAndIsNeverCutMidWord() {
        val line = "haan jaan main theek hoon, aaj bahut kaam tha office me. " +
            "phir maine tumhe yaad kiya aur socha call karun. ab batao tumhara din kaisa raha?"
        val pieces = LiveCallVoice.splitForSpeech(line)
        assertEquals("one long line becomes two spoken pieces", 2, pieces.size)
        assertTrue("the head must be the short one", pieces[0].length <= 90)
        assertFalse("never stop mid word", pieces[0].endsWith(" th"))
        // Nothing may be lost between the pieces.
        assertEquals(line, pieces.joinToString(" "))
        assertTrue("the head must end on a boundary", pieces[0].endsWith(",") || pieces[0].endsWith("."))
    }

    @Test
    fun aTextWithoutAnyBoundaryIsStillSpokenWhole() {
        val line = (1..40).joinToString("") { "shabd" }
        val pieces = LiveCallVoice.splitForSpeech(line)
        assertEquals("no boundary -> never risk a mid-word cut", listOf(line), pieces)
    }

    @Test
    fun theThinkingPauseHumsWithoutRepeatingItself() {
        assertEquals(AppConfig.LIVE_CALL_FILLERS[0], LiveCallVoice.filler(0))
        assertEquals(AppConfig.LIVE_CALL_FILLERS[1], LiveCallVoice.filler(1))
        assertEquals(
            "the rotation must wrap around",
            AppConfig.LIVE_CALL_FILLERS[0],
            LiveCallVoice.filler(AppConfig.LIVE_CALL_FILLERS.size),
        )
        assertTrue("no hum is ever empty text", AppConfig.LIVE_CALL_FILLERS.all { it.isNotBlank() })
        assertNotNull(LiveCallVoice.filler(7))
    }
}
