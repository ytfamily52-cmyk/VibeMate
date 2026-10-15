package com.example.vibemate

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * The REAL voice engine behind the call button.
 *
 * Before this file the "live call" was a screen with a timer: no microphone
 * permission, no recogniser and no text-to-speech, so the AI could neither HEAR
 * the user nor SAY anything (the old backend even called it a "demo — no real
 * voice streaming"). The whole loop now runs on the phone:
 *
 *   mic --SpeechRecognizer--> text --VibeMateApi.sendMessage()--> reply text
 *        --TextToSpeech--> the AI's voice --> mic again …
 *
 * This class owns only the VOICE half (mic in, speaker out, turn switching and
 * echo protection); the AI half (history, persona, Firestore persistence) stays
 * in [VibeMateApi] and is driven by MainActivity through [Listener]:
 *
 *   onHeard(text)  -> the activity runs the normal chat turn
 *   speakReply(txt)-> the activity hands the reply back to be spoken
 *
 * IMPORTANT: every public method must be called on the MAIN THREAD — Android's
 * SpeechRecognizer is created there and TextToSpeech callbacks arrive there.
 */
enum class LiveCallState {
    /** Voice engine warming up (TTS init + first greeting). */
    CONNECTING,
    /** Mic is open — the AI is hearing the user right now. */
    LISTENING,
    /** Your words are being turned into a reply (Gemini / OpenRouter). */
    THINKING,
    /** The AI is talking — the mic is CLOSED on purpose (no echo). */
    SPEAKING,
    /** The user muted the mic. */
    MUTED,
    /** Call finished; the engine is idle. */
    ENDED,
}

/**
 * Pure text helpers of the call. They live apart from [LiveCallEngine] because
 * they touch no Android API, so the JVM test suite locks them in for free
 * (see LiveCallTest).
 */
object LiveCallSpeech {

    /** Markdown-ish leftovers a model sometimes adds; never read them aloud. */
    private val MARKUP = Regex("[*_`#~]+")
    private val WHITESPACE = Regex("\\s+")
    /** Sentence ends a long reply may be cut at — English + Devanagari "।". */
    private val SENTENCE_ENDS = listOf(". ", "? ", "! ", "। ", "\n")

    /**
     * Turns a CHAT reply into something worth SPEAKING: the Private room marks
     * its tiny WhatsApp bubbles with "|||" (never read that aloud), and any
     * markdown is dropped. Long answers are cut at a whole sentence so the
     * persona never trails off mid-word on a call.
     */
    fun spokenText(raw: String): String {
        var text = raw.replace(AppConfig.PRIVATE_BUBBLE_SEPARATOR, ". ")
        text = MARKUP.replace(text, "")
        text = WHITESPACE.replace(text, " ").trim()
        return cutAtSentence(text, AppConfig.LIVE_CALL_MAX_SPOKEN_CHARS)
    }

    /** Cuts [text] to [limit] chars, preferring a whole sentence (never mid-word). */
    fun cutAtSentence(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val head = text.substring(0, limit)
        var end = -1
        for (marker in SENTENCE_ENDS) {
            val at = head.lastIndexOf(marker)
            if (at > end) end = at
        }
        if (end > limit / 3) return head.substring(0, end + 1).trim()
        val space = head.lastIndexOf(' ')
        return if (space > 0) head.substring(0, space).trim() else head.trim()
    }

    /**
     * The best transcription out of Android's candidate list: the recogniser
     * puts its most confident guess first, but a leading blank/one-letter junk
     * entry must not eat the turn.
     */
    fun bestResult(candidates: List<String>?): String {
        val usable = candidates?.firstOrNull { usableSpeech(it) }
        return usable?.trim().orEmpty()
    }

    /** Is this real speech (or just the recogniser's empty/1-char noise)? */
    fun usableSpeech(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return false
        return trimmed.any { it.isLetterOrDigit() }
    }

    /**
     * The first line the AI says when the call connects — gendered to match the
     * mate (boyfriend / boy-gay = raha, everyone else = rahi) and using the
     * user-given mate name when there is one.
     *
     * [privateMode] is the 18+ room: the opener is intimate (jaan) instead of the
     * friendly "Hello!", so the adult call does not start like a customer-care
     * line while the rest of the call is bold (see PersonaPrompts.privateCallRule).
     */
    fun greeting(persona: String, mateName: String, privateMode: Boolean = false): String {
        val name = mateName.trim().ifEmpty { PersonaPrompts.defaultName(persona) }
        // The ending is gendered AND the sentence is written in Devanagari: the
        // phone's Hindi voice reads Devanagari with the right pronunciation, while
        // the same sentence in Roman letters is read with English phonemes (see
        // LiveCallVoice.localeTagForText) — that mismatch is part of why the voice
        // sounded "ajeeb". The mate's own name stays exactly as the user wrote it.
        val ending = if (PersonaPrompts.isMaleMate(persona, name)) "रहा" else "रही"
        if (privateMode) {
            return "हम्म… हेलो जान। मैं $name बोल $ending हूँ। " +
                "आज इतनी रात को याद किया, बोलो ना — क्या कर रहे हो?"
        }
        return "हेलो! मैं $name बोल $ending हूँ। बोलो ना, क्या कर रहे हो?"
    }

    /**
     * What the AI says when the turn failed (network / quota) — keeps talking.
     * Written in Devanagari for the same reason as [greeting]: the phone's Hindi
     * voice reads Devanagari with the correct pronunciation.
     */
    fun troubleLine(): String = "एक सेकंड, नेटवर्क की दिक्कत है। फिर से बोलो ना?"

    /**
     * What the AI says when the turn failed because the AI ENGINE itself is out
     * of quota / overloaded — which is NOT a network problem. Saying "network ki
     * dikkat" for a daily-quota wall sent users off to check their Wi-Fi for
     * nothing, so the busy case gets its own honest line.
     */
    fun busyLine(): String = "अभी थोड़ा busy हूँ जान, एक मिनट रुको फिर बोलो ना?"

    /**
     * How long the engine waits before the mic opens again.
     *
     * A healthy call re-opens fast (a natural pause between two sentences), but a
     * WEDGED recogniser must not be retried at full speed: instant failures were
     * exactly the "click-click" loop where the mic restarted every second and the
     * AI never heard anything. So the extra wait GROWS with the number of
     * consecutive failures (and slightly with repeated silent turns), which turns
     * the burst into a calm pause the user can talk into.
     */
    fun micReopenDelayMs(micErrors: Int, silentTurns: Int): Long {
        val errorExtra = when {
            micErrors >= 4 -> 2400L
            micErrors >= 2 -> 1300L
            micErrors == 1 -> MIC_RETRY_EXTRA_MS
            else -> 0L
        }
        val silenceExtra = if (silentTurns >= 3) 600L else 0L
        return AppConfig.LIVE_CALL_REOPEN_MIC_DELAY_MS + maxOf(errorExtra, silenceExtra)
    }

    /** Extra pause after ONE failed mic session (a fast retry, not a hammer). */
    private const val MIC_RETRY_EXTRA_MS = 900L
}

/**
 * Mic + speaker half of a live call (see the file header). One instance per
 * activity; [start] on a new call, [stop] when it ends, [release] in onDestroy.
 */
class LiveCallEngine(
    context: Context,
    private val listener: Listener,
) {

    /** Callbacks the activity answers — all on the MAIN thread. */
    interface Listener {
        /** Turn/state changed (drives the "Sun rahi hoon…" status line). */
        fun onState(state: LiveCallState)
        /** What the AI HEARD: the user's own words, already transcribed. */
        fun onHeard(text: String)
        /** What the AI SAID: the line handed to the speaker. */
        fun onReply(text: String)
        /** Non-fatal trouble (mic hiccup, no TTS voice) — show it, keep going. */
        fun onProblem(message: String)
        /** The call cannot continue (permission revoked, no recogniser). */
        fun onFatal(message: String)
    }

    companion object {
        /** Logcat tag of the call: the chosen VOICE and its prosody are logged. */
        private const val TAG = "VibeMateCall"
        /** How long we wait for the TTS engine before opening the mic anyway. */
        private const val TTS_INIT_GRACE_MS = 2500L
        /** No TTS status parked yet. */
        private const val NO_TTS_STATUS = Int.MIN_VALUE
        /** Watching one spoken line: base wait + per-character budget. */
        private const val SPEAK_WATCHDOG_BASE_MS = 2500L
        private const val SPEAK_WATCHDOG_PER_CHAR_MS = 110L
        private const val SPEAK_WATCHDOG_MAX_MS = 30_000L

        /**
         * How long the phone's voice may talk before the engine assumes it went
         * silent WITHOUT reporting it, and opens the mic anyway.
         *
         * WHY THIS EXISTS: the mic may only re-open from the voice engine's
         * "finished" callback. An engine that dies mid-line (or never fires that
         * callback) would leave the mic shut for the rest of the call — the AI
         * "bol rahi hai" but never hears the user again. This budget is the
         * safety net that makes that dead-end impossible.
         */
        fun speechWatchdogBudgetMs(spoken: String): Long {
            val budget = SPEAK_WATCHDOG_BASE_MS + spoken.length * SPEAK_WATCHDOG_PER_CHAR_MS
            return budget.coerceAtMost(SPEAK_WATCHDOG_MAX_MS)
        }
    }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var active = false
    private var muted = false
    private var listening = false
    private var speaking = false
    private var state = LiveCallState.ENDED
    /** When the CURRENT listening turn opened (stuck-mic guard). */
    private var turnStartedAt = 0L
    private var pendingGreeting = ""
    private var pendingTtsStatus = NO_TTS_STATUS
    private var utteranceId = ""
    private var utteranceSeq = 0
    /**
     * How many times in a row the recogniser failed INSTANTLY (ERROR_CLIENT /
     * BUSY). A phone with a broken recogniser must not turn into a strobe light
     * at the mic: the re-open delay escalates and the language falls back (see
     * [nextMicDelay] / [fallBackLanguage]).
     */
    private var micErrors = 0
    /** Turns that ended with silence/no-match — the normal "no one spoke" case. */
    private var silentTurns = 0
    /** Index into [AppConfig.LIVE_CALL_LANGUAGE_CHAIN] currently in use. */
    private var languageIndex = 0
    /** The one-time "mic se kuch nahi aa raha" hint was already shown. */
    private var micHintShown = false

    // ---------------------------- the AI's VOICE ----------------------------
    /** The persona whose voice the call uses (see LiveCallVoice.profileFor). */
    private var persona = ""
    /**
     * The mate's custom name for THIS call. Only the gender-neutral FRIEND
     * persona reads it (its gender comes from the name — see
     * [PersonaPrompts.genderForName]); every other persona ignores it.
     */
    private var mateName = ""
    /** The voice the engine is speaking with right now ("" = engine default). */
    private var voiceName = ""
    /**
     * Voices that already FAILED on this call (a neural network voice with no
     * internet, a half-installed voice) — never picked again while the call runs,
     * so one dead voice cannot turn the whole call silent.
     */
    private val badVoices = mutableSetOf<String>()
    /** Id prefix of the pieces of the line being spoken ("vibemate-7-"). */
    private var utterancePrefix = ""
    /** A "hmm…" is playing — it is NOT the reply, so it must not open the mic. */
    private var fillerPlaying = false
    private var fillerIndex = 0
    /** Stream + volume every utterance is spoken with (see [applyVoice]). */
    private val speechParams = Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, AppConfig.LIVE_CALL_TTS_VOLUME)
    }

    private val openMicTask = Runnable { openMic() }

    /**
     * The little hesitation said while the model thinks (see [sayFiller]) — a real
     * partner does not go silent for three seconds either.
     */
    private val fillerTask = Runnable { sayFiller() }

    /**
     * How long to wait before the mic opens again. Healthy calls re-open fast
     * (a natural pause); after real errors the wait grows so the user hears a
     * calm pause instead of a click-click machine-gun. The maths lives in
     * [LiveCallSpeech.micReopenDelayMs] so the JVM tests lock it in.
     */
    private fun nextMicDelay(): Long =
        LiveCallSpeech.micReopenDelayMs(micErrors, silentTurns)

    /**
     * Moves to the next language in [AppConfig.LIVE_CALL_LANGUAGE_CHAIN] (hi-IN
     * -> en-IN -> phone default) and tells the user once why. A missing Hindi
     * pack used to mean the call heard NOTHING at all, forever.
     */
    private fun fallBackLanguage(): Boolean {
        if (languageIndex >= AppConfig.LIVE_CALL_LANGUAGE_CHAIN.lastIndex) return false
        languageIndex++
        // The recogniser caches the language of the running session, so the next
        // session must be a fresh one.
        releaseRecognizer()
        if (!micHintShown) {
            micHintShown = true
            listener.onProblem("Hindi mic support not found — listening in English mode")
        }
        return true
    }

    /**
     * WATCHDOG for one spoken line. Not every phone fires
     * UtteranceProgressListener.onDone (a switched-off voice, a killed engine,
     * a silent failure) — and if that callback never arrives, the mic would stay
     * closed forever and the call would look exactly like the old bug ("AI bolti
     * nahi / sunti nahi"). So after a generous time budget the call simply goes
     * back to listening.
     */
    private val speechWatchdogTask = Runnable {
        if (active && speaking) {
            val wasFiller = fillerPlaying
            speaking = false
            fillerPlaying = false
            utteranceId = ""
            if (wasFiller) {
                // A hum that never reported "done" is not worth a warning: the real
                // reply is still on its way and will simply speak over it.
                openMicSoon()
                return@Runnable
            }
            listener.onProblem("Voice stuck — turning the mic back on")
            openMicSoon()
        }
    }

    /** Is the user's mic muted right now? */
    val isMuted: Boolean get() = muted

    // ===================== call lifecycle =====================

    /**
     * Opens the call: the voice engine is prepared and [greeting] is the first
     * thing the AI says. As soon as it finishes speaking the mic opens by
     * itself, so the user can simply start talking.
     */
    fun start(greeting: String, persona: String = "", mateName: String = "") {
        if (active) return
        active = true
        // The persona decides the VOICE (a girlfriend must sound like a woman, a
        // boyfriend like a man — see LiveCallVoice.profileFor). The gender-neutral
        // FRIEND persona takes its gender from the mate's NAME, so the name travels
        // with the call too. A fresh call forgets the previous call's dead voices
        // so they get a new chance.
        this.persona = persona
        this.mateName = mateName
        badVoices.clear()
        voiceName = ""
        fillerIndex = 0
        muted = false
        listening = false
        speaking = false
        // Fresh call: forget the previous call's mic trouble (and start again
        // with the first language of the chain, hi-IN).
        micErrors = 0
        silentTurns = 0
        languageIndex = 0
        micHintShown = false
        pendingGreeting = greeting
        setState(LiveCallState.CONNECTING)

        if (ttsReady) {
            flushGreeting()
            return
        }
        prepareTts()
        // Safety net: a phone with a broken/slow TTS engine must still HEAR the
        // user. Never leave the call stuck on "Connecting…" (that was exactly
        // the "AI bolti hi nahi / sunti hi nahi" complaint).
        mainHandler.postDelayed({
            if (active && !ttsReady && !speaking) {
                listener.onProblem("Voice engine is slow — turning the mic on")
                flushGreeting()
            }
        }, TTS_INIT_GRACE_MS)
    }

    /** Ends the call: mic closed, speaking stopped. TTS stays warm for next time. */
    fun stop() {
        if (!active) return
        active = false
        pendingGreeting = ""
        // A pending or already playing "hmm…" must not outlive the call either.
        endFiller()
        speaking = false
        utteranceId = ""
        mainHandler.removeCallbacks(speechWatchdogTask)
        closeMic()
        try {
            tts?.stop()
        } catch (_: Exception) {
            // TTS already gone — nothing to stop.
        }
        releaseRecognizer()
        setState(LiveCallState.ENDED)
    }

    /** Frees the speech engines (onDestroy). The engine cannot be reused after. */
    fun release() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
            // already shut down
        }
        tts = null
        ttsReady = false
    }

    /** Mute/unmute the microphone. Muting does NOT stop the AI from talking. */
    fun setMuted(value: Boolean) {
        muted = value
        if (!active) return
        if (muted) {
            closeMic()
            setState(LiveCallState.MUTED)
        } else {
            openMicSoon(200L)
        }
    }

    /**
     * Back to listening without the AI saying anything (used when a turn failed:
     * the activity says the trouble line itself, then calls this).
     */
    fun resumeListening() {
        if (!active) return
        // A hesitation that is still playing is NOT a reply: cut it first, or its
        // own "speaking" flag would keep the mic shut for the rest of the call.
        endFiller()
        if (speaking) return
        if (muted) {
            setState(LiveCallState.MUTED)
            return
        }
        openMicSoon()
    }

    // ===================== speaker (the AI's voice) =====================

    /**
     * Speaks one AI reply and re-opens the mic when the voice finishes. The
     * text is cleaned first (see [LiveCallSpeech.spokenText]) so "|||" markers
     * and markdown are never read out loud.
     */
    fun speakReply(text: String) {
        if (!active) return
        val spoken = LiveCallSpeech.spokenText(text)
        if (spoken.isEmpty()) {
            resumeListening()
            return
        }
        listener.onReply(spoken)
        speak(spoken)
    }

    private fun flushGreeting() {
        val hello = pendingGreeting
        pendingGreeting = ""
        if (hello.isEmpty()) {
            // This runs again when the voice engine finally reports ready, which
            // may be AFTER the grace period already opened the mic — so never
            // touch a session that is already listening.
            if (!listening) openMicSoon()
        } else {
            listener.onReply(hello)
            speak(hello)
        }
    }

    /** Speaks [line] and then resumes listening (mic stays closed meanwhile). */
    private fun speak(line: String) {
        // Whatever hesitation was covering the thinking pause ends here: the real
        // answer flushes it away (QUEUE_FLUSH below).
        endFiller()
        closeMic()
        setState(LiveCallState.SPEAKING)
        val engine = tts
        if (engine == null || !ttsReady) {
            // Listen-only call: the voice may be missing on this phone, but the
            // AI must still HEAR the user — so the mic opens again right away.
            listener.onProblem("This phone has no voice (TTS) — turning the mic on")
            speaking = false
            openMicSoon()
            return
        }
        speaking = true
        // Pick the voice for THIS line (Devanagari -> Hindi, Latin -> Hinglish /
        // English) and set its prosody — see LiveCallVoice and applyVoice.
        applyVoice(line, engine)
        if (!queue(line, TextToSpeech.QUEUE_FLUSH)) {
            // The voice refused (missing data / engine died): keep the call alive.
            speaking = false
            utteranceId = ""
            listener.onProblem("Voice could not start — turning the mic on")
            openMicSoon()
            return
        }
        armSpeechWatchdog(line)
    }

    /**
     * Hands [line] to the speaker in ONE or TWO pieces; false when the engine
     * refused the utterance.
     *
     * WHY PIECES: the first piece is synthesised — and starts playing — while the
     * second one is still being built, so the AI begins talking noticeably sooner,
     * and the joint between the two is a natural breath instead of one endless
     * sentence read at a machine's pace. [mode] is TextToSpeech.QUEUE_FLUSH for a
     * fresh line (anything still queued is dropped) and QUEUE_ADD for the tail of
     * the same line, which must NOT cut the head off.
     */
    private fun queue(line: String, mode: Int): Boolean {
        val engine = tts ?: return false
        val pieces = LiveCallVoice.splitForSpeech(line)
        utteranceSeq++
        utterancePrefix = "vibemate-$utteranceSeq-"
        for ((index, piece) in pieces.withIndex()) {
            if (piece.isEmpty()) continue
            val id = utterancePrefix + index
            utteranceId = id
            val result = engine.speak(
                piece,
                if (index == 0) mode else TextToSpeech.QUEUE_ADD,
                speechParams,
                id,
            )
            if (result == TextToSpeech.ERROR) return false
        }
        return utteranceId.isNotEmpty()
    }

    /**
     * Says one tiny "hmm…" while the model is still writing the reply.
     *
     * WHY: a real partner does not leave the line dead silent for three seconds
     * and then suddenly start a sentence — that silence-then-robot delivery was a
     * big part of why the call did not feel natural. The mic stays closed (the
     * user has just finished their sentence) and the reply itself flushes the hum
     * away the moment it arrives.
     */
    private fun sayFiller() {
        if (!active || speaking || muted || state != LiveCallState.THINKING) return
        val engine = tts
        if (engine == null || !ttsReady) return
        val line = LiveCallVoice.filler(fillerIndex++)
        if (line.isEmpty()) return
        fillerPlaying = true
        speaking = true
        applyVoice(line, engine)
        if (!queue(line, TextToSpeech.QUEUE_FLUSH)) {
            fillerPlaying = false
            speaking = false
            utteranceId = ""
            return
        }
        // The watchdog covers a hum whose "done" never arrives, too.
        armSpeechWatchdog(line)
    }

    /** Arms the thinking-pause timer (the reply itself cancels it — see [endFiller]). */
    private fun armFiller() {
        mainHandler.removeCallbacks(fillerTask)
        if (AppConfig.LIVE_CALL_FILLER_DELAY_MS > 0L) {
            mainHandler.postDelayed(fillerTask, AppConfig.LIVE_CALL_FILLER_DELAY_MS)
        }
    }

    /**
     * Forgets a pending hesitation and, when one is playing right now, silences it
     * (and reports the line as free again). Called before every real reply, when
     * the mic goes back to listening, when the call ends — and by the watchdog.
     */
    private fun endFiller() {
        mainHandler.removeCallbacks(fillerTask)
        if (!fillerPlaying) return
        fillerPlaying = false
        speaking = false
        utteranceId = ""
        try {
            tts?.stop()
        } catch (_: Exception) {
            // engine already gone — nothing to silence
        }
    }

    /** Never wait for "onDone" forever — see [speechWatchdogTask]. */
    private fun armSpeechWatchdog(line: String) {
        mainHandler.removeCallbacks(speechWatchdogTask)
        val budget = SPEAK_WATCHDOG_BASE_MS + line.length * SPEAK_WATCHDOG_PER_CHAR_MS
        mainHandler.postDelayed(speechWatchdogTask, minOf(budget, SPEAK_WATCHDOG_MAX_MS))
    }

    /** Builds the TTS engine once; the voice is configured in the init callback. */
    private fun prepareTts() {
        if (tts != null) return
        val engine = TextToSpeech(appContext, TextToSpeech.OnInitListener { status ->
            onTtsInit(status)
        })
        tts = engine
        // Defensive: on a few devices the init callback lands BEFORE the field
        // assignment above, in which case the status was parked — replay it.
        if (pendingTtsStatus != NO_TTS_STATUS) {
            val parked = pendingTtsStatus
            pendingTtsStatus = NO_TTS_STATUS
            onTtsInit(parked)
        }
    }

    /** Voice engine answered: ready -> speak; missing -> keep the call listening. */
    private fun onTtsInit(status: Int) {
        if (tts == null) {
            pendingTtsStatus = status
            return
        }
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            // Stream, volume and the "line finished" callback — the voice itself
            // is picked per line (see applyVoice) because a reply can be Hindi
            // (Devanagari) or Hinglish (Latin) and each needs its own voice.
            installVoiceSettings()
        } else {
            ttsReady = false
            listener.onProblem("Voice engine is not installed — I can only listen")
        }
        flushGreeting()
    }

    /**
     * One-time engine setup: where the voice comes out and who tells us it is done.
     *
     * THE STREAM MATTERS: TextToSpeech defaults to the *accessibility* usage, which
     * several ROMs (ColorOS / realme included) scale and process differently — so
     * the AI could sound thin, far away or plain odd next to the call screen. As
     * plain SPEECH on the MEDIA stream it is simply normal audio, at full volume,
     * and the completion callback is what opens the mic again after a line.
     */
    private fun installVoiceSettings() {
        val engine = tts ?: return
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                onSpeechFinished(id)
            }

            @Deprecated("kept for older Android versions")
            override fun onError(id: String?) {
                onSpeechFailed(id, -1)
            }

            override fun onError(id: String?, errorCode: Int) {
                onSpeechFailed(id, errorCode)
            }
        })
    }

    /**
     * Chooses the VOICE for [line] and applies it (voice + rate + pitch).
     *
     * THE FIX for the robotic, "ajeeb" sound: before this the engine was only told
     * `setLanguage(hi-IN)` and never got a voice, so Android spoke with its own
     * default — the low-quality LOCAL voice on almost every phone — while the pitch
     * was pushed +8% on top of it. Now the best voice for the language of THIS line
     * is picked (gender-matched to the persona, neural voices preferred, see
     * [LiveCallVoice]) and the pitch is only shifted when that voice cannot give the
     * persona's gender.
     *
     * ORDER MATTERS: setLanguage() installs the engine default for the language, so
     * the chosen voice must be assigned AFTER it to survive.
     */
    private fun applyVoice(line: String, engine: TextToSpeech) {
        val tags = listOf(
            LiveCallVoice.localeTagForText(line),
            AppConfig.LIVE_CALL_LANGUAGE,
            AppConfig.LIVE_CALL_LANGUAGE_FALLBACK,
        ).distinct()
        for (tag in tags) {
            val result = try {
                engine.setLanguage(Locale.forLanguageTag(tag))
            } catch (_: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                break
            }
        }
        val profile = LiveCallVoice.profileFor(persona, mateName)
        val voices: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
        if (voices.isEmpty()) {
            // Some engines publish no voice table at all: keep the language above
            // and only apply the prosody (the old behaviour, minus the pitch hack).
            voiceName = ""
            engine.setSpeechRate(profile.rate)
            engine.setPitch(LiveCallVoice.pitchFor(profile, null))
            return
        }
        val chosen = LiveCallVoice.pick(
            voices.map { LiveCallVoice.info(it) },
            tags,
            profile.gender,
            badVoices,
        )
        val voice = chosen?.let { LiveCallVoice.voiceFor(voices, it.name) }
        voiceName = voice?.name.orEmpty()
        if (voice != null) {
            try {
                engine.voice = voice
            } catch (_: Exception) {
                // An engine that refuses this voice keeps its language default.
            }
        }
        val pitch = LiveCallVoice.pitchFor(profile, chosen?.let { LiveCallVoice.genderOfName(it.name) })
        engine.setSpeechRate(profile.rate)
        engine.setPitch(pitch)
        if (voice != null) {
            Log.i(
                TAG,
                "voice=${voice.name} tag=${voice.locale?.toLanguageTag()} " +
                    "q=${voice.quality} net=${voice.isNetworkConnectionRequired} " +
                    "rate=${profile.rate} pitch=$pitch " +
                    "appliedVoice=${engine.voice?.name}",
            )
        }
    }

    /** The AI stopped talking (the whole line is done) -> the user's turn again. */
    private fun onSpeechFinished(id: String?) {
        if (!active) return
        // A line is spoken in one or two PIECES and a FLUSHED line reports "done"
        // as well, so only the last piece of the current line may open the mic —
        // everything else is either an older piece or a line that was replaced.
        if (id != null && id != utteranceId) return
        mainHandler.removeCallbacks(speechWatchdogTask)
        speaking = false
        utteranceId = ""
        if (fillerPlaying) {
            // That was only the "hmm…": the real reply is still being written, so
            // the line stays quiet and the mic stays shut until it arrives.
            fillerPlaying = false
            return
        }
        if (muted) {
            setState(LiveCallState.MUTED)
            return
        }
        openMicSoon()
    }

    /**
     * The picked voice could not speak (a neural voice with no internet, a voice
     * pack uninstalled mid-call, a wedged engine).
     *
     * The voice is BLACKLISTED for the rest of the call — the next line picks the
     * next best one, so a single dead voice can never turn the call silent — and
     * the mic re-opens right away so the user is not left talking into nothing.
     */
    private fun onSpeechFailed(id: String?, errorCode: Int) {
        if (!active) return
        // Ignore an old/flushed utterance: only the line being spoken now counts.
        if (id != null && (utterancePrefix.isEmpty() || !id.startsWith(utterancePrefix))) return
        mainHandler.removeCallbacks(speechWatchdogTask)
        val wasFiller = fillerPlaying
        speaking = false
        fillerPlaying = false
        utteranceId = ""
        if (voiceName.isNotEmpty()) {
            badVoices += voiceName
            Log.w(TAG, "voice failed (code=$errorCode) — blacklisted $voiceName for this call")
        }
        if (wasFiller) return
        if (muted) {
            setState(LiveCallState.MUTED)
            return
        }
        openMicSoon()
    }

    // ===================== microphone (the AI's ears) =====================

    /** Opens the mic for one turn (a no-op while muted, busy listening or talking). */
    private fun openMic() {
        if (!active || speaking) return
        // NEVER start a second recognition session: a phone recogniser can only
        // have one, and starting another kills the user's sentence mid-way.
        if (listening) return
        if (muted) {
            setState(LiveCallState.MUTED)
            return
        }
        val rec = ensureRecognizer() ?: return
        listening = true
        turnStartedAt = System.currentTimeMillis()
        setState(LiveCallState.LISTENING)
        try {
            rec.startListening(recognizerIntent())
        } catch (busy: Exception) {
            // startListening() throws when the recogniser is still busy with the
            // previous turn — retry, but let the wait grow if it keeps throwing
            // (that burst was part of the mic click-click loop).
            listening = false
            micErrors++
            openMicSoon(nextMicDelay())
        }
    }

    /** [delay] ms from now the mic opens again (also cancels a pending open). */
    private fun openMicSoon(delay: Long = AppConfig.LIVE_CALL_REOPEN_MIC_DELAY_MS) {
        if (!active) return
        mainHandler.removeCallbacks(openMicTask)
        mainHandler.postDelayed(openMicTask, delay)
    }

    /** Closes the mic — ALWAYS before speaking, so the AI never records itself. */
    private fun closeMic() {
        mainHandler.removeCallbacks(openMicTask)
        if (!listening) return
        listening = false
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
            // recogniser already gone
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onFatal("This phone has no speech recognition")
            return null
        }
        return try {
            val rec = SpeechRecognizer.createSpeechRecognizer(appContext)
            rec.setRecognitionListener(recognitionListener)
            recognizer = rec
            rec
        } catch (e: Exception) {
            listener.onFatal("Could not start the mic: " + (e.message ?: "unknown"))
            null
        }
    }

    private fun releaseRecognizer() {
        val rec = recognizer ?: return
        recognizer = null
        listening = false
        try {
            rec.cancel()
            rec.destroy()
        } catch (_: Exception) {
            // already destroyed
        }
    }

    /**
     * What the mic listens to: free-form Hindi/Hinglish with short silence gaps.
     *
     * TYPE SAFETY IS THE WHOLE POINT HERE: the millisecond extras MUST go in as
     * `long` (they were sent as `Int` and the recogniser's getLongExtra() threw
     * ClassCastException, which killed every session instantly -> the endless
     * mic click-click with no reply). [currentLanguage] also lets the engine
     * fall back to another language when a phone has no Hindi pack.
     */
    private fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        val language = AppConfig.LIVE_CALL_LANGUAGE_CHAIN
            .getOrElse(languageIndex) { AppConfig.LIVE_CALL_LANGUAGE }
        // "" means: don't force a language, let the phone use its own default.
        if (language.isNotEmpty()) {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
        }
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        // A phone call is a fast back-and-forth: end the turn on a short pause
        // instead of making the user wait for the default (long) silence gap.
        // long, long, long — see the note above.
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
            AppConfig.LIVE_CALL_SILENCE_MS,
        )
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            AppConfig.LIVE_CALL_POSSIBLE_SILENCE_MS,
        )
        putExtra(
            RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
            AppConfig.LIVE_CALL_MINIMUM_LENGTH_MS,
        )
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) = Unit

        /** THE moment the AI hears the user: words in -> AI turn out. */
        override fun onResults(results: Bundle?) {
            listening = false
            if (!active || muted) return
            val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val heard = LiveCallSpeech.bestResult(candidates)
            if (heard.isEmpty() || System.currentTimeMillis() - turnStartedAt >
                AppConfig.LIVE_CALL_MAX_LISTEN_MS
            ) {
                // Nothing usable (noise / silence / stuck mic) -> listen again,
                // but slow down a little if it keeps happening (no click-storm).
                silentTurns++
                openMicSoon(nextMicDelay())
                return
            }
            // Real words arrived: the mic stack is healthy, so forget the errors.
            micErrors = 0
            silentTurns = 0
            setState(LiveCallState.THINKING)
            // The model needs a moment: if it is slow, cover the silence with a
            // tiny "hmm…" (the reply itself cuts it off — see sayFiller/endFiller).
            armFiller()
            listener.onHeard(heard)
        }

        /**
         * A recogniser failure. Instant failures (ERROR_CLIENT / BUSY) are the
         * dangerous ones: retrying them at full speed was the mic click-click
         * loop with no reply, so they ESCALATE — longer waits, then a language
         * fallback — instead of hammering the mic.
         */
        override fun onError(error: Int) {
            listening = false
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    listener.onFatal("Microphone permission was turned off — ending the call")
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Silence is normal in a call: the user simply did not speak.
                    silentTurns++
                    openMicSoon(nextMicDelay())
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    micErrors++
                    // Rebuild the wedged session; if the SAME failure keeps coming
                    // back, the language pack is the likely culprit.
                    releaseRecognizer()
                    if (micErrors >= 2 && !micHintShown && fallBackLanguage()) {
                        micHintShown = true
                    } else if (micErrors >= 2 && !micHintShown) {
                        micHintShown = true
                        listener.onProblem(
                            "No sound is coming from the mic — check the phone's " +
                                "voice search / speech service"
                        )
                    }
                    openMicSoon(nextMicDelay())
                }
                else -> {
                    micErrors++
                    openMicSoon(nextMicDelay())
                }
            }
        }
    }

    private fun setState(next: LiveCallState) {
        if (state == next) return
        state = next
        listener.onState(next)
    }
}


