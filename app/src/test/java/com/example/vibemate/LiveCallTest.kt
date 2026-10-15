package com.example.vibemate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live voice call regression suite.
 *
 * The bug this locks in: the call button opened a screen with a timer only —
 * no microphone permission, no speech recogniser and no text-to-speech — so the
 * AI neither HEARD the user nor SAID anything. The voice loop itself is Android
 * API (SpeechRecognizer/TextToSpeech, see LiveCallEngine.kt), but its TEXT half
 * is pure logic and is tested here on the JVM: what gets spoken, what the
 * transcriber's answer means, and the call rules that keep a reply speakable.
 */
class LiveCallTest {

    private val api = VibeMateApi()

    // ------------------------------------------------------ the AI's voice

    @Test
    fun spokenTextDropsBubbleSeparatorsAndMarkup() {
        val raw = "haan jaan **bilkul** ||| ab batao tumhara din kaisa tha?"
        val spoken = LiveCallSpeech.spokenText(raw)
        assertFalse("the '|||' bubble marker must never be read aloud", spoken.contains("|||"))
        assertFalse("markdown must never be read aloud", spoken.contains("*"))
        assertTrue(spoken.startsWith("haan jaan bilkul"))
    }

    @Test
    fun longReplyIsCutAtAWholeSentenceNotMidWord() {
        val long = "haan jaan main theek hoon. " +
            "aaj maine tumhare liye khana banaya tha. " +
            "phir maine tumhe call kiya lekin tum utha nahi. " +
            "ab batao tumhara pura din kaisa raha aur kya kiya tumne."
        val spoken = LiveCallSpeech.spokenText(long)
        assertTrue(
            "a call line must stay short, got ${spoken.length} chars",
            spoken.length <= AppConfig.LIVE_CALL_MAX_SPOKEN_CHARS,
        )
        assertFalse("never cut mid-word", spoken.endsWith(" th") || spoken.endsWith(" tumh"))
        assertTrue("the first sentence survives", spoken.contains("main theek hoon"))
    }

    @Test
    fun shortReplyIsSpokenUntouched() {
        assertEquals("haan jaan, ab batao?", LiveCallSpeech.spokenText("haan jaan, ab batao?"))
    }

    @Test
    fun emptyOrMarkupOnlyReplyIsNotSpokenAsNoise() {
        assertEquals("", LiveCallSpeech.spokenText("*_`"))
    }

    // ------------------------------------------------------ the AI's ears

    @Test
    fun transcriptionUsesTheFirstRealCandidate() {
        val heard = LiveCallSpeech.bestResult(listOf("", "a", "kya kar rahi ho"))
        assertEquals("kya kar rahi ho", heard)
        assertTrue(LiveCallSpeech.usableSpeech("haan"))
        assertFalse(LiveCallSpeech.usableSpeech("  "))
        assertFalse(LiveCallSpeech.usableSpeech("!"))
    }

    @Test
    fun silenceGivesNothingToAnswer() {
        assertEquals("", LiveCallSpeech.bestResult(null))
        assertEquals("", LiveCallSpeech.bestResult(emptyList()))
    }

    // ------------------------------------------------- greeting + trouble line

    @Test
    fun greetingIsGenderedAndUsesTheMateName() {
        val aisha = LiveCallSpeech.greeting("girlfriend", "Priya")
        assertTrue(aisha.contains("Priya"))
        assertTrue("a female mate must say 'bol rahi hoon'", aisha.contains("बोल रही हूँ"))
        val arjun = LiveCallSpeech.greeting("boyfriend", "")
        assertTrue(arjun.contains(PersonaPrompts.defaultName("boyfriend")))
        assertTrue("a male mate must say 'bol raha hoon'", arjun.contains("बोल रहा हूँ"))
        // The friend persona's gender comes from the NAME: a male name (or none)
        // speaks in the male form...
        val rahul = LiveCallSpeech.greeting("friend", "")
        assertTrue(rahul.contains(PersonaPrompts.defaultName("friend")))
        assertTrue("the friend mate must say 'bol raha hoon'", rahul.contains("बोल रहा हूँ"))
        // ...and a girl's name speaks in the FEMALE form.
        val priya = LiveCallSpeech.greeting("friend", "Priya")
        assertTrue(priya.contains("Priya"))
        assertTrue("a female friend must say 'bol rahi hoon'", priya.contains("बोल रही हूँ"))
    }

    @Test
    fun troubleLineKeepsTheCallAlive() {
        assertTrue(LiveCallSpeech.troubleLine().isNotEmpty())
    }

    @Test
    fun busyLineIsDistinctFromTheNetworkLine() {
        // A quota / overloaded engine must NOT be reported as a network problem —
        // that used to send users off to check their Wi-Fi for nothing.
        assertTrue(LiveCallSpeech.busyLine().isNotEmpty())
        assertFalse(
            "the busy line must differ from the network line",
            LiveCallSpeech.busyLine() == LiveCallSpeech.troubleLine(),
        )
    }

    @Test
    fun privateCallOpensIntimately() {
        val normal = LiveCallSpeech.greeting("girlfriend", "Priya")
        val private = LiveCallSpeech.greeting("girlfriend", "Priya", privateMode = true)
        assertFalse("the normal opener must stay friendly", normal.contains("जान"))
        assertTrue("the 18+ call must open intimately", private.contains("जान"))
        assertTrue(private.contains("Priya"))
        assertTrue("the private mate is still gendered", private.contains("बोल रही हूँ"))
    }

    @Test
    fun micSilenceExtrasAreLongBecauseTheRecogniserReadsLongs() {
        // THE bug behind the mic click-click loop: the silence extras were put in
        // the intent as Int, the recognition service read them with getLong() and
        // threw ClassCastException, so every session died instantly and nothing
        // was ever heard. These explicit `Long` types make a regression a COMPILE
        // error (recognizerIntent() puts them in with putExtra(String, Long)).
        val complete: Long = AppConfig.LIVE_CALL_SILENCE_MS
        val possibly: Long = AppConfig.LIVE_CALL_POSSIBLE_SILENCE_MS
        val minimum: Long = AppConfig.LIVE_CALL_MINIMUM_LENGTH_MS
        assertTrue(complete in 300L..5000L)
        assertTrue(possibly in complete..8000L)
        assertTrue(minimum > 0L && minimum < complete)
    }

    @Test
    fun micRetryBacksOffInsteadOfClickingForever() {
        val base = AppConfig.LIVE_CALL_REOPEN_MIC_DELAY_MS
        // A healthy call (silence only) re-opens at the normal fast pace...
        assertEquals(base, LiveCallSpeech.micReopenDelayMs(0, 0))
        // ...a WEDGED recogniser must be retried slower and slower, never in a
        // tight loop (that loop IS the click-click the user heard).
        val one = LiveCallSpeech.micReopenDelayMs(1, 0)
        val two = LiveCallSpeech.micReopenDelayMs(2, 0)
        val many = LiveCallSpeech.micReopenDelayMs(9, 0)
        assertTrue("one failure must already slow the retry", one > base)
        assertTrue(two > one)
        assertEquals("the back-off must cap", many, LiveCallSpeech.micReopenDelayMs(50, 0))
        assertTrue("and stay a pause, not a hang", many < 5000L)
        // Even a run of silent turns earns a little breathing room.
        assertTrue(LiveCallSpeech.micReopenDelayMs(0, 3) > base)
        // A single silent turn is still instantly answered (natural conversation).
        assertEquals(base, LiveCallSpeech.micReopenDelayMs(0, 1))
    }

    @Test
    fun spokenLineHasEnergyBudgetForTheWatchdog() {
        // The engine never waits for "onDone" longer than base + per-char budget,
        // so a phone whose voice engine goes silent can never leave the mic shut.
        val short = LiveCallSpeech.spokenText("haan jaan")
        val budget = LiveCallEngine.speechWatchdogBudgetMs(short)
        assertTrue("a short line must be watched for a few seconds", budget >= 2500L)
        val long = LiveCallSpeech.spokenText((1..200).joinToString(" ") { "shabd$it" })
        assertTrue(
            "a long line must still be watched for at most 30s",
            LiveCallEngine.speechWatchdogBudgetMs(long) <= 30_000L,
        )
    }

    // ------------------------------------------------- the call persona rules

    @Test
    fun callRuleMakesEveryReplySpeakable() {
        val prompt = PersonaPrompts.systemPrompt("girlfriend", "Priya", callTurn = true)
        assertTrue(prompt.contains("LIVE PHONE CALL"))
        assertTrue(prompt.contains("max 25 words"))
        assertTrue("emoji must be forbidden on a call", prompt.contains("NEVER use emoji"))
        // A spoken line must be written in Devanagari: the phone's Hindi voice reads
        // Devanagari with the right pronunciation, while Roman Hindi is read with
        // English phonemes — one of the reasons the voice sounded "ajeeb".
        assertTrue(
            "a call turn must ask for Devanagari Hindi",
            prompt.contains("DEVANAGARI"),
        )
        assertTrue(prompt.contains("\u0939\u093e\u0901 \u091c\u093e\u0928"))
    }

    @Test
    fun privateCallRuleSpeaksWithoutBubbles() {
        val prompt = PersonaPrompts.systemPrompt(
            "boyfriend",
            "Arjun",
            privateMode = true,
            callTurn = true,
        )
        assertTrue(prompt.contains("PRIVATE CALL"))
        assertTrue(
            "a private call turn must never come back as '|||' bubbles",
            prompt.contains("no bubbles"),
        )
    }

    @Test
    fun aCallTurnReplacesTheWrittenLengthRules() {
        // Typed chat: a real question gets the long INFO rule, normal baat-cheet
        // gets the "|||" bubble rule.
        val info = PersonaPrompts.systemPrompt("girlfriend", "Priya", privateMode = true, infoTurn = true)
        assertTrue(info.contains("ANSWER STYLE"))
        assertFalse(info.contains("PRIVATE CALL"))
        // Spoken turn: BOTH are replaced by the one-line CALL rule, because a
        // phone voice cannot read a long answer or "|||" markers.
        val call = PersonaPrompts.systemPrompt(
            "girlfriend",
            "Priya",
            privateMode = true,
            infoTurn = true,
            callTurn = true,
        )
        assertTrue(call.contains("PRIVATE CALL"))
        assertFalse("the long written answer rule must not survive a call", call.contains("ANSWER STYLE"))
        assertFalse("the bubble rule must not survive a call", call.contains("LENGTH STYLE"))
    }
}
