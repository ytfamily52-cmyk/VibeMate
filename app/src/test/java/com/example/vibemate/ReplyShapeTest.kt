package com.example.vibemate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "adhura reply" (half reply ending in "…") regression suite.
 *
 * Runs entirely on the JVM — no API call, no cost — and locks in the two shapes
 * the Private room must produce:
 *   - a CHAT turn  -> 2-4 tiny WhatsApp-style bubbles, never cut mid-sentence
 *   - an INFO turn -> ONE complete answer, never cut short
 * plus the normal (Gemini) chat rule: stop at a SENTENCE boundary, never append
 * "…" to a realistic reply.
 */
class ReplyShapeTest {

    private val api = VibeMateApi()

    // ---------------------------------------------------------------- info/chat

    @Test
    fun realQuestionsAreInfoTurns() {
        assertTrue(api.privateIsInfoTurn("Taj Mahal kahan hai", null))
        assertTrue(api.privateIsInfoTurn("chai kaise banate hain", null))
        assertTrue(api.privateIsInfoTurn("photosynthesis explain karo", null))
        assertTrue(api.privateIsInfoTurn("mujhe batao ye kya hai", null))
        assertTrue(api.privateIsInfoTurn("what is photosynthesis", null))
    }

    @Test
    fun romanceAlwaysStaysChat() {
        assertFalse(api.privateIsInfoTurn("kaisi ho jaan?", null))
        // a romantic pet name wins even when the turn also has a question word
        assertFalse(api.privateIsInfoTurn("Taj Mahal kahan hai jaan", null))
        assertFalse(api.privateIsInfoTurn("I love you, kahan ho?", null))
        assertFalse(api.privateIsInfoTurn("aaj maine bahut kaam kiya", null))
    }

    @Test
    fun greetingsAreNotInfoTurns() {
        assertFalse(api.privateIsInfoTurn("kaise ho?", null))
        assertFalse(api.privateIsInfoTurn("how are you", null))
        assertFalse(api.privateIsInfoTurn("kya kar rahi ho?", null))
    }

    @Test
    fun photoTurnIsAlwaysChat() {
        val photo = MessageDto().apply { type = "IMAGE"; data = "x" }
        assertFalse(api.privateIsInfoTurn("ye dekho kaisa hai", photo))
        // ...but a REAL question asked about that photo is still answered in full:
        // the room combines this with photoQuestionIsInfoTurn() (see PhotoTurnTest).
        assertTrue(api.photoQuestionIsInfoTurn("mujhe batao isme kya likha hai", photo))
    }

    // -------------------------------------------------------------- chat bubbles

    @Test
    fun markedChatReplyBecomesTinyBubbles() {
        val out = api.splitPrivateBubbles(
            "haan bilkul jaan ||| main bhi tumhe miss kar rahi thi ||| ab batao din kaisa tha?",
            false,
        )
        assertEquals(3, out.size)
        assertEquals("haan bilkul jaan", out[0])
        assertTrue(out.last().contains("din kaisa tha"))
    }

    @Test
    fun longParagraphSplitsAtSentencesAndLosesNoText() {
        val text = "haan jaan main bilkul theek hoon aur tumhara intezaar kar rahi thi. " +
            "aaj maine tumhare liye khana banaya tha. ab batao tumhara din kaisa raha?"
        val out = api.splitPrivateBubbles(text, false)
        assertTrue("expected several bubbles, got " + out.size, out.size >= 2)
        for (b in out) {
            assertFalse("bubble was cut with an ellipsis: " + b, b.endsWith("…"))
        }
        // nothing is dropped: both later sentences survive somewhere
        assertTrue(out.any { it.contains("khana banaya") })
        assertTrue(out.any { it.contains("din kaisa raha") })
    }

    @Test
    fun extraPiecesAreMergedIntoTheLastBubbleNotDropped() {
        val out = api.splitPrivateBubbles("a1 ||| b2 ||| c3 ||| d4 ||| e5", false)
        assertEquals(AppConfig.MAX_PRIVATE_BUBBLES, out.size)
        assertTrue("tail text was dropped: " + out, out.last().contains("e5"))
    }

    @Test
    fun longCommaSentenceSplitsIntoCompleteChunks() {
        val out = api.splitPieceAtPunctuation(
            "yaar aaj mausam bahut acha tha, maine ghar pe khana banaya, phir movie dekhi, " +
                "shaam ko walk pe gayi, raat ko tumhe yaad kiya, ab so rahi hoon"
        )
        assertTrue("expected at least 2 chunks, got " + out.size, out.size >= 2)
        for (b in out) assertFalse("chunk was cut: " + b, b.endsWith("…"))
        assertTrue(out.any { it.contains("so rahi hoon") })
    }

    @Test
    fun realisticBubbleIsReturnedUntouched() {
        assertEquals("main theek hoon janu", api.capPrivateBubble("main theek hoon janu"))
    }

    // -------------------------------------------------------------- info answers

    @Test
    fun infoAnswerIsOneCompleteBubble() {
        val answer = (1..150).joinToString(" ") { "shabd$it" }
        val out = api.splitPrivateBubbles(answer, true)
        assertEquals(1, out.size)
        assertEquals(answer, out[0])   // untouched, no "…"
    }

    @Test
    fun floodGuardOnlyStopsARunawayParagraph() {
        val flood = (1..600).joinToString(" ") { "shabd$it" }
        val out = api.capPrivateInfoReply(flood)
        assertTrue("the anti-flood guard should clip this", out.endsWith("…"))
        assertTrue(out.split(Regex("\\s+")).size <= AppConfig.MAX_PRIVATE_INFO_WORDS + 1)
    }

    // ------------------------------------------------------- normal (Gemini) chat

    @Test
    fun normalReplyKeepsWholeSentencesAndNoEllipsis() {
        val raw = "haan jaan, main theek hoon. aaj maine tumhare liye khana banaya tha. " +
            "tum batao tumhara din kaisa raha? main tumhe bahut miss kar rahi hoon."
        val out = api.cleanReply(raw)
        assertFalse("normal reply ended half: " + out, out.endsWith("…"))
        assertTrue(out.contains("banaya"))
        assertTrue(out.contains("miss kar rahi hoon"))
    }

    @Test
    fun normalReplyDropsOnlyDuplicatedSentences() {
        val out = api.cleanReply("haan jaan. haan jaan. main theek hoon.")
        assertEquals("haan jaan. main theek hoon.", out)
    }

    @Test
    fun normalReplyStopsBeforeItBecomesAWallOfText() {
        val raw = "haan jaan, main theek hoon. aaj maine tumhare liye khana banaya tha. " +
            "tum batao tumhara din kaisa raha? main tumhe bahut miss kar rahi hoon. " +
            "achha suno, kal hum movie dekhne chalenge aur phir bahut baat karenge."
        val out = api.cleanReply(raw)
        assertFalse("normal reply ended half: " + out, out.endsWith("…"))
        val words = out.split(Regex("\\s+")).count { it.isNotEmpty() }
        assertTrue("reply is still long: $words words -> $out", words <= AppConfig.MAX_REPLY_WORDS)
        // The sentence that did not fit is dropped WHOLE (never shown half).
        assertFalse("leftover tail was kept: $out", out.contains("movie"))
    }

    @Test
    fun boyfriendPersonaAsksForTinyReplies() {
        val prompt = PersonaPrompts.systemPrompt("boyfriend", "Arjun")
        assertTrue(prompt.contains("max 22 words"))
        assertFalse("the old, too-long target is back", prompt.contains("max 38 words"))
        assertTrue(prompt.contains("Arjun"))
    }
}