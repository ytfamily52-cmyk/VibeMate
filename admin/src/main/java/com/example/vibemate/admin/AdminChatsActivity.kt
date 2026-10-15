package com.example.vibemate.admin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Screen 2 — ALL CHATS of one user.
 *
 *  - Tap a chat    -> its full message history ([AdminMessagesActivity]).
 *  - Long-press    -> selection mode: "All" selects every loaded chat and "Delete"
 *                     removes the selected chats WITH all of their messages.
 *  - Back          -> first leaves selection mode, then returns to the users list.
 *
 * IMPORTANT: a chat the USER deleted from the app is listed here as well (row badge
 * "USER DELETED"), because the app only FLAGS a chat (`deleted_by_owner` /
 * `cleared_at`, see VibeMateApi.SessionDeletePolicy) instead of deleting it. The
 * delete in this screen is therefore the FINAL one — nothing is removed from the
 * database until an admin presses Delete here.
 */
class AdminChatsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_UID = "uid"
        const val EXTRA_LABEL = "label"
        const val EXTRA_META = "meta"
    }

    private lateinit var adapter: AdminSessionsAdapter
    private lateinit var store: AdminStore

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var refreshButton: TextView
    private lateinit var allButton: TextView
    private lateinit var deleteButton: TextView
    private lateinit var closeButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var listView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var statusView: TextView

    private var uid = ""
    private var label = "User"
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_chats)

        uid = intent.getStringExtra(EXTRA_UID).orEmpty()
        label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifEmpty { "User" }
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
        refreshButton = findViewById(R.id.headerRefresh)
        allButton = findViewById(R.id.headerAll)
        deleteButton = findViewById(R.id.headerDelete)
        closeButton = findViewById(R.id.headerClose)
        progress = findViewById(R.id.headerProgress)
        listView = findViewById(R.id.adminChatsList)
        emptyView = findViewById(R.id.adminEmpty)
        statusView = findViewById(R.id.adminStatus)

        titleView.text = label
        subtitleView.visibility = View.VISIBLE

        adapter = AdminSessionsAdapter(
            onSessionClicked = { session -> openMessages(session) },
            onSelectionChanged = { updateHeader() },
        )
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        findViewById<TextView>(R.id.headerBack).setOnClickListener { finish() }
        refreshButton.setOnClickListener { load() }
        allButton.setOnClickListener { adapter.selectAll() }
        deleteButton.setOnClickListener { confirmDelete() }
        closeButton.setOnClickListener { adapter.clearSelection() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode()) {
                    adapter.clearSelection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        subtitleView.text = meta
        load()
    }

    /**
     * Loads EVERY chat of this user (paged over the whole collection and filtered
     * by owner_id — no per-user 300-row cap, so a heavy user's older chats show
     * up too). An empty [uid] is the orphan bucket, handled inside the store.
     */
    private fun load() {
        if (busy) return
        busy = true
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val chats = store.listUserSessions(uid)
                runOnUiThread {
                    busy = false
                    progress.visibility = View.GONE
                    adapter.submit(chats)
                    emptyView.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
                    val userDeleted = AdminParsing.userDeletedCount(chats)
                    var text = "${chats.size} chats"
                    if (userDeleted > 0) {
                        text += " • $userDeleted user-deleted (removed from the app, safe here)"
                    }
                    status(text)
                    updateHeader()
                }
            } catch (e: Exception) {
                val message = e.message ?: "Chats could not be loaded."
                val expired = e is AdminException && e.httpCode == 401
                runOnUiThread {
                    busy = false
                    progress.visibility = View.GONE
                    emptyView.visibility =
                        if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                    status(message, error = true)
                    if (expired) goToLogin()
                }
            }
        }.start()
    }

    /** Header switches between the normal and the selection (All/Delete) state. */
    private fun updateHeader() {
        val selecting = adapter.isSelectionMode()
        refreshButton.visibility = if (selecting) View.GONE else View.VISIBLE
        allButton.visibility = if (selecting) View.VISIBLE else View.GONE
        deleteButton.visibility = if (selecting) View.VISIBLE else View.GONE
        closeButton.visibility = if (selecting) View.VISIBLE else View.GONE
        titleView.text = if (selecting) "${adapter.selectedCount()} selected" else label
    }

    /** Reads one chat: every message, oldest first. */
    private fun openMessages(session: AdminSession) {
        val intent = Intent(this, AdminMessagesActivity::class.java)
        intent.putExtra(AdminMessagesActivity.EXTRA_SESSION_ID, session.id)
        intent.putExtra(AdminMessagesActivity.EXTRA_LABEL, session.label)
        intent.putExtra(
            AdminMessagesActivity.EXTRA_META,
            "${label} • ${session.personaLabel}" +
                (if (session.privateMode) " • private 18+" else "") +
                (if (session.deleteNote.isNotEmpty()) " • ${session.deleteNote}" else "") +
                " • ${session.id.take(8)}…",
        )
        startActivity(intent)
    }

    private fun confirmDelete() {
        val selected = adapter.selectedSessions()
        if (selected.isEmpty()) return
        val names = selected.take(4).joinToString(", ") { it.label }
        val more = if (selected.size > 4) " +${selected.size - 4} more" else ""
        val userDeleted = AdminParsing.userDeletedCount(selected)
        AlertDialog.Builder(this)
            .setTitle("Delete ${selected.size} chat(s)?")
            .setMessage(
                "$names$more\n\n" +
                    "ALL messages in these chats (photos, voice notes, documents) will " +
                    "also be deleted. This is the FINAL delete — they will be gone from " +
                    "the database forever.\n\n" +
                    if (userDeleted > 0) {
                        "$userDeleted chat(s) carry the USER DELETED badge: " +
                            "the user removed them from their app, but everything is " +
                            "still safe here — they only end when you press Delete."
                    } else {
                        "The app only flags a chat; the real delete happens only here."
                    }
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteSelected(selected) }
            .show()
    }

    private fun deleteSelected(selected: List<AdminSession>) {
        adapter.clearSelection()
        busy = true
        progress.visibility = View.VISIBLE
        Thread {
            var failed = 0
            try {
                store.deleteSessions(selected.map { it.id }) { step -> post("delete: $step") }
            } catch (e: Exception) {
                failed = 1
                post(e.message ?: "Delete failed.", error = true)
            }
            runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                if (failed == 0) {
                    status("${selected.size} chat(s) deleted along with all their messages.")
                }
                // Reload from the UI thread so `busy` is already cleared.
                load()
            }
        }.start()
    }

    private fun goToLogin() {
        startActivity(Intent(this, AdminLoginActivity::class.java))
        finish()
    }

    /** Progress / result line at the bottom of the screen. */
    private fun status(message: String, error: Boolean = false) {
        statusView.text = message
        statusView.visibility = View.VISIBLE
        statusView.setTextColor(if (error) 0xFFD50000.toInt() else 0xFF0D7267.toInt())
        statusView.setBackgroundColor(if (error) 0x26D50000 else 0x2600BFA5)
    }

    /** status() from a worker thread. */
    private fun post(message: String, error: Boolean = false) {
        runOnUiThread { status(message, error) }
    }
}