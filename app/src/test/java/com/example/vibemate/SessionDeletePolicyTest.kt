package com.example.vibemate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Delete chat" / "Clear chat" must never remove anything from Firestore any more.
 *
 * The user's delete only FLAGS the chat (`deleted_by_owner` + `deleted_at`) and the
 * user's clear only writes `cleared_at`, so the chat and every message stay stored
 * for the VibeMate admin panel — the admin's delete is the ONLY final one. The app
 * hides the flagged chat and the cleared history through [SessionDeletePolicy].
 *
 * Pure JVM test: no device, no network, no cost.
 */
class SessionDeletePolicyTest {

    private fun session(deletedByOwner: Boolean = false, clearedAt: String = ""): SessionDto {
        val s = SessionDto()
        s.id = "s1"
        s.title = "Hi"
        s.deletedByOwner = deletedByOwner
        s.clearedAt = clearedAt
        return s
    }

    @Test
    fun aFreshChatHasNoFlagsAtAll() {
        val s = SessionDto()

        assertFalse(s.deletedByOwner)
        assertEquals("", s.clearedAt)
        assertTrue(SessionDeletePolicy.visibleToOwner(s))
    }

    @Test
    fun aChatTheUserDeletedDisappearsFromTheOwner() {
        assertFalse(SessionDeletePolicy.visibleToOwner(session(deletedByOwner = true)))
    }

    @Test
    fun aNormalChatStaysVisible() {
        assertTrue(SessionDeletePolicy.visibleToOwner(session()))
        assertTrue(SessionDeletePolicy.visibleToOwner(session(clearedAt = "2026-02-02T11:00:00Z")))
    }

    @Test
    fun withoutAClearMarkerEveryStoredMessageIsVisible() {
        assertTrue(SessionDeletePolicy.visibleToOwner("2020-01-01T00:00:00Z", ""))
        assertTrue(SessionDeletePolicy.visibleToOwner("", ""))
    }

    @Test
    fun clearingHidesOnlyTheMessagesWrittenBeforeIt() {
        val clearedAt = "2026-02-02T11:00:00Z"

        // Old context: hidden from the app (still stored for the admin panel).
        assertFalse(SessionDeletePolicy.visibleToOwner("2026-02-01T09:00:00Z", clearedAt))

        // The marker itself and everything newer is the current conversation.
        assertTrue(SessionDeletePolicy.visibleToOwner(clearedAt, clearedAt))
        assertTrue(SessionDeletePolicy.visibleToOwner("2026-02-03T08:00:00Z", clearedAt))
    }

    @Test
    fun aMessageWithoutATimestampNeverLeaksBackAfterAClear() {
        assertFalse(SessionDeletePolicy.visibleToOwner("", "2026-02-02T11:00:00Z"))
    }

    @Test
    fun fractionalSecondsNeverHideAMessageSentRightAfterTheClear() {
        // Instant.now() / Firestore emit variable fractional precision, and
        // lexicographically '.' < 'Z' — so "…11:00:00.500Z" used to compare
        // BELOW the clear marker "…11:00:00Z" and a message sent a split
        // second AFTER the clear was wrongly hidden. canonical() fixes that.
        val clearedAt = "2026-02-02T11:00:00Z"
        assertTrue(SessionDeletePolicy.visibleToOwner("2026-02-02T11:00:00.500Z", clearedAt))
        assertTrue(SessionDeletePolicy.visibleToOwner("2026-02-02T11:00:00.999999999Z", clearedAt))
        // A fraction of a second BEFORE the clear is still (correctly) hidden.
        assertFalse(SessionDeletePolicy.visibleToOwner("2026-02-02T10:59:59.999Z", clearedAt))
        // Both sides fractional: compare on whole seconds only.
        val clearedFractional = "2026-02-02T11:00:00.123456Z"
        assertFalse(SessionDeletePolicy.visibleToOwner("2026-02-02T10:59:59.9Z", clearedFractional))
        assertTrue(SessionDeletePolicy.visibleToOwner("2026-02-02T11:00:00.9Z", clearedFractional))
    }

    @Test
    fun theFieldNamesMatchWhatTheAdminPanelReads() {
        assertEquals("deleted_by_owner", SessionDeletePolicy.DELETED_BY_OWNER)
        assertEquals("deleted_at", SessionDeletePolicy.DELETED_AT)
        assertEquals("cleared_at", SessionDeletePolicy.CLEARED_AT)
    }
}
