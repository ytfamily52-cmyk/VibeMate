package com.example.vibemate.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "User ne chat delete kar di to admin se bhi delete ho jati thi" — the regression
 * suite for the soft delete.
 *
 * The APP never removes a chat any more: "delete chat" writes `deleted_by_owner` +
 * `deleted_at` and "clear chat" writes `cleared_at` on the `chat_sessions` document
 * (see VibeMateApi.SessionDeletePolicy), and firestore.rules no longer gives the
 * owner a `delete` right. So the admin panel must KEEP showing such a chat (with its
 * whole history) until an admin deletes it — that admin delete is the final one.
 *
 * Runs fully offline: parsing, merging and the row/header helpers only.
 */
class AdminUserDeletedChatTest {

    private val prefix = "projects/vibemate-3b75b/databases/(default)/documents"

    /** A `chat_sessions/{id}` doc as the APP writes it (flags optional). */
    private fun sessionDoc(
        id: String,
        owner: String,
        deletedByOwner: Boolean? = null,
        deletedAt: String = "2026-02-01T09:30:00Z",
        clearedAt: String? = null,
    ): String {
        var fields = "\"owner_id\":{\"stringValue\":\"$owner\"}," +
            "\"title\":{\"stringValue\":\"Hi\"}," +
            "\"persona\":{\"stringValue\":\"girlfriend\"}," +
            "\"mate_name\":{\"stringValue\":\"Aisha\"}," +
            "\"private_mode\":{\"booleanValue\":false}," +
            "\"created_at\":{\"timestampValue\":\"2026-01-01T10:00:00Z\"}," +
            "\"updated_at\":{\"timestampValue\":\"2026-01-03T10:00:00Z\"}"
        if (deletedByOwner != null) {
            fields += ",\"deleted_by_owner\":{\"booleanValue\":$deletedByOwner}," +
                "\"deleted_at\":{\"timestampValue\":\"$deletedAt\"}"
        }
        if (clearedAt != null) {
            fields += ",\"cleared_at\":{\"timestampValue\":\"$clearedAt\"}"
        }
        return "{\"name\":\"$prefix/chat_sessions/$id\",\"fields\":{$fields}}"
    }

    private fun list(vararg docs: String): String =
        "{\"documents\":[" + docs.joinToString(",") + "]}"

    private fun userDoc(uid: String, name: String): String =
        "{\"name\":\"$prefix/users/$uid\",\"fields\":{" +
            "\"display_name\":{\"stringValue\":\"$name\"}," +
            "\"created_at\":{\"timestampValue\":\"2026-01-02T03:04:05Z\"}}}"

    // ------------------------------------------------------------ parsing ------

    @Test
    fun aChatTheUserDeletedIsStillParsedAndVisibleToTheAdmin() {
        val body = "[" + "{\"document\":" + sessionDoc("s1", "uid-a", deletedByOwner = true) + "}]"

        val sessions = AdminParsing.parseSessionsFromQuery(body)

        assertEquals(1, sessions.size)
        assertTrue(sessions[0].userDeleted)
        assertEquals("2026-02-01T09:30:00Z", sessions[0].deletedAt)
        // Still a normal chat otherwise (label/title intact, not filtered out).
        assertEquals("Aisha", sessions[0].label)
        assertTrue(sessions[0].deleteNote.startsWith("user deleted"))
    }

    @Test
    fun aClearedChatStillParsesWithEveryMessageStored() {
        val sessions = AdminParsing.parseSessionsFromList(
            list(sessionDoc("s2", "uid-a", clearedAt = "2026-02-02T11:00:00Z"))
        )

        assertEquals(1, sessions.size)
        assertTrue(sessions[0].userCleared)
        assertFalse(sessions[0].userDeleted)
        assertEquals("user cleared (2026-02-02T11:00)", sessions[0].deleteNote)
    }

    @Test
    fun chatsWithoutTheFlagsBehaveExactlyLikeBefore() {
        val sessions = AdminParsing.parseSessionsFromList(list(sessionDoc("s3", "uid-a")))

        assertEquals(1, sessions.size)
        assertFalse(sessions[0].userDeleted)
        assertFalse(sessions[0].userCleared)
        assertEquals("", sessions[0].deleteNote)
        assertEquals("", sessions[0].deletedAt)
        assertEquals("", sessions[0].clearedAt)
    }

    @Test
    fun anExplicitFalseFlagIsNotReportedAsDeleted() {
        val sessions = AdminParsing.parseSessionsFromList(
            list(sessionDoc("s4", "uid-a", deletedByOwner = false))
        )

        assertFalse(sessions[0].userDeleted)
        assertEquals("", sessions[0].deleteNote)
    }

    // ------------------------------------------------------------- counting ----

    @Test
    fun userDeletedCountCountsOnlyTheDeletedOnes() {
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                sessionDoc("s1", "uid-a"),
                sessionDoc("s2", "uid-a", deletedByOwner = true),
                sessionDoc("s3", "uid-a", deletedByOwner = true),
                sessionDoc("s4", "uid-a", clearedAt = "2026-02-02T11:00:00Z"),
            )
        )

        assertEquals(2, AdminParsing.userDeletedCount(sessions))
        assertEquals(0, AdminParsing.userDeletedCount(emptyList()))
    }

    @Test
    fun mergeUsersReportsTheDeletedChatsPerUser() {
        val profiles = AdminParsing.parseUserProfiles(list(userDoc("uid-a", "Aisha Fan")))
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                sessionDoc("s1", "uid-a"),
                sessionDoc("s2", "uid-a", deletedByOwner = true),
                sessionDoc("s3", "uid-z", deletedByOwner = true), // no profile doc
            )
        )

        val users = AdminParsing.mergeUsers(profiles, sessions)

        val aisha = users.first { it.uid == "uid-a" }
        // A deleted chat is still a stored chat, so it is counted in both numbers.
        assertEquals(2, aisha.sessionCount)
        assertEquals(1, aisha.deletedCount)

        val ghost = users.first { it.uid == "uid-z" }
        assertEquals(1, ghost.sessionCount)
        assertEquals(1, ghost.deletedCount)
    }

    @Test
    fun mergeUsersKeepsTheDeletedCountAtZeroForUntouchedUsers() {
        val profiles = AdminParsing.parseUserProfiles(list(userDoc("uid-a", "Aisha Fan")))
        val sessions = AdminParsing.parseSessionsFromList(list(sessionDoc("s1", "uid-a")))

        val users = AdminParsing.mergeUsers(profiles, sessions)

        assertEquals(0, users[0].deletedCount)
    }
}
