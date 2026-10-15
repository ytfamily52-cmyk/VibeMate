package com.example.vibemate.admin

/**
 * One row in the users list.
 *
 * A "user" is either
 *  - a `users/{uid}` profile document (name + joined date), and/or
 *  - the `owner_id` of at least one `chat_sessions` document (some installs
 *    never wrote a profile), or
 *  - the ORPHAN bucket ([uid] empty): sessions whose `owner_id` was wiped by
 *    the old replace-style PATCH bug. Showing it lets the owner clean up.
 */
data class AdminUser(
    val uid: String,
    val displayName: String = "",
    val createdAt: String = "",
    val sessionCount: Int = 0,
    val privateCount: Int = 0,
    /** Chats the user removed from THEIR app (still stored here, see [AdminSession.userDeleted]). */
    val deletedCount: Int = 0,
    /**
     * `updated_at` (falling back to `created_at`) of the user's MOST RECENT chat.
     * The users list is sorted by this, so the freshest activity sits on top;
     * "" means the user has no chat yet.
     */
    val lastChatAt: String = "",
    val hasProfile: Boolean = false,
) {
    /** What the list shows as the user's name. */
    val label: String
        get() = when {
            displayName.isNotEmpty() -> displayName
            uid.isEmpty() -> "(no owner)"
            else -> "User " + uid.take(8)
        }

    /** Sessions with an empty owner_id (left over from the old bug). */
    val isOrphan: Boolean get() = uid.isEmpty()
}

/** One `chat_sessions/{id}` document: a chat between the user and a mate. */
data class AdminSession(
    val id: String,
    val ownerId: String = "",
    val title: String = "",
    val persona: String = "",
    val mateName: String = "",
    val privateMode: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    /**
     * `deleted_by_owner`: the user removed this chat from THEIR app. The app only
     * FLAGS the chat (it has no delete right any more, see firestore.rules), so
     * the chat + every message are still here — exactly what the admin panel is
     * for. Only the admin's delete is final.
     */
    val deletedByOwner: Boolean = false,
    /** `deleted_at`: when the user deleted the chat from their app. */
    val deletedAt: String = "",
    /** `cleared_at`: when the user last cleared the history (messages all remain). */
    val clearedAt: String = "",
) {
    /** Mate's name, falling back to the persona's relationship label. */
    val label: String get() = mateName.ifEmpty { personaLabel }

    /** The user deleted this chat from their app — show it, it is the "final delete" case. */
    val userDeleted: Boolean get() = deletedByOwner

    /** The user cleared the history: everything is still readable here. */
    val userCleared: Boolean get() = clearedAt.isNotEmpty()

    /** What the chat row / header says about the user's own delete ("" = nothing). */
    val deleteNote: String
        get() = when {
            userDeleted -> {
                val when_ = deletedAt.ifEmpty { updatedAt.ifEmpty { createdAt } }
                "user deleted" + if (when_.isEmpty()) "" else " (" + when_.take(16) + ")"
            }
            userCleared -> {
                "user cleared (" + clearedAt.take(16) + ")"
            }
            else -> ""
        }

    val personaLabel: String
        get() = when (persona) {
            "boyfriend" -> "Boyfriend"
            "crush" -> "Crush"
            "friend" -> "Friend"
            // "Gay" is ONE relation in the app but TWO personas (same-sex couples,
            // so the label says which couple): gay_boy = both men, gay_girl =
            // both women. The legacy "gay" id (old sessions) means boy gay.
            "gay", "gay_boy" -> "Boy Gay"
            "gay_girl" -> "Girl Gay"
            "girlfriend" -> "Girlfriend"
            else -> if (persona.isEmpty()) "Mate" else persona
        }
}

/** One `chat_sessions/{id}/messages/{id}` document. */
data class AdminMessage(
    val id: String,
    val role: String = "",
    val content: String = "",
    val createdAt: String = "",
    val type: String = "",
    val fileName: String = "",
    val data: String = "",
) {
    /** True for a message the USER wrote (the app writes role = "user"). */
    val fromUser: Boolean get() = role.equals("user", ignoreCase = true)

    /** Friendly attachment kind ("" for a plain text message). */
    val attachmentLabel: String
        get() = when (type.uppercase()) {
            "IMAGE" -> "Photo"
            "AUDIO" -> "Voice note"
            "DOC" -> "Document"
            "VIDEO" -> "Video"
            else -> ""
        }

    /** True when a base64 photo payload is present. */
    val hasPhoto: Boolean get() = type.equals("IMAGE", ignoreCase = true) && data.isNotEmpty()
}