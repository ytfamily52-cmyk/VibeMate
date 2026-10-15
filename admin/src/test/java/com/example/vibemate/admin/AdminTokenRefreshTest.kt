package com.example.vibemate.admin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authentication contract of [AdminStore] — the bug that made a perfectly
 * valid admin account look like a non-admin.
 *
 * Firestore answers a request WITHOUT a token with HTTP **403** (the same code
 * it uses for "the security rules denied this"), NOT 401. So when the panel was
 * opened from a saved login and handed the store only a refresh token, its very
 * first call went out unauthenticated and the owner saw
 * "Firestore rules ne ye account admin nahi maana (403)" even though the rules
 * listed the account. The store must therefore refresh BEFORE the first request.
 */
class AdminTokenRefreshTest {

    @Test
    fun aStoreWithoutAnIdTokenMustRefreshFirst() {
        // Exactly what the panel used to build: refresh token, no ID token.
        val store = AdminStore(refreshToken = "refresh-token")

        assertTrue(store.needsTokenBeforeFirstCall())
    }

    @Test
    fun aStoreThatAlreadyHasAnIdTokenCallsStraightAway() {
        val store = AdminStore(idToken = "id-token", refreshToken = "refresh-token")

        assertFalse(store.needsTokenBeforeFirstCall())
    }

    @Test
    fun withNoSessionAtAllThereIsNothingToRefresh() {
        val store = AdminStore()

        assertFalse(store.needsTokenBeforeFirstCall())
    }

    @Test
    fun anEmptyRefreshTokenCannotRescueAMissingIdToken() {
        // A blank refresh token would make auth.refresh() fail — the store must
        // not pretend it can authenticate.
        val store = AdminStore(refreshToken = "")

        assertFalse(store.needsTokenBeforeFirstCall())
    }

    @Test
    fun theIdentityUsedForErrorHintsSurvives() {
        val store = AdminStore(
            refreshToken = "refresh-token",
            signInEmail = "ngkaman143@gmail.com",
            signInUid = "uJlZuXOhh7T1SDloSb0ffkFtMEp1",
        )

        assertTrue(store.signInEmail == "ngkaman143@gmail.com")
        assertTrue(store.signInUid == "uJlZuXOhh7T1SDloSb0ffkFtMEp1")
    }
}