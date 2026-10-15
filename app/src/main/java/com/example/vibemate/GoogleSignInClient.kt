package com.example.vibemate

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Native Google sign-in through Android's Credential Manager — no browser, no
 * redirect, no polling:
 *
 *  1. the phone's own Google accounts appear in the system account sheet (the
 *     same one Gmail uses), so the user just taps their account;
 *  2. Google answers with an **ID token** for [AppConfig.GOOGLE_WEB_CLIENT_ID];
 *  3. [FirebaseAuth.signInWithGoogleIdToken] trades that token for a Firebase
 *     session — MainActivity does steps 2 + 3.
 *
 * Two project settings (not code) have to be right, otherwise Google refuses
 * before the app ever sees a token:
 *  - Google must be enabled in Firebase -> Authentication -> Sign-in method;
 *  - this app's **package name + signing certificate SHA-1** must be registered
 *    in Firebase -> Project settings -> *your Android app* -> *Add fingerprint*.
 *    That registration is what creates the Android OAuth client Google Play
 *    services looks for on the device; without it every attempt fails with
 *    "provider configuration" / error 10. From the command line:
 *    `node admin/tools/firebase_admin.js add-debug-sha`.
 *
 * The context handed to the constructor MUST be an Activity (MainActivity
 * passes itself): Credential Manager launches the account sheet *through* that
 * context, and an application context can never start that activity — the call
 * then fails with "Failed to launch the selector UI. Hint: ensure the `context`
 * parameter is an Activity-based context." Every "Continue with Google" tap
 * used to die there, which is why the button looked dead.
 *
 * Callbacks are delivered on the main thread.
 */
class GoogleSignInClient(private val activity: Context) {

    companion object {
        /** Filter logcat with: adb logcat -s VibeMateGoogle */
        private const val TAG = "VibeMateGoogle"
    }

    private val credentialManager: CredentialManager = CredentialManager.create(activity)

    /**
     * Open the Google account sheet and hand the chosen account's ID token back.
     *
     * @param onIdToken Google's ID token (a JWT) of the account the user tapped.
     * @param onCancelled the sheet was dismissed without choosing an account —
     *   not an error, so nothing is shown to the user.
     * @param onFailed Google / Play services could not complete the sign-in; the
     *   message is already user-readable (it explains a missing SHA-1, no Google
     *   account on the phone, an old Play services, ...).
     */
    fun signIn(
        onIdToken: (String) -> Unit,
        onCancelled: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        // Defensive: the account sheet is an Activity, so a non-Activity context
        // can never show it. Report that plainly instead of letting the cryptic
        // platform error ("Failed to launch the selector UI") reach the user.
        if (activity !is Activity) {
            failed("Google sign-in needs an Activity context.", onFailed)
            return
        }
        val option = GetGoogleIdOption.Builder()
            // Audience of the ID token Firebase will accept: the project's WEB
            // client id (an Android client id would be rejected by Firebase).
            .setServerClientId(AppConfig.GOOGLE_WEB_CLIENT_ID)
            // false = always show the account sheet (this app has no account
            // chooser of its own and must also offer "add another account");
            // true = only accounts already used with the app.
            .setFilterByAuthorizedAccounts(false)
            // false = never sign in silently; the user always taps an account.
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        credentialManager.getCredentialAsync(
            activity,                                   // Activity context: launches the sheet
            request,
            null,                                       // no CancellationSignal: Back closes the sheet
            ContextCompat.getMainExecutor(activity),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val credential = result.credential
                    if (credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        try {
                            onIdToken(GoogleIdTokenCredential.createFrom(credential.data).idToken)
                        } catch (e: GoogleIdTokenParsingException) {
                            failed("Google's answer could not be read (${e.message}).", onFailed)
                        }
                    } else {
                        failed(
                            "Google returned an unexpected credential (${credential.type}) " +
                                "instead of an ID token.",
                            onFailed,
                        )
                    }
                }

                override fun onError(e: GetCredentialException) {
                    if (e is GetCredentialCancellationException) {
                        onCancelled()
                    } else {
                        failed(explain(e), onFailed)
                    }
                }
            },
        )
    }

    /** Logs the reason for logcat, then shows the user-facing message. */
    private fun failed(message: String, onFailed: (String) -> Unit) {
        Log.w(TAG, "Google sign-in failed: $message")
        onFailed(message)
    }

    /**
     * Turns a Credential Manager error into something the user can act on. The
     * two configuration failures below are unreadable on their own ("provider
     * configuration issue" / "no credentials available") but are almost always
     * the real reason Google sign-in refuses.
     */
    private fun explain(e: GetCredentialException): String {
        val raw = (e.message ?: "").trim()
        return when {
            e is GetCredentialProviderConfigurationException ->
                "Google sign-in is not set up for this build: this app's SHA-1 is not " +
                    "registered in Firebase -> Project settings -> Add fingerprint."
            e is NoCredentialException ->
                "No Google account is on this phone. Add one in Settings -> Accounts, " +
                    "then tap \"Continue with Google\" again."
            raw.isEmpty() ->
                "Google Play services refused the sign-in (${e.javaClass.simpleName})."
            else -> raw
        }
    }
}