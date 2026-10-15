package com.example.vibemate

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

import java.io.IOException

/**
 * Firebase Authentication via the public Google Identity Toolkit REST API
 * (https://firebase.google.com/docs/reference/rest/auth).
 *
 * Three sign-in flavours, all online (no local network needed):
 *  - Email    -> accounts:signUp / accounts:signInWithPassword
 *  - Anonymous -> accounts:signUp with only returnSecureToken (no email/sign-up
 *                prompt) — Firebase creates a real but permanent anonymous user.
 *  - Google   -> the Google ID token that the phone's own account sheet hands to
 *                GoogleSignInClient is exchanged with accounts:signInWithIdp —
 *                no browser, no redirect, no polling. See signInWithGoogleIdToken.
 *
 * Every method is synchronous and must be called off the UI thread (see
 * MainActivity). Errors are surfaced as FirebaseAuthException with Firebase's
 * own message ("EMAIL_EXISTS", "INVALID_LOGIN_CREDENTIALS", ...).
 */
class FirebaseAuthException(message: String) : Exception(message)

/** Result of a successful Firebase sign-in/up. */
class FirebaseSession {
    var idToken: String = ""
    var refreshToken: String = ""
    var uid: String = ""
    var email: String = ""
    var displayName: String = ""
    var expiresIn: Long = 0
}

class FirebaseAuth {

    private val client = OkHttpClient()

    /**
     * The requestUri sent with accounts:signInWithIdp. It has to be a value the
     * project authorises — "localhost" is in every project's default
     * Authentication -> Settings -> Authorized domains list, so no console step is
     * needed. No browser is ever opened: the token itself already came from Google
     * Play services (see GoogleSignInClient), this field is only part of the API's
     * contract.
     */
    private val signInRedirectUrl: String = "http://localhost"

    private fun urlEncode(value: String): String {
        return try {
            java.net.URLEncoder.encode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }
    }

    // ------------------------------------------------------------------ email

    /** Log in with an existing Firebase email/password account. */
    fun signInWithEmail(email: String, password: String): FirebaseSession {
        val body = JsonObject()
        body.addProperty("email", email.trim())
        body.addProperty("password", password)
        body.addProperty("returnSecureToken", true)
        return parseSession(post("accounts:signInWithPassword", body))
    }

    /** Create a new Firebase email/password account and return its ID token. */
    fun signUpWithEmail(email: String, password: String): FirebaseSession {
        val body = JsonObject()
        body.addProperty("email", email.trim())
        body.addProperty("password", password)
        body.addProperty("returnSecureToken", true)
        return parseSession(post("accounts:signUp", body))
    }

    // -------------------------------------------------------------- anonymous

    /** Create/return a permanent anonymous Firebase user (no email needed). */
    fun signInAnonymously(): FirebaseSession {
        val body = JsonObject()
        body.addProperty("returnSecureToken", true)
        return parseSession(post("accounts:signUp", body))
    }

    // ------------------------------------------------------------------ google

    /**
     * Google sign-in: exchange the ID token that the phone's own account sheet
     * handed the app (see [GoogleSignInClient]) for a Firebase ID token.
     *
     * There is no browser step: Google Play services has already verified the
     * account on the device, so Firebase only has to accept the token's audience
     * (the project's web client id, [AppConfig.GOOGLE_WEB_CLIENT_ID]) and mint a
     * session. `postBody` is the url-encoded form Firebase expects —
     * `id_token=..&providerId=google.com`, the same body Firebase's own SDKs
     * build for native Google sign-in.
     *
     * If this throws INVALID_IDP_RESPONSE, the app's signing SHA-1 is not
     * registered in Firebase -> Project settings (see GoogleSignInClient).
     */
    fun signInWithGoogleIdToken(idToken: String): FirebaseSession {
        if (idToken.isEmpty()) {
            throw FirebaseAuthException("MISSING_IDP_RESPONSE: Google returned no ID token.")
        }
        val body = JsonObject()
        body.addProperty("postBody", "id_token=" + urlEncode(idToken) + "&providerId=google.com")
        body.addProperty("requestUri", signInRedirectUrl)
        body.addProperty("returnSecureToken", true)
        return parseSession(post("accounts:signInWithIdp", body))
    }

    /**
     * Exchange a refresh token for a fresh ID token (securetoken API).
     * Used to restore the session on app start without asking for login again.
     */
    fun refreshWithToken(refreshToken: String): FirebaseSession {
        if (refreshToken.isEmpty()) {
            throw FirebaseAuthException("No stored session to refresh.")
        }
        val url = "https://securetoken.googleapis.com/v1/token?key=${AppConfig.FIREBASE_API_KEY}"
        val body = JsonObject()
        body.addProperty("grant_type", "refresh_token")
        body.addProperty("refresh_token", refreshToken)
        val builder = Request.Builder()
            .url(url)
            .post(RequestBody.create(null, body.toString()))
            .header("Content-Type", "application/json")
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw FirebaseAuthException(
                "Could not connect to Firebase. Please check your internet connection."
            )
        }
        try {
            val bodyText = response.body?.string() ?: ""
            if (response.code !in 200..299) {
                throw FirebaseAuthException(
                    parseFirebaseError(bodyText, "Session expired (HTTP ${response.code}).")
                )
            }
            val obj = parseObject(bodyText) ?: JsonObject()
            // The securetoken API returns id_token / refresh_token / user_id.
            val session = FirebaseSession()
            session.idToken = getString(obj, "id_token").ifEmpty { getString(obj, "idToken") }
            session.refreshToken = getString(obj, "refresh_token").ifEmpty { getString(obj, "refreshToken") }
            session.uid = getString(obj, "user_id").ifEmpty { getString(obj, "localId") }
            session.expiresIn = try {
                getString(obj, "expires_in").toLong()
            } catch (e: Exception) {
                0
            }
            if (session.idToken.isEmpty()) {
                throw FirebaseAuthException("Firebase did not return an ID token.")
            }
            return session
        } finally {
            response.close()
        }
    }

    /** Set (or update) the display name on the Firebase user profile. */
    fun updateProfile(idToken: String, displayName: String) {
        val body = JsonObject()
        body.addProperty("idToken", idToken)
        body.addProperty("displayName", displayName)
        post("accounts:update", body)
    }

    // ---------------------------------------------------- low-level HTTP -----

    private fun post(endpoint: String, body: JsonObject): JsonObject {
        val url = "https://identitytoolkit.googleapis.com/v1/$endpoint?key=${AppConfig.FIREBASE_API_KEY}"
        val builder = Request.Builder()
            .url(url)
            .post(RequestBody.create(null, body.toString()))
            .header("Content-Type", "application/json")
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw FirebaseAuthException(
                "Could not connect to Firebase. Please check your internet connection."
            )
        }
        try {
            val bodyText = response.body?.string() ?: ""
            if (response.code !in 200..299) {
                throw FirebaseAuthException(parseFirebaseError(bodyText, "Firebase request failed (HTTP ${response.code})."))
            }
            if (bodyText.isEmpty()) return JsonObject()
            return parseObject(bodyText) ?: JsonObject()
        } finally {
            response.close()
        }
    }

    /** Firebase error bodies look like {"error": {"code": 400, "message": "..."}} */
    private fun parseFirebaseError(bodyText: String, fallback: String): String {
        return try {
            val root = parseObject(bodyText) ?: return fallback
            val error = root.getAsJsonObject("error")
            val message = error.get("message")
            if (message != null && !message.isJsonNull) message.asString else fallback
        } catch (e: Exception) {
            fallback
        }
    }

    private fun parseSession(obj: JsonObject): FirebaseSession {
        val session = FirebaseSession()
        session.idToken = getString(obj, "idToken")
        session.refreshToken = getString(obj, "refreshToken")
        session.uid = getString(obj, "localId")
        session.email = getString(obj, "email")
        session.displayName = getString(obj, "displayName")
        session.expiresIn = try {
            getString(obj, "expiresIn").toLong()
        } catch (e: Exception) {
            0
        }
        if (session.idToken.isEmpty()) {
            throw FirebaseAuthException("Firebase did not return an ID token.")
        }
        return session
    }

    private fun parseObject(bodyText: String): JsonObject? {
        return try {
            JsonParser.parseString(bodyText).asJsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun getString(obj: JsonObject, name: String): String {
        val el = obj.get(name) ?: return ""
        return if (el.isJsonNull) "" else el.asString
    }
}