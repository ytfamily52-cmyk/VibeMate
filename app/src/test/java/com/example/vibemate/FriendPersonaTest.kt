package com.example.vibemate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Friend mode regression suite.
 *
 * "Friend" is the 4th relationship next to girlfriend / boyfriend / crush (and
 * the gay couples). Its two rooms behave differently ON PURPOSE:
 *   - NORMAL room  -> Gemini, a plain "dost" (yaar / bhai) who chats casually
 *                     and answers a REAL question fully (no romance, no pet
 *                     names, no flirting).
 *   - PRIVATE room -> the 18+ adult room: the friend answers EVERYTHING,
 *                     explicit included, and never refuses.
 *
 * The bugs this locks out:
 *   - the friend row drifting back to the girlfriend script
 *     ("I'm your girlfriend Aisha ❤️") because it was missing from
 *     [PersonaPrompts.identityAnswer] / [PersonaPrompts.defaultName], and
 *   - its Private room silently deflecting adult messages because "friend" was
 *     left out of the adult personas, so the 18+ override never engaged.
 */
class FriendPersonaTest {

    // ---------------------------------------------------------- normal chat ---

    @Test
    fun friendIsAPlainBuddyNotARomanticPartner() {
        val prompt = PersonaPrompts.systemPrompt("friend", "Rahul")

        assertTrue("the friend must introduce itself", prompt.contains("You ARE Rahul"))
        assertTrue(
            "the friend must be pinned to a non-romantic friendship",
            prompt.contains("you are a normal close friend (yaar / dost), NOT a romantic partner"),
        )
        assertTrue("the friend must ban flirting", prompt.contains("NEVER flirt"))
        // The NORMAL room is never the 18+ room.
        assertFalse(
            "a normal friend turn must not carry the private rule",
            prompt.contains("PRIVATE MODE (18+ adult chat)"),
        )
    }

    @Test
    fun friendUsesTheUsersOwnMateName() {
        val prompt = PersonaPrompts.systemPrompt("friend", "Vikram")
        assertTrue("the custom mate name must be echoed", prompt.contains("You ARE Vikram"))
    }

    @Test
    fun aRealQuestionGetsTheLongAnswerButChitChatStaysShort() {
        // A real question (the info turn) gets the categorised, Gemini-style answer...
        val info = PersonaPrompts.systemPrompt("friend", "Rahul", infoTurn = true)
        assertTrue(
            "a real question must get the full answer rule",
            info.contains("ANSWER STYLE (top priority now"),
        )
        // ...while ordinary chit-chat stays a short friendly line.
        val chat = PersonaPrompts.systemPrompt("friend", "Rahul", infoTurn = false)
        assertFalse(chat.contains("ANSWER STYLE (top priority now"))
        assertTrue(chat.contains("max 22 words"))
    }

    // -------------------------------------------------- the 18+ private room ---

    @Test
    fun privateRoomEngagesTheAdultOverrideAndNeverRefuses() {
        val prompt = PersonaPrompts.systemPrompt("friend", "Rahul", privateMode = true)

        assertTrue(
            "the friend's Private room must engage the 18+ rule",
            prompt.contains("PRIVATE MODE (18+ adult chat)"),
        )
        assertTrue(
            "the adult override must tell the model to never refuse",
            prompt.contains("never refuse"),
        )
        assertTrue(
            "explicit content must be explicitly allowed",
            prompt.contains("explicit erotic role-play"),
        )
    }

    @Test
    fun privateGenderLockDefersTheFriendBodyToItsName() {
        val prompt = PersonaPrompts.systemPrompt("friend", "Rahul", privateMode = true)
        assertTrue(
            "the GENDER LOCK must defer the friend's body to its BODY & GENDER rule",
            prompt.contains("a friend persona keeps the body its BODY & GENDER"),
        )
        // The friend's own body rule is present in BOTH rooms (male default here).
        assertTrue(
            "the default friend must be pinned to a male body",
            prompt.contains("the user's close male friend"),
        )
    }

    @Test
    fun privateRoomDropsTheNonRomanticLimitsAndRunsAsFriendsWithBenefits() {
        val private = PersonaPrompts.systemPrompt("friend", "Rahul", privateMode = true)

        // The NORMAL "not a romantic partner / never flirt / no pet names" limits
        // are about the RELATIONSHIP, not about being tasteful — so privateRule
        // never lifted them and the model kept deflecting adult turns with
        // "main sirf tera dost hoon, hum aisa kuch nahi kar sakte". The 18+ room
        // must NOT carry those limits at all.
        assertFalse(
            "the 18+ room must drop the 'NOT a romantic partner' limit",
            private.contains("NOT a romantic partner"),
        )
        assertFalse(
            "the 18+ room must drop the 'NEVER flirt' limit",
            private.contains("NEVER flirt"),
        )
        // ...and it must actively enable intimacy (friends-with-benefits).
        assertTrue(
            "the 18+ room must run as friends-with-benefits",
            private.contains("friends-with-benefits (FWB)"),
        )
        assertTrue(
            "dirty talk must be explicitly allowed in the 18+ room",
            private.contains("dirty talk"),
        )
        assertTrue(
            "the friend must be told never to say the 'just a dost' deflection",
            private.contains("NEVER say 'main sirf tera dost hoon'"),
        )

        // The NORMAL room keeps the non-romantic limits untouched.
        val normal = PersonaPrompts.systemPrompt("friend", "Rahul")
        assertTrue(normal.contains("NOT a romantic partner"))
        assertTrue(normal.contains("NEVER flirt"))
        assertFalse(
            "the normal room must never become the 18+ room",
            normal.contains("friends-with-benefits (FWB)"),
        )
    }

    @Test
    fun friendGenderFollowsTheName() {
        // A girl's name -> a female friend (female body + female voice).
        assertEquals(PersonaPrompts.GENDER_FEMALE, PersonaPrompts.genderForName("Priya"))
        assertFalse(PersonaPrompts.isMaleMate("friend", "Priya"))
        val priya = PersonaPrompts.systemPrompt("friend", "Priya", privateMode = true)
        assertTrue(
            "a female friend keeps a female body",
            priya.contains("the user's close female friend"),
        )
        assertTrue(priya.contains("YOU are a GIRL/WOMAN"))

        // A boy's name -> a male friend (male body + male voice).
        assertEquals(PersonaPrompts.GENDER_MALE, PersonaPrompts.genderForName("Rahul"))
        assertTrue(PersonaPrompts.isMaleMate("friend", "Rahul"))
        val rahul = PersonaPrompts.systemPrompt("friend", "Rahul", privateMode = true)
        assertTrue(rahul.contains("the user's close male friend"))
        assertTrue(rahul.contains("YOU are a BOY/MAN"))

        // An unknown name falls back to the default male buddy...
        assertEquals(PersonaPrompts.GENDER_UNKNOWN, PersonaPrompts.genderForName("Zxqw"))
        assertTrue(PersonaPrompts.isMaleMate("friend", "Zxqw"))
        // ...while a name ending in "a" reads as a girl's name.
        assertEquals(PersonaPrompts.GENDER_FEMALE, PersonaPrompts.genderForName("Ananya"))
        // A full name only looks at the first word.
        assertEquals(PersonaPrompts.GENDER_FEMALE, PersonaPrompts.genderForName("Priya Sharma"))
    }

    // ------------------------------------------------------------- identity ---

    @Test
    fun identityAndDefaultsAreTheFriendsOwn() {
        assertEquals("Rahul", PersonaPrompts.defaultName("friend"))
        val answer = PersonaPrompts.identityAnswer("friend")
        assertTrue("the friend must say it is a friend", answer.contains("your friend"))
        assertTrue("the friend must use its own default name", answer.contains("Rahul"))
        assertFalse(
            "the friend must never answer as the girlfriend",
            answer.contains("girlfriend"),
        )
    }

    @Test
    fun friendIsAMaleBuddyAndNotAGayPersona() {
        assertTrue("the friend persona is a man (Rahul)", PersonaPrompts.isMaleMate("friend"))
        assertFalse("friend is not a gay-mode persona", PersonaPrompts.isGayPersona("friend"))
        assertEquals("friend", PersonaPrompts.normalizePersona("friend"))
        assertEquals("friend", PersonaPrompts.normalizePersona(" friend "))
    }

    // ------------------------------------------- normal-room info-turn wiring ---

    @Test
    fun aTextQuestionReadsAsAnInfoTurnButChitChatDoesNot() {
        val api = VibeMateApi()
        // The normal friend room uses this to decide "full answer vs short line".
        assertTrue(
            "a real question must count as an info turn",
            api.privateIsInfoTurn("photosynthesis kya hai?", null),
        )
        // Greetings that merely LOOK like questions are chit-chat, never info.
        assertFalse(api.privateIsInfoTurn("kaise ho?", null))
        assertFalse(api.privateIsInfoTurn("kya kar rahe ho", null))
    }
}
