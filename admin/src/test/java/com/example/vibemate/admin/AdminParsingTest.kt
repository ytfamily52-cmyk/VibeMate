package com.example.vibemate.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for the admin app's Firestore parsing + merging.
 *
 * These cover the shape of real answers from the Firestore REST API
 * (collection list, :runQuery and one document), the users ∪ session-owners
 * merge that builds the users screen, and the deletion-relevant document ids.
 * No device, no network, no cost.
 */
class AdminParsingTest {

    private val prefix = "projects/vibemate-3b75b/databases/(default)/documents"

    private fun userDoc(uid: String, name: String, created: String = "2026-01-02T03:04:05Z"): String =
        """{"name":"$prefix/users/$uid","fields":{""" +
            """"display_name":{"stringValue":"$name"},""" +
            """"created_at":{"timestampValue":"$created"}}}"""

    private fun sessionDoc(
        id: String,
        owner: String,
        updated: String = "2026-01-03T10:00:00Z",
        persona: String = "girlfriend",
        private: Boolean = false,
        title: String = "Hi",
    ): String =
        """{"name":"$prefix/chat_sessions/$id","fields":{""" +
            """"owner_id":{"stringValue":"$owner"},""" +
            """"title":{"stringValue":"$title"},""" +
            """"persona":{"stringValue":"$persona"},""" +
            """"mate_name":{"stringValue":"Aisha"},""" +
            """"private_mode":{"booleanValue":$private},""" +
            """"created_at":{"timestampValue":"2026-01-01T10:00:00Z"},""" +
            """"updated_at":{"timestampValue":"$updated"}}}"""

    private fun list(vararg docs: String): String =
        "{\"documents\":[" + docs.joinToString(",") + "]}"

    private fun query(vararg docs: String): String =
        "[" + docs.joinToString(",") { "{\"document\":$it}" } + ",{\"readTime\":\"2026-01-05T00:00:00Z\"}]"

    // ---------------------------------------------------------------- users ---

    @Test
    fun userProfilesParseNameJoinDateAndUid() {
        val users = AdminParsing.parseUserProfiles(
            list(userDoc("uid-a", "Aisha Fan"), userDoc("uid-b", "Rahul"))
        )

        assertEquals(2, users.size)
        assertEquals("uid-a", users[0].uid)
        assertEquals("Aisha Fan", users[0].displayName)
        assertEquals("2026-01-02T03:04:05Z", users[0].createdAt)
        assertTrue(users[0].hasProfile)
        assertEquals("Rahul", users[1].displayName)
    }

    @Test
    fun userProfilesSurviveMissingAndBrokenFields() {
        val body = list(
            """{"name":"$prefix/users/uid-c","fields":{}}""",
            """{"fields":{"display_name":{"stringValue":"no name"}}}""",
            """{"name":"$prefix/users/uid-d"}""",
        )

        val users = AdminParsing.parseUserProfiles(body)
        // The doc without a `name` is skipped; a doc with no fields still shows.
        assertEquals(2, users.size)
        assertEquals("uid-c", users[0].uid)
        assertEquals("", users[0].displayName)
        assertEquals("User uid-c", users[0].label)
        assertEquals("uid-d", users[1].uid)
        assertEquals("User uid-d", users[1].label)
    }

    @Test
    fun emptyOrGarbageBodyYieldsNothing() {
        assertTrue(AdminParsing.parseUserProfiles("").isEmpty())
        assertTrue(AdminParsing.parseUserProfiles("not json").isEmpty())
        assertTrue(AdminParsing.parseMessages("{\"documents\":null}").isEmpty())
        assertTrue(AdminParsing.parseSessionsFromQuery("[]").isEmpty())
    }

    // ------------------------------------------------------------- sessions ---

    @Test
    fun sessionListParsesOwnerPersonaAndPrivateFlag() {
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                sessionDoc("s1", "uid-a"),
                sessionDoc("s2", "uid-a", persona = "crush", private = true),
            )
        )

        assertEquals(2, sessions.size)
        assertEquals("uid-a", sessions[0].ownerId)
        assertEquals("girlfriend", sessions[0].persona)
        assertEquals("Girlfriend", sessions[0].personaLabel)
        assertEquals("Aisha", sessions[0].label)
        assertEquals("Hi", sessions[0].title)
        assertFalse(sessions[0].privateMode)
        assertTrue(sessions[1].privateMode)
        assertEquals("Crush", sessions[1].personaLabel)
    }

    @Test
    fun sessionQueryParsesRunQueryEnvelope() {
        val sessions = AdminParsing.parseSessionsFromQuery(
            query(sessionDoc("s9", "uid-b", persona = "boyfriend"))
        )

        assertEquals(1, sessions.size)
        assertEquals("s9", sessions[0].id)
        assertEquals("uid-b", sessions[0].ownerId)
        assertEquals("Boyfriend", sessions[0].personaLabel)
    }

    @Test
    fun bothGayPersonasGetReadableLabels() {
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                sessionDoc("s10", "uid-c", persona = "gay"),
                sessionDoc("s11", "uid-c", persona = "gay_boy"),
                sessionDoc("s12", "uid-c", persona = "gay_girl"),
            )
        )

        val byId = sessions.associateBy { it.id }
        assertEquals(3, sessions.size)
        // "gay" is the legacy boy-gay id (sessions stored by an older build), so it
        // must read exactly like gay_boy instead of showing a raw id.
        assertEquals("gay", byId.getValue("s10").persona)
        assertEquals("Boy Gay", byId.getValue("s10").personaLabel)
        // "Gay" is one relation in the app but two personas: the label says which
        // same-sex couple the mate is.
        assertEquals("Boy Gay", byId.getValue("s11").personaLabel)
        assertEquals("Girl Gay", byId.getValue("s12").personaLabel)
        assertEquals("Aisha", byId.getValue("s12").label)
    }

    @Test
    fun sessionsSortNewestFirstAndFallBackToCreatedAt() {
        val sessions = AdminParsing.sortSessionsNewestFirst(
            AdminParsing.parseSessionsFromList(
                list(
                    sessionDoc("old", "uid-a", updated = "2026-01-01T00:00:00Z"),
                    sessionDoc("new", "uid-a", updated = "2026-01-09T00:00:00Z"),
                    """{"name":"$prefix/chat_sessions/mid","fields":{"owner_id":{"stringValue":"uid-a"},""" +
                        """"created_at":{"timestampValue":"2026-01-05T00:00:00Z"}}}""",
                )
            )
        )

        assertEquals(listOf("new", "mid", "old"), sessions.map { it.id })
    }

    // ------------------------------------------------------------- messages ---

    @Test
    fun messagesParseAttachmentsAndSortOldestFirst() {
        val body = list(
            """{"name":"$prefix/chat_sessions/s1/messages/m2","fields":{"role":{"stringValue":"vibemate"},"content":{"stringValue":"Haan bolo"},"created_at":{"timestampValue":"2026-01-02T10:00:05Z"}}}""",
            """{"name":"$prefix/chat_sessions/s1/messages/m1","fields":{"role":{"stringValue":"user"},"content":{"stringValue":"ye photo dekho"},"type":{"stringValue":"IMAGE"},"file_name":{"stringValue":"p.jpg"},"data":{"stringValue":"AAAA"},"created_at":{"timestampValue":"2026-01-02T10:00:00Z"}}}""",
        )

        val messages = AdminParsing.parseMessages(body)
        assertEquals(listOf("m1", "m2"), messages.map { it.id })

        val photo = messages[0]
        assertTrue(photo.fromUser)
        assertEquals("IMAGE", photo.type)
        assertEquals("p.jpg", photo.fileName)
        assertEquals("AAAA", photo.data)
        assertTrue(photo.hasPhoto)
        assertEquals("Photo", photo.attachmentLabel)

        assertFalse(messages[1].fromUser)
        assertFalse(messages[1].hasPhoto)
        assertEquals("", messages[1].attachmentLabel)
    }

    @Test
    fun documentIdsWorkForBothListAndQueryAnswers() {
        val fromList = AdminParsing.parseDocumentIds(
            list(
                """{"name":"$prefix/call_records/c1"}""",
                """{"name":"$prefix/call_records/c2"}""",
            )
        )
        val fromQuery = AdminParsing.parseDocumentIds(
            query("""{"name":"$prefix/call_records/c3"}""")
        )

        assertEquals(listOf("c1", "c2"), fromList)
        assertEquals(listOf("c3"), fromQuery)
    }

    // -------------------------------------------------------- merge / order ---

    @Test
    fun mergeUsersCountsChatsAndAddsOwnersWithoutProfile() {
        val profiles = AdminParsing.parseUserProfiles(
            list(userDoc("uid-a", "Aisha Fan"), userDoc("uid-b", "Rahul"))
        )
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                sessionDoc("s1", "uid-a"),
                sessionDoc("s2", "uid-a", private = true),
                sessionDoc("s3", "uid-z"), // owner without a profile document
            )
        )

        val users = AdminParsing.mergeUsers(profiles, sessions)
        assertEquals(3, users.size)

        val aisha = users.first { it.uid == "uid-a" }
        assertEquals(2, aisha.sessionCount)
        assertEquals(1, aisha.privateCount)
        assertTrue(aisha.hasProfile)
        assertEquals("Aisha Fan", aisha.label)

        val rahul = users.first { it.uid == "uid-b" }
        assertEquals(0, rahul.sessionCount)

        val ghost = users.first { it.uid == "uid-z" }
        assertEquals(1, ghost.sessionCount)
        assertFalse(ghost.hasProfile)
        assertEquals("User uid-z", ghost.label)

        // The user with the most chats sits on top.
        assertEquals("uid-a", users[0].uid)
    }

    @Test
    fun mergeUsersKeepsTheOwnerlessBucketLast() {
        val profiles = AdminParsing.parseUserProfiles(list(userDoc("uid-a", "Aisha Fan")))
        val sessions = AdminParsing.parseSessionsFromList(
            list(
                sessionDoc("s1", "uid-a"),
                sessionDoc("o1", ""),
                sessionDoc("o2", ""),
            )
        )

        val users = AdminParsing.mergeUsers(profiles, sessions)
        assertEquals(listOf("uid-a", ""), users.map { it.uid })

        val orphan = users.last()
        assertTrue(orphan.isOrphan)
        assertEquals("(no owner)", orphan.label)
        assertEquals(2, orphan.sessionCount)
    }

    @Test
    fun pageTokenEndsTheLoopAtTheLastPage() {
        assertEquals(
            "tok-2",
            AdminParsing.nextPageToken("{\"documents\":[],\"nextPageToken\":\"tok-2\"}"),
        )
        assertNull(AdminParsing.nextPageOrNull("{\"documents\":[]}"))
        assertNull(AdminParsing.nextPageOrNull("not json"))
    }

    // --------------------------------------------------------------- config ---

    @Test
    fun adminEmailAllowListIsCaseInsensitive() {
        assertFalse(AdminConfig.isAdminEmail(""))
        assertFalse(AdminConfig.isAdminEmail("   "))
        // Whatever the owner puts in ADMIN_EMAILS, matching ignores case — and an
        // empty list deliberately allows any account (the rules still gate it).
        for (email in AdminConfig.ADMIN_EMAILS) {
            assertTrue(AdminConfig.isAdminEmail(email))
            assertTrue(AdminConfig.isAdminEmail(email.uppercase()))
        }
    }
}