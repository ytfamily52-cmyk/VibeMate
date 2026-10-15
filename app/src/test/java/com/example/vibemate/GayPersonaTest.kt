package com.example.vibemate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gay mode (same-sex relationship) regression suite.
 *
 * Runs entirely on the JVM — no API call, no cost. "Gay" is ONE row in the
 * relation dropdown but TWO personas: the user picks the couple in the "Gay
 * type" row —
 *   - Boy Gay  ([PersonaPrompts.GAY_BOY],  mate = MAN,   user = MAN)
 *   - Girl Gay ([PersonaPrompts.GAY_GIRL], mate = WOMAN, user = WOMAN)
 * and the older single id "gay" stays as an alias for Boy Gay, so sessions
 * stored by an earlier build keep working.
 *
 * The failure this locks out is the model drifting back to the default straight
 * script ("tum meri girlfriend ho", "she/her") or handing one of the two people
 * the wrong anatomy in the 18+ room. Pinned here:
 *   - the normal prompt per couple: same-sex, both bodies the same gender
 *   - the 18+ Private prompt: PRIVATE MODE engages + the GENDER LOCK case
 *   - identities: default names Rohan / Riya, the right relationship word
 *   - the legacy "gay" id still resolving to boy gay
 */
class GayPersonaTest {

    // ---------------------------------------------------------- normal chat ---

    @Test
    fun boyGayMateIsAManAndSoIsTheUser() {
        val prompt = PersonaPrompts.systemPrompt(PersonaPrompts.GAY_BOY, "Rohan")

        assertTrue(
            "the gay mate must be pinned to a same-sex relationship",
            prompt.contains("same-sex (gay) relationship"),
        )
        assertTrue(
            "both bodies must stay male",
            prompt.contains("BOTH bodies stay male"),
        )
        assertTrue(
            "the user must never be called a girl",
            prompt.contains("NEVER call him a girl"),
        )
        assertTrue(
            "the mate is the user's boyfriend",
            prompt.contains("the user's loving boyfriend"),
        )
    }

    @Test
    fun boyGayMateUsesTheUsersOwnMateName() {
        val prompt = PersonaPrompts.systemPrompt(PersonaPrompts.GAY_BOY, "Vikram")
        assertTrue(
            "the custom mate name must be echoed in the gay prompt",
            prompt.contains("You ARE Vikram"),
        )
    }

    @Test
    fun boyGayMateNeverBorrowsTheStraightPersonaText() {
        val gay = PersonaPrompts.systemPrompt(PersonaPrompts.GAY_BOY, "Rohan")
        val girlfriend = PersonaPrompts.systemPrompt("girlfriend", "Aisha")
        val boyfriend = PersonaPrompts.systemPrompt("boyfriend", "Arjun")

        assertNotEquals(girlfriend, gay)
        assertNotEquals(boyfriend, gay)
        assertFalse(
            "gay mode must not carry the girlfriend's female body rule",
            gay.contains("her vagina/pussy"),
        )
        assertFalse(
            "gay mode must not be told the user is his GIRL",
            gay.contains("the user is his GIRL"),
        )
        assertFalse(
            "gay mode must not be told the user is his GIRLFRIEND",
            gay.contains("The user is your GIRLFRIEND"),
        )
    }

    @Test
    fun boyGayMateKeepsTheRulesEveryPersonaGets() {
        val prompt = PersonaPrompts.systemPrompt(PersonaPrompts.GAY_BOY, "Rohan")

        assertTrue("language lock must reach gay mode", prompt.contains("LANGUAGE (must always follow)"))
        assertTrue("one-voice rule must reach gay mode", prompt.contains("ONE VOICE ONLY"))
        assertTrue("no-meta rule must reach gay mode", prompt.contains("NO META (absolute)"))
        assertTrue("identity rule must reach gay mode", prompt.contains("IDENTITY (top priority"))
        assertTrue(
            "photo rule must reach gay mode (image sending is off app-wide)",
            prompt.contains("you CANNOT send the user a picture"),
        )
    }

    // ---------------------------------------------------------- 18+ private ---

    @Test
    fun boyGayPrivateRoomLocksBothBodiesMale() {
        val privatePrompt = PersonaPrompts.systemPrompt(
            PersonaPrompts.GAY_BOY,
            "Rohan",
            privateMode = true,
            infoTurn = false,
            photoTurn = false,
        )

        assertTrue(
            "gay mode is an adult persona, so Private Mode must engage",
            privatePrompt.contains("PRIVATE MODE (18+ adult chat)"),
        )
        assertTrue(
            "the GENDER LOCK must list the boy-gay case as MAN + MAN",
            privatePrompt.contains("a boy-gay persona is a MAN") &&
                privatePrompt.contains("a same-sex couple, both male"),
        )
        assertTrue(
            "the user must never be given female anatomy",
            privatePrompt.contains("NEVER give the user a vagina"),
        )
        assertTrue(
            "the private room must still send short bubbles",
            privatePrompt.contains(AppConfig.PRIVATE_BUBBLE_SEPARATOR),
        )
    }

    // ------------------------------------------------------------- identity ---

    @Test
    fun boyGayModeIntroducesItselfAsTheBoyfriend() {
        val answer = PersonaPrompts.identityAnswer(PersonaPrompts.GAY_BOY, "Rohan")

        assertTrue("the identity answer must name the mate", answer.contains("Rohan"))
        assertTrue("the identity answer must say boyfriend", answer.contains("boyfriend"))
        assertTrue("the identity answer must flag the same-sex couple", answer.contains("\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08"))
    }

    @Test
    fun boyGayModeHasItsOwnDefaultNameAndModel() {
        assertEquals("Rohan", PersonaPrompts.defaultName(PersonaPrompts.GAY_BOY))
        assertTrue(
            "the identity answer must fall back to Rohan",
            PersonaPrompts.identityAnswer(PersonaPrompts.GAY_BOY).contains("Rohan"),
        )
        assertTrue(
            "gay mode must have its own adult (Private) model wired",
            AppConfig.OPENROUTER_MODEL_GAY.isNotBlank(),
        )
    }

    // ------------------------------------------------------------- girl gay ---

    @Test
    fun girlGayMateIsAWomanAndSoIsTheUser() {
        val prompt = PersonaPrompts.systemPrompt(PersonaPrompts.GAY_GIRL, "Riya")

        assertTrue(
            "the girl-gay mate must be pinned to a same-sex couple",
            prompt.contains("same-sex (lesbian / girl-gay) relationship"),
        )
        assertTrue("both bodies must stay female", prompt.contains("BOTH bodies stay female"))
        assertTrue("the user must never be called a man", prompt.contains("NEVER call her a man"))
        assertTrue(
            "the girl-gay mate is the user's girlfriend",
            prompt.contains("the user's loving girlfriend"),
        )
    }

    @Test
    fun girlGayMateNeverBorrowsTheBoyGayText() {
        val girlGay = PersonaPrompts.systemPrompt(PersonaPrompts.GAY_GIRL, "Riya")
        val boyGay = PersonaPrompts.systemPrompt(PersonaPrompts.GAY_BOY, "Rohan")

        assertNotEquals("the two gay couples need different prompts", boyGay, girlGay)
        assertTrue(
            "the girl-gay body rule must keep female anatomy",
            girlGay.contains("her vagina/pussy"),
        )
        assertFalse(
            "the girl-gay mate must never be given a penis of her own",
            girlGay.contains("your penis/cock"),
        )
        assertFalse(
            "the user must not be described as a man",
            girlGay.contains("The user is your BOYFRIEND"),
        )
        assertFalse(
            "girl gay must not carry the boy-gay behaviour block",
            girlGay.contains("GAY COUPLE BEHAVIOUR"),
        )
    }

    @Test
    fun girlGayPrivateRoomLocksBothBodiesFemale() {
        val privatePrompt = PersonaPrompts.systemPrompt(
            PersonaPrompts.GAY_GIRL,
            "Riya",
            privateMode = true,
            infoTurn = false,
            photoTurn = false,
        )

        assertTrue(
            "girl gay is an adult persona, so Private Mode must engage",
            privatePrompt.contains("PRIVATE MODE (18+ adult chat)"),
        )
        assertTrue(
            "the GENDER LOCK must list the girl-gay case as WOMAN + WOMAN",
            privatePrompt.contains("a girl-gay (lesbian) persona is a WOMAN") &&
                privatePrompt.contains("a same-sex couple, both female"),
        )
        assertTrue(
            "the private room must still send short bubbles",
            privatePrompt.contains(AppConfig.PRIVATE_BUBBLE_SEPARATOR),
        )
    }

    @Test
    fun girlGayModeIntroducesItselfAsTheGirlfriend() {
        val answer = PersonaPrompts.identityAnswer(PersonaPrompts.GAY_GIRL, "Riya")

        assertTrue("the identity answer must name the mate", answer.contains("Riya"))
        assertTrue("the identity answer must say girlfriend", answer.contains("girlfriend"))
        assertTrue(
            "the identity answer must flag the same-sex couple",
            answer.contains("\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08"),
        )
        assertEquals("Riya", PersonaPrompts.defaultName(PersonaPrompts.GAY_GIRL))
    }

    // ---------------------------------------- the ids the dropdowns can send ---

    /**
     * The relation dropdown can send girlfriend / boyfriend / crush + the "Gay"
     * umbrella, and the Gay type row resolves that umbrella into GAY_BOY /
     * GAY_GIRL (see MainActivity.GAY_TYPE_OPTIONS). Every one of those ids must
     * have a real prompt, a default name AND a working 18+ Private room — a new
     * row must never reach the backend as a persona the engine cannot handle.
     */
    @Test
    fun everyPersonaTheDropdownsCanSendHasAPromptANameAndAPrivateRoom() {
        val ids = listOf(
            "girlfriend",
            "boyfriend",
            "crush",
            "friend",
            PersonaPrompts.GAY_BOY,
            PersonaPrompts.GAY_GIRL,
        )
        for (id in ids) {
            assertTrue(
                "$id must have its own persona prompt",
                PersonaPrompts.systemPrompt(id, "Priya").contains("You ARE"),
            )
            assertTrue(
                "$id must have a default name",
                PersonaPrompts.defaultName(id).isNotBlank(),
            )
            assertTrue(
                "$id must get the 18+ Private room rule",
                PersonaPrompts.systemPrompt(id, "Priya", privateMode = true)
                    .contains("PRIVATE MODE (18+ adult chat)"),
            )
        }
    }

    @Test
    fun legacyGayIdStillMeansBoyGay() {
        assertEquals(PersonaPrompts.GAY_BOY, PersonaPrompts.normalizePersona("gay"))
        assertEquals(
            "an old 'gay' session must behave exactly like boy gay",
            PersonaPrompts.systemPrompt(PersonaPrompts.GAY_BOY, "Rohan"),
            PersonaPrompts.systemPrompt("gay", "Rohan"),
        )
        assertEquals("", PersonaPrompts.normalizePersona(" "))
        assertTrue(PersonaPrompts.isGayPersona("gay"))
        assertTrue(PersonaPrompts.isGayPersona(PersonaPrompts.GAY_BOY))
        assertTrue(PersonaPrompts.isGayPersona(PersonaPrompts.GAY_GIRL))
        assertFalse("girlfriend is not a gay persona", PersonaPrompts.isGayPersona("girlfriend"))
        assertFalse("a blank relation is not a gay persona", PersonaPrompts.isGayPersona(""))
        // isMaleMate drives the male word ending in the live-call greeting line.
        assertTrue(PersonaPrompts.isMaleMate(PersonaPrompts.GAY_BOY))
        assertTrue(PersonaPrompts.isMaleMate("boyfriend"))
        assertTrue("the legacy id is boy gay, so a man", PersonaPrompts.isMaleMate("gay"))
        assertFalse(PersonaPrompts.isMaleMate(PersonaPrompts.GAY_GIRL))
        assertFalse(PersonaPrompts.isMaleMate("girlfriend"))
    }
}
