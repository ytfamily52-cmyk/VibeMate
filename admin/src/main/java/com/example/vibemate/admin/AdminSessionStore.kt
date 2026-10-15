package com.example.vibemate.admin

import android.content.Context

/**
 * Persists the admin login so the panel reopens without asking for the password
 * again (the refresh token is exchanged for a fresh ID token on launch).
 *
 * Stored in the admin app's own private SharedPreferences — never in the main
 * app's "vibemate_session" store, so the two logins stay independent.
 */
class AdminSessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("vibemate_admin_session", Context.MODE_PRIVATE)

    fun save(session: AdminAuthSession) {
        prefs.edit()
            .putString("id_token", session.idToken)
            .putString("refresh_token", session.refreshToken)
            .putString("uid", session.uid)
            .putString("email", session.email)
            .putLong("saved_at", System.currentTimeMillis())
            .apply()
    }

    /** True when a refresh token is stored (auto-login is possible). */
    fun hasSession(): Boolean = refreshToken().isNotEmpty()

    fun refreshToken(): String = prefs.getString("refresh_token", "").orEmpty()

    /**
     * Cached ID token of the last sign-in / refresh, handed to [AdminStore] so
     * its VERY FIRST Firestore call is authenticated.
     *
     * This matters because Firestore answers a request WITHOUT a token with
     * HTTP 403 — the same code it uses for a rules denial — so an unauthenticated
     * first call used to look exactly like "this account is not an admin".
     */
    fun idToken(): String = prefs.getString("id_token", "").orEmpty()

    /** E-mail of the last successful sign-in (used to prefill the form). */
    fun email(): String = prefs.getString("email", "").orEmpty()

    /** Firebase uid of the last successful sign-in (used for admin hints). */
    fun uid(): String = prefs.getString("uid", "").orEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }
}