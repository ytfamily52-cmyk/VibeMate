package com.example.vibemate.admin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.IOException

/** Any failure while talking to Firebase Auth (network, bad login, bad key). */
class AdminAuthException(message: String) : Exception(message)

/** A signed-in admin: Firebase ID token (1 h) + refresh token + identity. */
class AdminAuthSession {
    var idToken: String = ""
    var refreshToken: String = ""
    var uid: String = ""
    var email: String = ""
}

/**
 * Firebase Authentication over the REST API — email + password ONLY.
 *
 * The admin app has no Firebase SDK and no google-services.json on purpose: this
 * is the same public Identity Toolkit REST API the main app uses in
 * FirebaseAuth.kt, so all it needs is the project's WEB API key (see [AdminConfig]).
 *
 * Every call is blocking and must run off the UI thread.
 */
class AdminAuth {

    private val client = OkHttpClient()

    /** Sign in with an existing Firebase email/password account. */
    fun signIn(email: String, password: String): AdminAuthSession {
        val body = JsonObject()
        body.addProperty("email", email.trim())
        body.addProperty("password", password)
        body.addProperty("returnSecureToken", true)
        val url = AdminConfig.IDENTITY_BASE + "/accounts:signInWithPassword?key=" +
            AdminConfig.FIREBASE_API_KEY
        return parseSession(
            parseObject(send(Request.Builder().url(url).post(jsonBody(body)))) ?: JsonObject()
        )
    }

    /**
     * Exchange a refresh token for a fresh ID token (securetoken API). Used to
     * silently reopen the admin panel, and again every hour while it stays open.
     */
    fun refresh(refreshToken: String): AdminAuthSession {
        if (refreshToken.isEmpty()) {
            throw AdminAuthException("No saved login — please sign in again.")
        }
        val url = AdminConfig.REFRESH_URL + "?key=" + AdminConfig.FIREBASE_API_KEY
        val body = JsonObject()
        body.addProperty("grant_type", "refresh_token")
        body.addProperty("refresh_token", refreshToken)
        val text = send(Request.Builder().url(url).post(jsonBody(body)))
        val obj = parseObject(text) ?: throw AdminAuthException("Firebase did not respond.")

        val session = AdminAuthSession()
        session.idToken = getString(obj, "id_token").ifEmpty { getString(obj, "idToken") }
        session.refreshToken = getString(obj, "refresh_token").ifEmpty { refreshToken }
        session.uid = getString(obj, "user_id").ifEmpty { getString(obj, "localId") }
        session.email = getString(obj, "email")
        if (session.idToken.isEmpty()) {
            throw AdminAuthException("Firebase did not return a new ID token.")
        }
        return session
    }

    // ---------------------------------------------------------- internals -----

    private fun jsonBody(body: JsonObject): RequestBody =
        RequestBody.create(null, body.toString())

    private fun send(builder: Request.Builder): String {
        val request = builder.header("Content-Type", "application/json").build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AdminAuthException("No internet connection. Check your connection and try again.")
        }
        val bodyText = try {
            response.body?.string().orEmpty()
        } finally {
            response.close()
        }
        if (response.code !in 200..299) {
            throw AdminAuthException(friendly(errorMessage(bodyText), response.code))
        }
        return bodyText
    }

    /** Firebase error bodies look like {"error":{"code":400,"message":"..."}}. */
    private fun errorMessage(bodyText: String): String {
        return try {
            val root = parseObject(bodyText) ?: return "Firebase request failed."
            val error = root.getAsJsonObject("error") ?: return "Firebase request failed."
            val message = error.get("message") ?: return "Firebase request failed."
            if (message.isJsonNull) "Firebase request failed." else message.asString
        } catch (e: Exception) {
            "Firebase request failed."
        }
    }

    /** Turns Firebase's terse codes into something a human can act on. */
    private fun friendly(code: String, httpCode: Int): String {
        return when {
            code.contains("INVALID_LOGIN_CREDENTIALS") || code.contains("INVALID_PASSWORD") ->
                "Email or password is incorrect."
            code.contains("EMAIL_NOT_FOUND") -> "There is no Firebase account for this email."
            code.contains("USER_DISABLED") -> "This account is disabled in Firebase."
            code.contains("INVALID_EMAIL") -> "The email address is not valid."
            code.contains("MISSING_PASSWORD") -> "Enter a password."
            code.contains("TOO_MANY_ATTEMPTS_TRY_LATER") ->
                "Too many failed attempts — try again a little later."
            code.contains("API key not valid") || code.contains("API_KEY_INVALID") ->
                "AdminConfig.FIREBASE_API_KEY is invalid (match it with the app's AppConfig)."
            httpCode == 400 -> "Sign in failed. Check the email/password."
            else -> "Sign in failed (HTTP $httpCode). [$code]"
        }
    }

    private fun parseSession(obj: JsonObject): AdminAuthSession {
        val session = AdminAuthSession()
        session.idToken = getString(obj, "idToken")
        session.refreshToken = getString(obj, "refreshToken")
        session.uid = getString(obj, "localId")
        session.email = getString(obj, "email")
        if (session.idToken.isEmpty()) {
            throw AdminAuthException("Firebase did not return an ID token — please try again.")
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
        val element = obj.get(name) ?: return ""
        if (element.isJsonNull || !element.isJsonPrimitive) return ""
        return element.asString
    }
}