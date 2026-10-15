package com.example.vibemate.admin

/**
 * Every online setting the admin app needs.
 *
 * These mirror the main app's `AppConfig.kt` — if the Firebase project is ever
 * changed, update BOTH files (the admin app deliberately has no
 * google-services.json: it talks to Firebase over plain REST, exactly like the
 * main app does in VibeMateApi.kt / FirebaseAuth.kt).
 */
object AdminConfig {

    /** Firebase → Project settings → General → "Web API key" (same as the app). */
    const val FIREBASE_API_KEY: String = "AIzaSyCj6r7qwZBnUj5G86nUtORYXz0CiCnNONE"

    /** Firebase → Project settings → General → "Project ID" (same as the app). */
    const val FIREBASE_PROJECT_ID: String = "vibemate-3b75b"

    /**
     * Accounts allowed INTO the admin app (matched case-insensitively).
     *
     * `ngkaman143@gmail.com` (uid uJlZuXOhh7T1SDloSb0ffkFtMEp1) is the owner's
     * admin account — it is listed here for an early, friendly error, and in
     * `firestore.rules` → `isAdmin()` (e-mail + uid) which is the REAL gate,
     * plus as a `admins/{uid}` document in Firestore (the no-redeploy way).
     *
     * An EMPTY list means "any valid Firebase account may try" and lets the
     * security rules alone decide — handy when the admin account changes.
     * Keep this list identical to `firestore.rules` → `isAdmin()`.
     */
    val ADMIN_EMAILS: List<String> = listOf("ngkaman143@gmail.com")

    /** Is [email] in [ADMIN_EMAILS]? (empty list = allow any e-mail) */
    fun isAdminEmail(email: String): Boolean {
        val clean = email.trim().lowercase()
        if (clean.isEmpty()) return false
        if (ADMIN_EMAILS.isEmpty()) return true
        return ADMIN_EMAILS.any { it.trim().lowercase() == clean }
    }

    /**
     * Copy-paste instructions that turn [email] / [uid] into an admin. Shown in
     * the "403 — not an admin" message so the fix needs no guessing.
     */
    fun grantHint(email: String, uid: String): String {
        val who = email.ifEmpty { "(this account)" }
        val id = uid.ifEmpty { "(uid: copy it from Authentication → Users)" }
        return "Signed in as $who" + (if (uid.isNotEmpty()) " · uid $uid" else "") + "\n" +
            "To make this account an admin (any ONE is enough):\n" +
            "1) Firebase Console → Firestore → collection `admins` → Add document\n" +
            "     Document ID: $id\n" +
            "     Field: admin = true (boolean) → Save\n" +
            "     (or)\n" +
            "2) firestore.rules → add '$who' to the isAdmin() email list, then deploy the " +
            "rules again (firebase deploy --only firestore:rules).\n" +
            "Deploying the rules is required — nothing is allowed without it."
    }

    /** Firestore REST root — the same endpoint the app writes through. */
    const val FIRESTORE_BASE: String =
        "https://firestore.googleapis.com/v1/projects/" +
            FIREBASE_PROJECT_ID + "/databases/(default)/documents"

    /** Google Identity Toolkit (Firebase Auth REST) root. */
    const val IDENTITY_BASE: String = "https://identitytoolkit.googleapis.com/v1"

    /** securetoken API — exchanges a refresh token for a fresh ID token. */
    const val REFRESH_URL: String = "https://securetoken.googleapis.com/v1/token"

    /** Documents fetched per page while listing collections. */
    const val PAGE_SIZE: Int = 300

    /**
     * Safety cap: a huge database can never make the admin app hang forever.
     *
     * Every paged read (users, a user's chats, a chat's messages, a user's call
     * records) stops after [MAX_PAGES] pages, so the panel reads at most
     * [MAX_PAGES] × [PAGE_SIZE] = 12,000 documents and can never loop forever.
     * It is deliberately generous: the users screen, the chat list and the
     * message list must show EVERYTHING ("sare chats"), so the cap is only a
     * runaway guard, never a normal truncation point.
     */
    const val MAX_PAGES: Int = 40
}