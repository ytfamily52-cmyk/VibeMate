package com.example.vibemate

import android.speech.tts.Voice

/**
 * Who the mate is. A girlfriend must sound like a woman on the phone — before
 * this file NO voice was ever chosen (only `setLanguage("hi-IN")`), so Android's
 * engine picked its own default and the only thing that made it sound female was
 * a pitch bump on top of it (see [LiveCallVoice.pitchFor]).
 */
enum class MateGender { FEMALE, MALE }

/**
 * How one persona SPEAKS: the timbre it should have and the prosody it uses.
 *
 * [rate] is the phone's own speech speed (1.0 = natural talk, below 1 drags and
 * sounds like an IVR menu) and [pitch] is only the *fine* tweak that separates
 * two personas that share the same voice — the gender itself now comes from a
 * real, gender-matching [Voice] whenever the phone has one.
 */
data class VoiceProfile(val gender: MateGender, val rate: Float, val pitch: Float)

/**
 * One Android [Voice] flattened into plain data.
 *
 * WHY A COPY: on a live call the picked voice decides whether the AI sounds like
 * a person or like a robot — and that ranking must be testable WITHOUT a phone.
 * [Voice] itself cannot be built on the JVM and its getters talk to a service,
 * so [LiveCallVoice] ranks these snapshots instead (the adapter is
 * [LiveCallVoice.info]).
 */
data class VoiceInfo(
    val name: String,
    val localeTag: String,
    val quality: Int,
    val latency: Int,
    val needsNetwork: Boolean,
    val features: Set<String> = emptySet(),
)

/**
 * VOICE SELECTION for the live call — the "why does it sound weird?" file.
 *
 * The call used to sound robotic for a very concrete reason: the engine only
 * called `setLanguage(hi-IN)` and never chose a VOICE. Android then speaks with
 * the engine's default for that language, which on almost every phone is the
 * low-quality LOCAL voice, and the code tried to make it sound like a young
 * woman by raising the pitch by 8% on top of it — which is exactly the squeaky,
 * artificial "ajeeb" sound. The good neural ("network") voices Google offers
 * were never selected at all.
 *
 * What this file does instead:
 *   1. [pick] ranks every voice the phone offers: the language the reply needs
 *      first, then a gender match for the persona, then the best quality and the
 *      neural (network) voices over the local ones.
 *   2. [pitchFor] only touches the pitch when the picked voice could NOT give
 *      the right gender — a real female voice is spoken at its own pitch.
 *   3. [localeTagForText] sends Devanagari text to the Hindi voice and Latin
 *      (Hinglish / English) text to the language in AppConfig, so Hindi words are
 *      not mangled by English phonemes and vice versa.
 *   4. [splitForSpeech] hands a SHORT first piece to the voice, so the AI starts
 *      talking while the rest of the line is still being synthesised (that is
 *      what makes the turn feel fast instead of "…thinking…").
 *
 * Everything here is pure logic (no Android call except the [info] adapter), so
 * LiveCallVoiceTest locks the ranking down on the JVM and
 * LiveCallVoiceDeviceTest prints the REAL voice table of the phone.
 */
object LiveCallVoice {

    /** Devanagari block: what the hi-IN recogniser answers in. */
    private val DEVANAGARI = '\u0900'..'\u097F'

    /** "…" is read as a tiny breath by every engine — a natural hesitation. */
    const val HESITATION = "\u2026"

    /** A score of this means "never use this voice" (wrong language). */
    const val UNUSABLE: Int = Int.MIN_VALUE

    /**
     * The feature Google's network (= neural, cloud) voices advertise. The
     * platform constant for it is deprecated, so the plain string is used here.
     */
    private const val FEATURE_NETWORK_SYNTHESIS = "networkSynthesis"

    /**
     * MEASURED ON A REAL PHONE (realme RMX3085, Google TTS, 473 voices): plain
     * `setLanguage("hi-IN")` hands out the voice literally named "hi-IN-language",
     * whose one special feature is [FEATURE_LEGACY_VOICE] — the OLD pre-neural
     * voice of the language. THAT is the robot the call was speaking with, while
     * "hi-in-x-hia/hic/hid/hie-network" (real, neural voices) were never looked at.
     */
    private const val FEATURE_LEGACY_VOICE = "legacySetLanguageVoice"

    /** The engine's own marker for "this voice's data is not on the phone". */
    private const val FEATURE_NOT_INSTALLED = "notInstalled"

    private const val LEGACY_VOICE_PENALTY = 10_000
    private const val NOT_INSTALLED_PENALTY = 200

    /**
     * Language beats everything else: one tier is far bigger than the whole
     * quality + gender + network budget below, so the FIRST language the caller
     * asked for always wins and the next one is only a fallback.
     */
    private const val LANGUAGE_TIER = 100_000
    private const val EXACT_REGION_BONUS = 400
    private const val LANGUAGE_ONLY_BONUS = 100
    private const val GENDER_MATCH_BONUS = 90
    private const val GENDER_MISMATCH_PENALTY = 120
    private const val NETWORK_VOICE_BONUS = 70

    /** Where a long line may be cut into two spoken pieces. */
    private val SENTENCE_END = listOf(". ", "? ", "! ", "\u0964 ", "\n")
    private val CLAUSE_END = listOf(", ", "; ", ": ")

    // ===================== the text -> language decision =====================

    /** How much of [text] is written in Devanagari (0f = pure Latin). */
    fun devanagariRatio(text: String): Float {
        var devanagari = 0
        var latin = 0
        for (c in text) {
            when {
                // Devanagari letters AND the matras / anusvara that hang off them
                // (a matra is not a "letter", so counting letters alone would
                // under-read a Hindi word like "में" as a single character).
                c in DEVANAGARI -> devanagari++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }
        val total = devanagari + latin
        return if (total == 0) 0f else devanagari.toFloat() / total
    }

    /**
     * Is this reply written in Devanagari? ANY Devanagari wins: the Hindi voice
     * reads the English loan words in "कल office गई थी" perfectly well, while an
     * English voice cannot read a single Devanagari word — so one Hindi word is
     * enough to make the whole line Hindi.
     */
    fun isDevanagariText(text: String): Boolean = devanagariRatio(text) > 0f

    /**
     * The language to SPEAK [text] in.
     *
     * A Hindi reply must not be read by an English voice (Hindi words come out
     * with English phonemes) and an English reply must not be read by the Hindi
     * voice — that mismatch was a big part of the "sound ajeeb lag raha hai"
     * complaint. Latin text goes to [AppConfig.LIVE_CALL_TTS_LATIN_TAG], which is
     * the knob to tune for Hinglish.
     */
    fun localeTagForText(text: String): String =
        if (isDevanagariText(text)) AppConfig.LIVE_CALL_LANGUAGE else AppConfig.LIVE_CALL_TTS_LATIN_TAG

    // ===================== the voice table =====================

    /** Android [Voice] -> the plain snapshot the ranking works on. */
    fun info(voice: Voice): VoiceInfo = VoiceInfo(
        name = voice.name.orEmpty(),
        localeTag = voice.locale?.toLanguageTag().orEmpty(),
        quality = voice.quality,
        latency = voice.latency,
        needsNetwork = voice.isNetworkConnectionRequired,
        features = voice.features.orEmpty(),
    )

    /** The [Voice] with this name, or null when the engine no longer offers it. */
    fun voiceFor(voices: Set<Voice>, name: String): Voice? =
        voices.firstOrNull { it.name == name }

    /** Does this voice name say whether it is a female or a male voice? */
    fun genderOfName(name: String): MateGender? {
        val lower = name.lowercase()
        // Google's voice ids end in "#female_1" / "#male_1" where both exist;
        // when a name says nothing, the caller fakes the gender with pitch
        // (see [pitchFor]) instead of guessing it from the name.
        if (lower.contains("#female")) return MateGender.FEMALE
        if (lower.contains("#male")) return MateGender.MALE
        // "female" contains "male", so this list must be tested first (it is).
        if (listOf("female", "-f-", "_f_", "-fem").any { lower.contains(it) }) return MateGender.FEMALE
        if (listOf("male", "-m-", "_m_", "-mal").any { lower.contains(it) }) return MateGender.MALE
        return null
    }

    /**
     * The gender a persona must have — the same rule the greetings use. The
     * gender-neutral FRIEND persona takes its gender from [mateName] (a girl's
     * name -> a female voice, see [PersonaPrompts.genderForName]); every other
     * persona has a fixed gender and ignores the name.
     */
    fun genderOfPersona(persona: String, mateName: String = ""): MateGender =
        if (PersonaPrompts.isMaleMate(persona, mateName)) MateGender.MALE else MateGender.FEMALE

    /**
     * How good [voice] is for a reply that has to be spoken in one of
     * [wantedTags] (IN ORDER — the first match always beats the next one).
     *
     * [UNUSABLE] when the voice speaks an unrelated language at all.
     */
    fun score(voice: VoiceInfo, wantedTags: List<String>, gender: MateGender): Int {
        val index = wantedTags.indexOfFirst { languageMatches(voice.localeTag, it) }
        if (index < 0) return UNUSABLE
        val wanted = wantedTags[index]
        var score = (wantedTags.size - index) * LANGUAGE_TIER
        score += if (voice.localeTag.equals(wanted, ignoreCase = true)) {
            EXACT_REGION_BONUS
        } else {
            LANGUAGE_ONLY_BONUS
        }
        score += qualityScore(voice.quality)
        score += latencyScore(voice.latency)
        if (voice.features.contains(FEATURE_LEGACY_VOICE)) {
            // The old pre-neural voice of the language (see FEATURE_LEGACY_VOICE):
            // it may only be used when the phone has literally nothing else.
            score -= LEGACY_VOICE_PENALTY
        }
        if (voice.features.contains(FEATURE_NOT_INSTALLED)) {
            // The engine says the voice data is not downloaded: it can be picked,
            // but only as a last resort (and the engine blacklists it if it fails).
            score -= NOT_INSTALLED_PENALTY
        }
        score += when (genderOfName(voice.name)) {
            gender -> GENDER_MATCH_BONUS
            null -> 0
            else -> -GENDER_MISMATCH_PENALTY
        }
        if (voice.needsNetwork || voice.features.contains(FEATURE_NETWORK_SYNTHESIS)) {
            // The cloud voices are the neural ones — that is where "natural"
            // lives. They are also the only voices a phone can lose (offline):
            // the engine therefore BLACKLISTS a voice that failed and picks
            // again instead of keeping a dead voice (see LiveCallEngine.pickVoice).
            score += if (AppConfig.LIVE_CALL_TTS_PREFER_NEURAL_VOICES) {
                NETWORK_VOICE_BONUS
            } else {
                -NETWORK_VOICE_BONUS
            }
        }
        return score
    }

    /**
     * The best voice for [wantedTags] (in order) out of everything the engine
     * offers. [exclude] keeps a voice that already failed on this call out of the
     * running; the result is null when the phone has no usable voice at all.
     */
    fun pick(
        voices: List<VoiceInfo>,
        wantedTags: List<String>,
        gender: MateGender,
        exclude: Set<String> = emptySet(),
    ): VoiceInfo? = voices
        .asSequence()
        .filter { it.name.isNotEmpty() && it.name !in exclude }
        .map { it to score(it, wantedTags, gender) }
        .filter { it.second != UNUSABLE }
        .maxByOrNull { it.second }
        ?.first

    /** Same language? ("hi-IN" vs "hi-in" vs "hi" all match a wanted "hi-IN".) */
    fun languageMatches(voiceTag: String, wantedTag: String): Boolean {
        if (wantedTag.isEmpty() || voiceTag.isEmpty()) return false
        val voice = voiceTag.lowercase()
        val wanted = wantedTag.lowercase()
        if (voice == wanted) return true
        return voice.substringBefore('-') == wanted.substringBefore('-')
    }

    private fun qualityScore(quality: Int): Int = when (quality) {
        Voice.QUALITY_VERY_HIGH -> 120
        Voice.QUALITY_HIGH -> 60
        Voice.QUALITY_NORMAL -> 0
        Voice.QUALITY_LOW -> -80
        Voice.QUALITY_VERY_LOW -> -160
        else -> 0
    }

    /** A snappy voice keeps a phone call a phone call. */
    private fun latencyScore(latency: Int): Int = when (latency) {
        Voice.LATENCY_VERY_LOW -> 25
        Voice.LATENCY_LOW -> 12
        Voice.LATENCY_NORMAL -> 0
        Voice.LATENCY_HIGH -> -10
        Voice.LATENCY_VERY_HIGH -> -20
        else -> 0
    }

    // ===================== prosody =====================

    /** The persona's speaking profile (gender + the two prosody knobs). */
    fun profileFor(persona: String, mateName: String = ""): VoiceProfile =
        when (genderOfPersona(persona, mateName)) {
        MateGender.MALE -> VoiceProfile(
            gender = MateGender.MALE,
            rate = AppConfig.LIVE_CALL_TTS_RATE,
            pitch = AppConfig.LIVE_CALL_TTS_MALE_PITCH,
        )
        MateGender.FEMALE -> VoiceProfile(
            gender = MateGender.FEMALE,
            rate = AppConfig.LIVE_CALL_TTS_RATE,
            pitch = AppConfig.LIVE_CALL_TTS_FEMALE_PITCH,
        )
    }

    /**
     * The pitch to speak at, given what the PICKED voice itself sounds like.
     *
     * This is the fix for the squeaky, "ajeeb" voice: on a phone that has a
     * woman's voice the base pitch is used unchanged, and only a voice of the
     * wrong gender (or an anonymous one) is shifted — the exact opposite of the
     * old code, which always pushed the pitch up by 8% on top of a female voice.
     */
    fun pitchFor(profile: VoiceProfile, spokenBy: MateGender?): Float {
        val pitch = when {
            spokenBy == null ->
                if (profile.gender == MateGender.FEMALE) profile.pitch * 1.15f else profile.pitch * 0.88f
            spokenBy == profile.gender -> profile.pitch
            profile.gender == MateGender.FEMALE -> profile.pitch * 1.28f
            else -> profile.pitch * 0.78f
        }
        // TextToSpeech refuses anything outside 0.5 .. 2.0.
        return pitch.coerceIn(0.5f, 2.0f)
    }

    // ===================== turn taking =====================

    /**
     * Splits a reply into the pieces the speaker gets: a SHORT head first (the AI
     * starts talking while the rest is still being synthesised) and then the
     * remainder, cut at a whole sentence / clause — never mid-word, because a
     * phone voice that stops mid-word is the "beech me kat gayi" complaint.
     */
    fun splitForSpeech(
        text: String,
        firstChunkChars: Int = AppConfig.LIVE_CALL_SPEECH_CHUNK_CHARS,
    ): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= firstChunkChars) return listOf(trimmed)
        val head = trimmed.substring(0, firstChunkChars)
        var cut = lastBoundary(head, SENTENCE_END) { it >= firstChunkChars / 3 }
        if (cut <= 0) cut = lastBoundary(head, CLAUSE_END) { true }
        if (cut <= 0) cut = head.lastIndexOf(' ')
        if (cut <= 0) return listOf(trimmed)
        val first = trimmed.substring(0, cut + 1).trim()
        val rest = trimmed.substring(cut + 1).trim()
        if (first.isEmpty()) return listOf(trimmed)
        return if (rest.isEmpty()) listOf(first) else listOf(first, rest)
    }

    /** Last position of any marker of [markers] that also satisfies [accept]. */
    private fun lastBoundary(
        head: String,
        markers: List<String>,
        accept: (Int) -> Boolean,
    ): Int {
        var best = -1
        for (marker in markers) {
            val at = head.lastIndexOf(marker)
            if (at > best && accept(at)) best = at
        }
        return best
    }

    /**
     * A tiny hesitation the engine says when the model is slow, so the line never
     * goes dead silent while the AI "thinks" — a real partner hums or says "hmm…"
     * too. [index] rotates through [AppConfig.LIVE_CALL_FILLERS] so the same hum
     * never repeats twice in a row.
     */
    fun filler(index: Int): String {
        val fillers = AppConfig.LIVE_CALL_FILLERS
        if (fillers.isEmpty()) return ""
        val safe = ((index % fillers.size) + fillers.size) % fillers.size
        return fillers[safe]
    }
}
