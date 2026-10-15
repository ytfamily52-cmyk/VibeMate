package com.example.vibemate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The "user sent a photo" regression suite (pure JVM — no network, no API cost).
 *
 * It locks in the two fixes the user asked for:
 *
 *  1) RECOGNITION — a photo really travels to the model as image bytes
 *     (`inlineData`, built from [VibeMateApi.geminiReadablePhoto]) and a question
 *     asked about the photo counts as a REAL question, so the answer comes back
 *     complete instead of being trimmed to two chat lines ("adhura").
 *  2) IMAGE SENDING IS OFF — every persona knows it cannot send a picture and is
 *     forbidden from sending the user to the old "/pic" promise, and the API
 *     refuses to generate even if a caller forgets to check the flag.
 */
class PhotoTurnTest {

    private val api = VibeMateApi()

    /** A user photo exactly as MainActivity / `listMessages` hand it to the API. */
    private fun photo(id: String = "photo-1"): MessageDto = MessageDto().apply {
        this.id = id
        role = "user"
        type = "IMAGE"
        fileName = "question.png"   // the name says .png, the bytes are re-encoded JPEG
        data = "ZmFrZS1qcGVnLWJ5dGVz"
    }

    private fun text(role: String, content: String): MessageDto = MessageDto().apply {
        this.role = role
        this.content = content
    }

    /** A realistic 6-sentence answer: ~84 words, far past the chat cap. */
    private fun longAnswer(): String =
        (1..6).joinToString(". ") { i ->
            "ye jawab ki line $i me pura matlab likha hua hai shabdon ke saath"
        } + "."

    // ============================================== (1) photo recognition ======

    @Test
    fun theNewestPhotoIsTheOneThatTravelsToTheModel() {
        val older = photo("old")
        val newest = photo("new")
        val history = listOf(
            older,
            text("model", "wah, kya baat hai!"),
            newest,
            text("user", "aur suno"),
        )
        assertEquals(
            "only the newest photo may be attached (older ones are already answered)",
            newest.id,
            api.newestPhoto(history)?.id,
        )
        assertNull(
            "a text-only chat has no photo to attach",
            api.newestPhoto(history.filter { it.type != "IMAGE" }),
        )
    }

    @Test
    fun aPhotoBecomesRealImageBytesForTheModel() {
        val readable = api.geminiReadablePhoto(photo())
        assertNotNull("the photo must travel as image bytes, not as a text hint", readable)
        assertEquals("image/jpeg", readable!!.first)
        assertEquals("ZmFrZS1qcGVnLWJ5dGVz", readable.second)

        // Nothing gets attached for models, voice notes, documents or an empty photo.
        assertNull(api.geminiReadablePhoto(text("model", "hi")))
        assertNull(api.geminiReadablePhoto(MessageDto().apply { type = "AUDIO"; data = "x" }))
        assertNull(api.geminiReadablePhoto(MessageDto().apply { type = "IMAGE"; data = "" }))
    }

    @Test
    fun aQuestionAboutAPhotoIsAnInfoTurn() {
        // real questions about the picture -> ONE complete answer
        assertTrue(api.photoQuestionIsInfoTurn("mujhe batao isme kya likha hai", photo()))
        assertTrue(api.photoQuestionIsInfoTurn("ye kaun sa fruit hai", photo()))
        assertTrue(api.photoQuestionIsInfoTurn("what is in this picture", photo()))

        // a photo with no question stays normal warm chat (tiny bubbles)
        assertFalse(api.photoQuestionIsInfoTurn("ye dekho kaisa hai", photo()))
        assertFalse(api.photoQuestionIsInfoTurn("kaise ho?", photo()))
        assertFalse(api.photoQuestionIsInfoTurn("", photo()))

        // voice notes / documents keep the old always-chat behaviour
        assertFalse(
            api.photoQuestionIsInfoTurn(
                "mujhe batao ye kya hai",
                MessageDto().apply { type = "AUDIO"; data = "x" },
            ),
        )
        // ...and a text-only turn is decided by the normal detector
        assertFalse(api.photoQuestionIsInfoTurn("mujhe batao ye kya hai", null))
    }

    @Test
    fun theAnswerAboutAPhotoIsNotTrimmedToTwoLines() {
        val raw = longAnswer()
        val complete = api.cleanReply(raw, infoTurn = true)
        assertEquals("an answer about a photo must come back whole", raw, complete)
        assertFalse("the answer was cut: $complete", complete.endsWith("…"))

        // The very same text in a normal chat turn still stops after ~2 lines,
        // which is exactly the truncation an info turn must escape.
        val chatWords = api.cleanReply(raw).split(Regex("\\s+")).count { it.isNotEmpty() }
        assertTrue("normal chat turn got too long: $chatWords words", chatWords <= AppConfig.MAX_REPLY_WORDS)
        assertTrue("the chat cap must actually trim this long text", chatWords < 30)
    }

    @Test
    fun thePhotoTurnPromptTellsThePersonaToReadThePicture() {
        val withPhoto = PersonaPrompts.systemPrompt("girlfriend", "Priya", photoTurn = true)
        assertTrue(withPhoto.contains("USER SENT A PHOTO"))
        assertTrue(withPhoto.contains("NEVER say you cannot see images"))
        assertTrue("the question about the photo must be answered", withPhoto.contains("ANSWER"))

        val without = PersonaPrompts.systemPrompt("girlfriend", "Priya")
        assertFalse(
            "the photo rule must appear ONLY on a turn that really has a photo",
            without.contains("USER SENT A PHOTO"),
        )
        assertTrue("the persona name is still templated in", without.contains("Priya"))

        // The 18+ room gets the same rule.
        val privateRoom = PersonaPrompts.systemPrompt(
            "girlfriend",
            "Priya",
            privateMode = true,
            infoTurn = true,
            photoTurn = true,
        )
        assertTrue(privateRoom.contains("USER SENT A PHOTO"))
    }

    @Test
    fun thePrivatePhotoPromptPinsThePersonaToTheTranscription() {
        val privateRoom = PersonaPrompts.systemPrompt(
            "girlfriend",
            "Priya",
            privateMode = true,
            photoTurn = true,
        )
        assertTrue(
            "the private photo rule must pin the persona to the VISUAL/AURA transcription",
            privateRoom.contains("transcribed for you"),
        )
        assertTrue(
            "the private photo rule must ban inventing a different picture",
            privateRoom.contains("Never invent a different picture"),
        )

        // The normal (Gemini) room keeps the generic rule — there the model really
        // DOES get the image bytes, so no transcription wording appears.
        val normal = PersonaPrompts.systemPrompt("girlfriend", "Priya", photoTurn = true)
        assertTrue(normal.contains("you CAN see what is really in it"))
        assertFalse(normal.contains("transcribed for you"))
    }

    // ============================================== (2) image sending is off ===

    @Test
    fun everyPersonaKnowsItCannotSendPictures() {
        for (persona in listOf(
            "girlfriend",
            "boyfriend",
            "crush",
            "gay",
            PersonaPrompts.GAY_BOY,
            PersonaPrompts.GAY_GIRL,
            "friend",
        )) {
            val prompt = PersonaPrompts.systemPrompt(persona, "Priya")
            assertTrue(
                "$persona must be told it cannot send pictures",
                prompt.contains("you CANNOT send the user a picture"),
            )
            assertTrue(
                "$persona must not send the user to a command",
                prompt.contains("never tell them to type a command"),
            )
            assertFalse(
                "$persona still carries the old picture promise",
                prompt.contains("plus what they want"),
            )
        }
    }

    @Test
    fun theApiRefusesToGenerateWhileImageGenIsOff() {
        assertFalse("AppConfig.IMAGE_GEN_ENABLED must stay false", AppConfig.IMAGE_GEN_ENABLED)
        try {
            api.generateImage("ek cute mirror selfie")
            fail("generateImage() must refuse while IMAGE_GEN_ENABLED is false")
        } catch (e: ApiException) {
            assertTrue(
                "unexpected refusal message: ${e.message}",
                e.message.orEmpty().contains("switched off"),
            )
        }
    }
}