package com.example.vibemate

/**
 * One row in the WhatsApp-style chat list.
 *
 * status is shown as WhatsApp-like ticks under the user's own messages:
 *  - SENT      single grey tick   (message left the phone)
 *  - DELIVERED double grey ticks  (backend accepted + persisted it)
 *  - READ      double blue ticks  (VibeMate's reply arrived, so it "saw" it)
 *  - FAILED    red "!" marker     (network/validation error)
 */
class ChatMessage {

    enum class Sender { USER, VIBEMATE, SYSTEM, TYPING }

    enum class Status { SENDING, SENT, DELIVERED, READ, FAILED }

    var sender: Sender = Sender.USER
    var displayName: String = ""
    var text: String = ""
    var status: Status = Status.SENDING
    var timeLabel: String = ""

    // WhatsApp-style attachment: type = "" (text), "IMAGE", "AUDIO", "DOC".
    // data holds the base64 payload (image bytes / audio / document) and
    // fileName the display name shown for audio/document bubbles.
    var type: String = ""
    var data: String = ""
    var fileName: String = ""

    // Firestore document id of this message (empty for UI-only rows / rows that
    // are not yet persisted). Used by the in-chat "select + delete message".
    var id: String = ""

    // When non-empty, the row renders a tappable call-to-action button (e.g.
    // "🔒 Join Private Chat") below the message text. Used by the explicit-
    // content invite in normal chat so the user can jump into the 18+ room.
    var actionLabel: String = ""

    constructor(sender: Sender, displayName: String, text: String, timeLabel: String) {
        this.sender = sender
        this.displayName = displayName
        this.text = text
        this.timeLabel = timeLabel
    }

    constructor(
        sender: Sender,
        displayName: String,
        text: String,
        timeLabel: String,
        type: String,
        data: String,
        fileName: String,
    ) {
        this.sender = sender
        this.displayName = displayName
        this.text = text
        this.timeLabel = timeLabel
        this.type = type
        this.data = data
        this.fileName = fileName
    }
}
