package com.example.vibemate.admin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

/**
 * Admin sign-in (email + password).
 *
 * Goes through the Firebase REST API ([AdminAuth]) and keeps the login in
 * [AdminSessionStore], so the panel reopens without retyping the password.
 *
 * Only accounts listed in [AdminConfig.ADMIN_EMAILS] — and, for real security, in
 * `firestore.rules` → `isAdmin()` — can read other users' chats.
 */
class AdminLoginActivity : AppCompatActivity() {

    private lateinit var emailField: TextInputEditText
    private lateinit var passwordField: TextInputEditText
    private lateinit var errorView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var signInButton: View

    private val auth = AdminAuth()
    private lateinit var sessions: AdminSessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_login)

        sessions = AdminSessionStore(this)
        emailField = findViewById(R.id.adminEmail)
        passwordField = findViewById(R.id.adminPassword)
        errorView = findViewById(R.id.adminError)
        progress = findViewById(R.id.adminProgress)
        signInButton = findViewById(R.id.adminSignIn)

        // Prefill the last sign-in, or the allow-listed admin e-mail, so only the
        // password has to be typed.
        val knownEmail = sessions.email().ifEmpty { AdminConfig.ADMIN_EMAILS.firstOrNull().orEmpty() }
        if (knownEmail.isNotEmpty()) emailField.setText(knownEmail)

        signInButton.setOnClickListener { signIn() }
        autoLoginIfPossible()
    }

    /** Reopens the panel silently when a refresh token is already stored. */
    private fun autoLoginIfPossible() {
        if (!sessions.hasSession()) return
        showProgress(true)
        Thread {
            try {
                val session = auth.refresh(sessions.refreshToken())
                if (session.email.isEmpty()) session.email = sessions.email()
                sessions.save(session)
                openPanel()
            } catch (e: Exception) {
                sessions.clear()
                runOnUiThread {
                    showProgress(false)
                    showError("The old login expired — please sign in again.")
                }
            }
        }.start()
    }

    private fun signIn() {
        val email = emailField.text?.toString().orEmpty().trim()
        val password = passwordField.text?.toString().orEmpty()

        if (email.isEmpty() || password.isEmpty()) {
            showError("Enter both the email and the password.")
            return
        }
        if (!AdminConfig.isAdminEmail(email)) {
            showError(
                "This email is not in AdminConfig.ADMIN_EMAILS. Either add this email " +
                    "there, or empty that list — then firestore.rules decides whether " +
                    "this account is an admin."
            )
            return
        }

        errorView.visibility = View.GONE
        showProgress(true)
        Thread {
            try {
                val session = auth.signIn(email, password)
                sessions.save(session)
                openPanel()
            } catch (e: Exception) {
                val message = e.message ?: "Sign in failed."
                runOnUiThread {
                    showProgress(false)
                    showError(message)
                }
            }
        }.start()
    }

    private fun openPanel() {
        runOnUiThread {
            startActivity(Intent(this, AdminUsersActivity::class.java))
            finish()
        }
    }

    private fun showProgress(visible: Boolean) {
        runOnUiThread {
            progress.visibility = if (visible) View.VISIBLE else View.GONE
            signInButton.isEnabled = !visible
        }
    }

    private fun showError(message: String) {
        errorView.text = message
        errorView.visibility = View.VISIBLE
    }
}