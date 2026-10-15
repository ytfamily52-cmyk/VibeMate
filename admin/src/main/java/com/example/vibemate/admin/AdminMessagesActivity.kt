package com.example.vibemate.admin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Screen 3 — the FULL message history of one chat (oldest first).
 *
 * Read-only on purpose: what needs deleting is a whole chat (screen 2), because a
 * chat with holes in the middle is worse than no chat at all. Photos, voice notes
 * and documents are shown exactly as they were stored.
 */
class AdminMessagesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_META = "meta"
    }

    private lateinit var adapter: AdminMessagesAdapter
    private lateinit var store: AdminStore

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView

    private var sessionId = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_messages)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifEmpty { "Chat" }
        val meta = intent.getStringExtra(EXTRA_META).orEmpty()

        val sessions = AdminSessionStore(this)
        if (!sessions.hasSession()) {
            goToLogin()
            return
        }
        store = AdminStore(
            // The saved ID token is what makes the first request authenticated.
            idToken = sessions.idToken(),
            refreshToken = sessions.refreshToken(),
            signInEmail = sessions.email(),
            signInUid = sessions.uid(),
            onSessionRefreshed = { sessions.save(it) },
        )

        titleView = findViewById(R.id.headerTitle)
        subtitleView = findViewById(R.id.headerSubtitle)
        progress = findViewById(R.id.headerProgress)
        emptyView = findViewById(R.id.adminEmpty)

        titleView.text = label
        subtitleView.visibility = View.VISIBLE
        subtitleView.text = meta

        adapter = AdminMessagesAdapter()
        val listView = findViewById<RecyclerView>(R.id.adminMessagesList)
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        findViewById<TextView>(R.id.headerBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.headerRefresh).setOnClickListener { load() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        load()
    }

    /** Reads every message of this chat (paged, oldest first). */
    private fun load() {
        if (busy) return
        busy = true
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val messages = store.listMessages(sessionId)
                runOnUiThread {
                    busy = false
                    progress.visibility = View.GONE
                    adapter.submit(messages)
                    emptyView.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                    val photos = messages.count { it.hasPhoto }
                    var text = "${messages.size} messages"
                    if (photos > 0) text += " • $photos photo"
                    subtitleView.text = text
                }
            } catch (e: Exception) {
                val message = e.message ?: "Messages could not be loaded."
                val expired = e is AdminException && e.httpCode == 401
                runOnUiThread {
                    busy = false
                    progress.visibility = View.GONE
                    emptyView.visibility =
                        if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                    subtitleView.text = message
                    if (expired) goToLogin()
                }
            }
        }.start()
    }

    private fun goToLogin() {
        startActivity(Intent(this, AdminLoginActivity::class.java))
        finish()
    }
}