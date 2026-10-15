package com.example.vibemate.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Latest chat sabse upar" — the ordering contract of the admin app.
 *
 * The app writes `updated_at` / `created_at` from `Instant.now().toString()`, whose
 * fractional part is VARIABLE width (".5", ".834", ".834745", or none at all). Raw
 * string comparison then breaks INSIDE the same second — "…:00Z" sorts above
 * "…:00.5Z" ('Z' > '.') and "…:00.5Z" above "…:00.55Z" — so the truly latest chat
 * was not always first. [AdminParsing.canonicalTimestamp] pads the fraction to a
 * fixed 9 digits, and BOTH lists are ordered with it:
 *  - the chats list (one user's chats), newest first;
 *  - the users list, the user with the latest chat on top.
 *
 * Runs fully offline — no network, no device, no cost.
 */
class AdminOrderingTest {

    private val prefix = "projects/vibemate-3b75b/databases/(default)/documents"

    private fun sessionDoc(id: String, owner: String, updated: String): String =
        "{\"name\":\"$prefix/chat_sessions/$id\",\"fields\":{" +
            "\"owner_id\":{\"stringValue\":\"$owner\"}," +
            "\"title\":{\"stringValue\":\"Hi\"}," +
            "\"persona\":{\"stringValue\":\"girlfriend\"}," +
            "\"mate_name\":{\"stringValue\":\"Aisha\"}," +
            "\"private_mode\":{\"booleanValue\":false}," +
            "\"created_at\":{\"timestampValue\":\"2026-01-01T00:00:00Z\"}," +
            "\"updated_at\":{\"timestampValue\":\"$updated\"}}}"

    private fun userDoc(uid: String, name: String): String =
        "{\"name\":\"$prefix/users/$uid\",\"fields\":{" +
            "\"display_name\":{\"stringValue\":\"$name\"}," +
            "\"created_at\":{\"timestampValue\":\"2026-01-02T03:04:05Z\"}}}"

    private fun list(vararg docs: String): String =
        "{\"documents\":[" + docs.joinToString(",") + "]}"

    // ------------------------------------------------- timestamp canonical -----

    @Test
    fun fractionsArePaddedToAFixedWidth() {
        assertEquals(
            "2026-01-02T10:00:00.000000000Z",
            AdminParsing.canonicalTimestamp("2026-01-02T10:00:00Z"),
        )
        assertEquals(
            "2026-01-02T10:00:00.500000000Z",
            AdminParsing.canonicalTimestamp("2026-01-02T10:00:00.5Z"),
        )
        assertEquals(
            "2026-01-02T10:00:00.834745000Z",
            AdminParsing.canonicalTimestamp("2026-01-02T10:00:00.834745Z"),
        )
        assertEquals(
            "2026-01-02T10:00:00.834745123Z",
            AdminParsing.canonicalTimestamp("2026-01-02T10:00:00.834745123Z"),
        )
        assertEquals("", AdminParsing.canonicalTimestamp(""))
        assertEquals("", AdminParsing.canonicalTimestamp("   "))
    }

    @Test
    fun theTrulyLatestTimestampIsTheGreatestAfterCanonicalisation() {
        val whole = AdminParsing.canonicalTimestamp("2026-01-02T10:00:00Z")
        val half = AdminParsing.canonicalTimestamp("2026-01-02T10:00:00.5Z")
        val halfMore = AdminParsing.canonicalTimestamp("2026-01-02T10:00:00.55Z")

        // Raw strings would say whole > half > halfMore — the OPPOSITE of the truth.
        assertTrue(halfMore > half)
        assertTrue(half > whole)
    }

    // --------------------------------------------------------- chats list ------

    @Test
    fun theLatestChatIsFirstEvenInsideTheSameSecond() {
        val sessions = AdminParsing.sortSessionsNewestFirst(
            AdminParsing.parseSessionsFromList(
                list(
                    sessionDoc("oldest", "uid-a", "2026-01-02T10:00:00Z"),
                    sessionDoc("newest", "uid-a", "2026-01-02T10:00:00.55Z"),
                    sessionDoc("middle", "uid-a", "2026-01-02T10:00:00.5Z"),
                )
            )
        )

        assertEquals(listOf("newest", "middle", "oldest"), sessions.map { it.id })
    }

    @Test
    fun messagesStayOldestFirstInsideTheSameSecond() {
        val body = list(
            "{\"name\":\"$prefix/chat_sessions/s1/messages/m2\",\"fields\":{\"role\":{\"stringValue\":\"vibemate\"},\"created_at\":{\"timestampValue\":\"2026-01-02T10:00:00.55Z\"}}}",
            "{\"name\":\"$prefix/chat_sessions/s1/messages/m1\",\"fields\":{\"role\":{\"stringValue\":\"user\"},\"created_at\":{\"timestampValue\":\"2026-01-02T10:00:00Z\"}}}",
            "{\"name\":\"$prefix/chat_sessions/s1/messages/m3\",\"fields\":{\"role\":{\"stringValue\":\"vibemate\"},\"created_at\":{\"timestampValue\":\"2026-01-02T10:00:00.5Z\"}}}",
        )

        val messages = AdminParsing.parseMessages(body)
        assertEquals(listOf("m1", "m3", "m2"), messages.map { it.id })
    }

    // -------------------------------------------------------- users list ------

    @Test
    fun theUserWithTheLatestChatComesFirst() {
        val profiles = AdminParsing.parseUserProfiles(
            list(userDoc("uid-heavy", "Heavy"), userDoc("uid-fresh", "Fresh"))
        )
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                // uid-heavy has many OLD chats ...
                sessionDoc("h1", "uid-heavy", "2026-01-01T00:00:00Z"),
                sessionDoc("h2", "uid-heavy", "2026-01-02T00:00:00Z"),
                sessionDoc("h3", "uid-heavy", "2026-01-03T00:00:00Z"),
                // ... uid-fresh has one brand-new chat.
                sessionDoc("f1", "uid-fresh", "2026-06-01T00:00:00Z"),
            )
        )

        val users = AdminParsing.mergeUsers(profiles, sessions)

        assertEquals("uid-fresh", users[0].uid)
        assertEquals("2026-06-01T00:00:00Z", users[0].lastChatAt)
        assertEquals(1, users[0].sessionCount)
    }

    @Test
    fun theLatestChatInsideTheSameSecondAlsoWins() {
        val profiles = AdminParsing.parseUserProfiles(
            list(userDoc("uid-early", "Early"), userDoc("uid-late", "Late"))
        )
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                sessionDoc("e1", "uid-early", "2026-01-02T10:00:00.5Z"),
                sessionDoc("l1", "uid-late", "2026-01-02T10:00:00.55Z"),
            )
        )

        val users = AdminParsing.mergeUsers(profiles, sessions)

        assertEquals("uid-late", users[0].uid)
    }

    @Test
    fun theOrphanBucketStaysLastEvenWhenItHasTheNewestChat() {
        val profiles = AdminParsing.parseUserProfiles(list(userDoc("uid-a", "Aisha")))
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                sessionDoc("mine", "uid-a", "2026-01-01T00:00:00Z"),
                sessionDoc("orphan", "", "2026-12-31T00:00:00Z"),
            )
        )

        val users = AdminParsing.mergeUsers(profiles, sessions)

        assertEquals(listOf("uid-a", ""), users.map { it.uid })
    }

    @Test
    fun aUserWithoutChatsHasNoLastChatAndSortsAfterActiveOnes() {
        val profiles = AdminParsing.parseUserProfiles(
            list(userDoc("uid-idle", "Idle"), userDoc("uid-active", "Active"))
        )
        val sessions = AdminParsing.parseSessionsFromList(
            list(sessionDoc("a1", "uid-active", "2026-01-01T00:00:00Z"))
        )

        val users = AdminParsing.mergeUsers(profiles, sessions)

        assertEquals(listOf("uid-active", "uid-idle"), users.map { it.uid })
        assertEquals("", users.first { it.uid == "uid-idle" }.lastChatAt)
    }
}
