package com.example.vibemate.admin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException
import java.net.URLEncoder

/** Any failure talking to Firestore (network, HTTP error, rules denied). */
class AdminException(message: String, val httpCode: Int = 0) : Exception(message)

/**
 * Firestore REST client for the admin panel.
 *
 * Reads and deletes the SAME documents the app writes (see VibeMateApi.kt):
 *   users/{uid}                                -> display_name, created_at
 *   chat_sessions/{sid}                        -> owner_id, title, persona,
 *                                                 mate_name, private_mode, ...
 *   chat_sessions/{sid}/messages/{mid}         -> role, content, type, file_name,
 *                                                 data (base64), created_at
 *   call_records/{cid}                         -> user_id, ...
 *
 * Soft delete: "delete chat" / "clear chat" in the APP never remove anything any
 * more — they only write `deleted_by_owner` + `deleted_at` / `cleared_at` on the
 * session, so a chat the user removed from their own phone is still listed here
 * (badge "USER DELETED") with its whole message history. The deletes in this file
 * are the ONLY final ones.
 *
 * Cross-user access is granted by firestore.rules -> isAdmin(); without that the
 * server answers 403 and [AdminException] explains exactly what to fix.
 *
 * Authentication: an ID token lives 1 hour, so a 401 refreshes it ONCE,
 * transparently, and saves the new session through [onSessionRefreshed] — a long
 * review never dies. And because Firestore answers a request WITHOUT a token with
 * 403 (the same code a rules denial uses) — not 401 — a factory with only a
 * refresh token mints an ID token BEFORE the very first request.
 *
 * Every call is blocking and must run off the UI thread.
 */
class AdminStore(
    var idToken: String = "",
    var refreshToken: String = "",
    /** Signed-in admin identity — only used to write helpful error messages. */
    var signInEmail: String = "",
    var signInUid: String = "",
    private val auth: AdminAuth = AdminAuth(),
    private val onSessionRefreshed: (AdminAuthSession) -> Unit = {},
) {

    private val client = OkHttpClient()

    // ---------------------------------------------------------------- users ---

    /** Every `users/{uid}` profile document (paged). */
    fun listUserProfiles(): List<AdminUser> {
        val out = ArrayList<AdminUser>()
        var token: String? = null
        var pages = 0
        do {
            val body = get(collectionUrl("users", token))
            out.addAll(AdminParsing.parseUserProfiles(body))
            token = AdminParsing.nextPageOrNull(body)
            pages++
        } while (token != null && pages < AdminConfig.MAX_PAGES)
        return out
    }

    // ------------------------------------------------------------- sessions ---

    /** Every `chat_sessions` document — used for the per-user chat counts. */
    fun listAllSessions(): List<AdminSession> {
        val out = ArrayList<AdminSession>()
        var token: String? = null
        var pages = 0
        do {
            val body = get(collectionUrl("chat_sessions", token))
            out.addAll(AdminParsing.parseSessionsFromList(body))
            token = AdminParsing.nextPageOrNull(body)
            pages++
        } while (token != null && pages < AdminConfig.MAX_PAGES)
        return out
    }

    /**
     * ALL chats of ONE user (`owner_id == uid`), newest first.
     *
     * Built from [listAllSessions] (the SAME paged scan that powers the per-user
     * chat counts on the users screen) instead of a `:runQuery` with a fixed
     * `limit`. The old query stopped after [AdminConfig.PAGE_SIZE] rows and never
     * asked for the next page, so a user with more than 300 chats silently lost
     * every older one — the users list counted 400 while this screen showed 300.
     * Filtering the full scan keeps the two screens in exact agreement and shows
     * EVERY chat, including the ones the user "deleted" (they are only flagged,
     * see [AdminSession.userDeleted]) and the owner-less ORPHAN bucket ([uid]
     * empty, where the field is missing rather than blank).
     */
    fun listUserSessions(uid: String): List<AdminSession> =
        AdminParsing.sessionsOfOwner(listAllSessions(), uid)

    /** Every message of one chat, oldest first (paged). */
    fun listMessages(sessionId: String): List<AdminMessage> {
        val out = ArrayList<AdminMessage>()
        var token: String? = null
        var pages = 0
        do {
            val body = get(collectionUrl("chat_sessions/$sessionId/messages", token))
            out.addAll(AdminParsing.parseMessages(body))
            token = AdminParsing.nextPageOrNull(body)
            pages++
        } while (token != null && pages < AdminConfig.MAX_PAGES)
        return AdminParsing.sortMessagesOldestFirst(out)
    }

    /**
     * Ids of one user's call records (`where user_id == uid`), PAGED.
     *
     * The query used to carry a single fixed `limit` and stop, so deleting a user
     * left the 301st+ call record behind. It now walks the pages with `offset`
     * until a short page arrives (Firestore returns the results in document-id
     * order when no orderBy is given, so consecutive pages never overlap or skip).
     */
    fun listCallRecordIds(uid: String): List<String> {
        val out = ArrayList<String>()
        var offset = 0
        var pages = 0
        while (true) {
            val body = postJson(
                AdminConfig.FIRESTORE_BASE + ":runQuery",
                AdminParsing.equalityQuery(
                    "call_records", "user_id", uid, offset, AdminConfig.PAGE_SIZE,
                ),
            )
            val page = AdminParsing.parseDocumentIds(body)
            out.addAll(page)
            pages++
            if (!AdminParsing.hasAnotherPage(
                    page.size, AdminConfig.PAGE_SIZE, pages, AdminConfig.MAX_PAGES,
                )
            ) {
                break
            }
            offset += AdminConfig.PAGE_SIZE
        }
        return out
    }

    private fun collectionUrl(path: String, pageToken: String?): String {
        val url = StringBuilder(AdminConfig.FIRESTORE_BASE + "/" + path + "?pageSize=")
            .append(AdminConfig.PAGE_SIZE)
        if (pageToken != null && pageToken.isNotEmpty()) {
            url.append("&pageToken=").append(urlEncode(pageToken))
        }
        return url.toString()
    }

    // -------------------------------------------------------------- deletes ---

    /** Delete one message document. */
    fun deleteMessage(sessionId: String, messageId: String) {
        delete(AdminConfig.FIRESTORE_BASE + "/chat_sessions/" + sessionId + "/messages/" + messageId)
    }

    /**
     * Delete ONE chat — the FINAL delete: every message first, then the session
     * document itself.
     *
     * The app never deletes a chat (it only flags it, see the class comment), so
     * THIS is what makes a chat disappear for good — including the ones whose rows
     * say "USER DELETED".
     */
    fun deleteSession(sessionId: String) {
        for (messageId in messageIds(sessionId)) {
            try {
                deleteMessage(sessionId, messageId)
            } catch (ignored: Exception) {
                // best-effort: one unreadable message must not block the rest
            }
        }
        delete(AdminConfig.FIRESTORE_BASE + "/chat_sessions/" + sessionId)
    }

    /** Delete several chats (long-press → select → Delete). [onStep] = progress. */
    fun deleteSessions(sessionIds: List<String>, onStep: (String) -> Unit = {}) {
        for ((index, sessionId) in sessionIds.withIndex()) {
            onStep("chat ${index + 1}/${sessionIds.size}")
            deleteSession(sessionId)
        }
    }

    /**
     * Delete a WHOLE user: every chat (+ its messages), the call history and the
     * profile document. [onStep] reports progress for the UI.
     *
     * An empty [uid] means the ORPHAN bucket (sessions whose owner_id was wiped by
     * the old replace-style PATCH bug): those chats are removed and no profile is
     * touched.
     *
     * NOTE: this removes the user's Firestore DATA. The Firebase Authentication
     * login itself cannot be removed over REST (that needs the Admin SDK) — delete
     * it in Firebase Console → Authentication → Users if you want it gone too.
     */
    fun deleteUser(uid: String, onStep: (String) -> Unit = {}) {
        val sessions = if (uid.isEmpty()) {
            listAllSessions().filter { it.ownerId.isEmpty() }
        } else {
            listUserSessions(uid)
        }
        onStep("${sessions.size} chats found")

        var done = 0
        for (session in sessions) {
            deleteSession(session.id)
            done++
            onStep("chat $done/${sessions.size} deleted")
        }

        if (uid.isNotEmpty()) {
            val callIds = listCallRecordIds(uid)
            for (callId in callIds) {
                try {
                    delete(AdminConfig.FIRESTORE_BASE + "/call_records/" + callId)
                } catch (ignored: Exception) {
                }
            }
            if (callIds.isNotEmpty()) onStep("${callIds.size} call records deleted")
            delete(AdminConfig.FIRESTORE_BASE + "/users/" + uid)
            onStep("profile deleted")
        }
    }

    /** Message ids of one chat — read before deleting the chat itself. */
    private fun messageIds(sessionId: String): List<String> {
        val out = ArrayList<String>()
        var token: String? = null
        var pages = 0
        do {
            val body = get(collectionUrl("chat_sessions/$sessionId/messages", token))
            out.addAll(AdminParsing.parseDocumentIds(body))
            token = AdminParsing.nextPageOrNull(body)
            pages++
        } while (token != null && pages < AdminConfig.MAX_PAGES)
        return out
    }

    // ----------------------------------------------------------------- HTTP ---

    private fun get(url: String): String = send { token ->
        Request.Builder().url(url).get().header("Authorization", "Bearer " + token)
    }

    private fun postJson(url: String, body: JsonObject): String = send { token ->
        Request.Builder().url(url)
            .post(RequestBody.create(null, body.toString()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
    }

    private fun delete(url: String): String = send { token ->
        Request.Builder().url(url).delete().header("Authorization", "Bearer " + token)
    }

    /**
     * True when the first request must be preceded by a token refresh: there is
     * no ID token yet, but a refresh token can mint one.
     */
    internal fun needsTokenBeforeFirstCall(): Boolean =
        idToken.isEmpty() && refreshToken.isNotEmpty()

    /**
     * Exchanges the refresh token for a fresh ID token and remembers it. A dead
     * refresh token surfaces as a 401 so the screens can send the admin back to
     * the sign-in form instead of hanging.
     */
    private fun refreshSession() {
        val refreshedSession = try {
            auth.refresh(refreshToken)
        } catch (e: Exception) {
            throw AdminException(
                "Session expired — please sign in again. [" + (e.message ?: "") + "]",
                401,
            )
        }
        idToken = refreshedSession.idToken
        refreshToken = refreshedSession.refreshToken
        if (refreshedSession.email.isNotEmpty()) signInEmail = refreshedSession.email
        if (refreshedSession.uid.isNotEmpty()) signInUid = refreshedSession.uid
        onSessionRefreshed(refreshedSession)
    }

    /**
     * Runs an AUTHENTICATED request.
     *
     *  * No ID token yet (the panel was reopened from a saved session) -> fetch
     *    one BEFORE the request. This is essential: Firestore answers a request
     *    without a token with 403 — not 401 — so an unauthenticated first call
     *    used to look exactly like "this account is not an admin".
     *  * No token at all (no session) -> 401, which sends the admin to sign in.
     *  * Firebase answers 401 once the 1-hour ID token expires -> refresh once
     *    and retry, transparently.
     */
    private fun send(build: (String) -> Request.Builder): String {
        var refreshed = false
        if (needsTokenBeforeFirstCall()) {
            refreshSession()
            refreshed = true
        } else if (idToken.isEmpty()) {
            throw AdminException("No saved login — please sign in again.", 401)
        }
        while (true) {
            val request = build(idToken).build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw AdminException("No internet connection. Check your connection and try again.")
            }
            val bodyText = try {
                response.body?.string().orEmpty()
            } finally {
                response.close()
            }

            if (response.code == 401 && !refreshed && refreshToken.isNotEmpty()) {
                refreshed = true
                // A dead refresh token must surface as a 401 so the screens can
                // send the admin back to the sign-in form instead of hanging.
                refreshSession()
                continue
            }
            if (response.code !in 200..299) {
                throw AdminException(describe(response.code, bodyText), response.code)
            }
            return bodyText
        }
    }

    /** Human-readable explanation of an HTTP failure (403 = rules not deployed). */
    private fun describe(code: Int, bodyText: String): String {
        val detail = errorDetail(bodyText)
        return when (code) {
            401 -> "Login expired — please sign in again." + detail
            403 -> "Firestore rules did not accept this account as an admin (403).\n" +
                AdminConfig.grantHint(signInEmail, signInUid) + detail
            404 -> "Document not found (404)." + detail
            else -> "Firebase error (HTTP $code)." + detail
        }
    }

    /** Firestore error bodies look like {"error":{"code":..,"message":".."}}. */
    private fun errorDetail(bodyText: String): String {
        val message = try {
            val error = JsonParser.parseString(bodyText).asJsonObject.getAsJsonObject("error")
                ?: return ""
            val value = error.get("message") ?: return ""
            if (value.isJsonNull) "" else value.asString
        } catch (e: Exception) {
            ""
        }
        return if (message.isEmpty()) "" else " [$message]"
    }

    private fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }
    }
}
