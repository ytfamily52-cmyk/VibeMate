package com.example.vibemate.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "User ki SAARI chats admin app me dikhni chahiye" — the pagination contract.
 *
 * Two fixed-`limit` queries used to silently hide data:
 *  1. the chats screen ran `:runQuery` with `limit = PAGE_SIZE` and never asked
 *     for the next page, so a user with more than 300 chats lost every older one
 *     (while the users list still counted them all);
 *  2. deleting such a user left the 301st+ `call_records` behind, because the
 *     call-record query had the same fixed limit.
 *
 * The chats screen now filters the fully-paged collection scan ([sessionsOfOwner])
 * and the call query walks its pages with `offset` ([equalityQuery] +
 * [AdminParsing.hasAnotherPage]). These tests pin both contracts — fully offline,
 * no network, no device, no cost.
 */
class AdminPaginationTest {

    private val prefix = "projects/vibemate-3b75b/databases/(default)/documents"

    /** A `chat_sessions/{id}` doc as the APP writes it. */
    private fun sessionDoc(
        id: String,
        owner: String,
        updated: String = "2026-01-03T10:00:00Z",
        deletedByOwner: Boolean? = null,
    ): String {
        var fields = "\"owner_id\":{\"stringValue\":\"$owner\"}," +
            "\"title\":{\"stringValue\":\"Hi\"}," +
            "\"persona\":{\"stringValue\":\"girlfriend\"}," +
            "\"mate_name\":{\"stringValue\":\"Aisha\"}," +
            "\"private_mode\":{\"booleanValue\":false}," +
            "\"created_at\":{\"timestampValue\":\"2026-01-01T10:00:00Z\"}," +
            "\"updated_at\":{\"timestampValue\":\"$updated\"}"
        if (deletedByOwner != null) {
            fields += ",\"deleted_by_owner\":{\"booleanValue\":$deletedByOwner}"
        }
        return "{\"name\":\"$prefix/chat_sessions/$id\",\"fields\":{$fields}}"
    }

    private fun list(vararg docs: String): String =
        "{\"documents\":[" + docs.joinToString(",") + "]}"

    // -------------------------------------------------------- all chats load ---

    @Test
    fun everyChatOfTheOwnerIsKeptEvenPastTheOldThreeHundredLimit() {
        // 450 chats of one user: the old fixed-limit query dropped #301 and up.
        val docs = (1..450).map { sessionDoc("s$it", "uid-a") }.toTypedArray()
        val sessions = AdminParsing.parseSessionsFromList(list(*docs))

        val mine = AdminParsing.sessionsOfOwner(sessions, "uid-a")

        assertEquals(450, mine.size)
        // Nothing lost, nothing duplicated.
        assertEquals(450, mine.map { it.id }.toSet().size)
    }

    @Test
    fun aUsersChatsExcludeOtherOwnersAndIncludeSoftDeletedOnes() {
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                sessionDoc("mine1", "uid-a"),
                // The user removed this from their app — still stored, still shown.
                sessionDoc("mine2", "uid-a", deletedByOwner = true),
                sessionDoc("other", "uid-b"),
                sessionDoc("orphan", ""),
            )
        )

        val mine = AdminParsing.sessionsOfOwner(sessions, "uid-a")
        assertEquals(listOf("mine1", "mine2"), mine.map { it.id }.sorted())
        assertTrue(mine.any { it.userDeleted })

        // The orphan bucket is its own "owner" (empty id).
        assertEquals(listOf("orphan"), AdminParsing.sessionsOfOwner(sessions, "").map { it.id })
    }

    @Test
    fun chatsComeBackNewestFirst() {
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                sessionDoc("old", "uid-a", updated = "2026-01-01T00:00:00Z"),
                sessionDoc("new", "uid-a", updated = "2026-06-01T00:00:00Z"),
                sessionDoc("mid", "uid-a", updated = "2026-03-01T00:00:00Z"),
            )
        )

        val mine = AdminParsing.sessionsOfOwner(sessions, "uid-a")
        assertEquals(listOf("new", "mid", "old"), mine.map { it.id })
    }

    // --------------------------------------------------- query + paging loop ---

    @Test
    fun theFirstPageAsksForNoOffset() {
        val query = AdminParsing
            .equalityQuery("call_records", "user_id", "uid-a", 0, 300)
            .getAsJsonObject("structuredQuery")

        val filter = query.getAsJsonObject("where").getAsJsonObject("fieldFilter")
        assertEquals(
            "call_records",
            query.getAsJsonArray("from")[0].asJsonObject.get("collectionId").asString,
        )
        assertEquals("user_id", filter.getAsJsonObject("field").get("fieldPath").asString)
        assertEquals("EQUAL", filter.get("op").asString)
        assertEquals("uid-a", filter.getAsJsonObject("value").get("stringValue").asString)
        assertEquals(300, query.get("limit").asInt)
        // offset 0 is left out, so the very first request stays unchanged.
        assertFalse(query.has("offset"))
    }

    @Test
    fun laterPagesCarryTheirOffset() {
        val query = AdminParsing
            .equalityQuery("chat_sessions", "owner_id", "uid-a", 600, 300)
            .getAsJsonObject("structuredQuery")

        assertEquals(600, query.get("offset").asInt)
        assertEquals(300, query.get("limit").asInt)
    }

    @Test
    fun aFullPageMeansThereMayBeMore() {
        // 300 of 300 returned, page 1 of 40 -> fetch the next page.
        assertTrue(AdminParsing.hasAnotherPage(300, 300, 1, 40))
    }

    @Test
    fun aShortPageIsTheLastOne() {
        // Fewer rows than the page size -> nothing left, whatever the counter says.
        assertFalse(AdminParsing.hasAnotherPage(17, 300, 1, 40))
        assertFalse(AdminParsing.hasAnotherPage(0, 300, 1, 40))
    }

    @Test
    fun theSafetyCapStopsTheLoop() {
        // Still a full page, but the cap is reached -> stop (never hang).
        assertFalse(AdminParsing.hasAnotherPage(300, 300, 40, 40))
    }
}
