package com.example.vibemate.admin

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Screen 1 — EVERY USER.
 *
 *  - Tap a user -> their chats ([AdminChatsActivity]).
 *  - Long-press -> selection mode: tap to add/remove, "All" selects every loaded
 *                  user, "Delete" wipes the selected users (every chat + its
 *                  messages, call records and the profile document) after a
 *                  confirmation.
 *  - Back       -> first leaves selection mode, then closes the screen.
 *
 * The "(no owner)" row lists sessions whose `owner_id` was wiped by the old
 * replace-style PATCH bug; deleting it cleans those up (there is no profile to
 * remove, so none is touched).
 */
class AdminUsersActivity : AppCompatActivity() {

    private lateinit var adapter: AdminUsersAdapter
    private lateinit var store: AdminStore
    private lateinit var sessions: AdminSessionStore

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var refreshButton: TextView
    private lateinit var allButton: TextView
    private lateinit var deleteButton: TextView
    private lateinit var closeButton: TextView
    private lateinit var signOutButton: ImageButton
    private lateinit var progress: ProgressBar
    private lateinit var listView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var statusView: TextView

    private var busy = false
    private var resumedOnce = false
    private var chatCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_users)

        sessions = AdminSessionStore(this)
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
        signOutButton = findViewById(R.id.headerSignOut)
        progress = findViewById(R.id.headerProgress)
        listView = findViewById(R.id.adminUsersList)
        emptyView = findViewById(R.id.adminEmpty)
        statusView = findViewById(R.id.adminStatus)

        adapter = AdminUsersAdapter(
            onUserClicked = { user -> openChats(user) },
            onSelectionChanged = { updateHeader() },
        )
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        refreshButton.setOnClickListener { load() }
        allButton.setOnClickListener { adapter.selectAll() }
        deleteButton.setOnClickListener { confirmDelete() }
        closeButton.setOnClickListener { adapter.clearSelection() }
        signOutButton.setOnClickListener { confirmSignOut() }

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

        load()
    }

    override fun onResume() {
        super.onResume()
        // Returning from a chat screen may have changed the counts.
        if (resumedOnce) load() else resumedOnce = true
    }

    /** Reads every profile + every session (chat counts) and rebuilds the list. */
    private fun load() {
        if (busy) return
        busy = true
        progress.visibility = View.VISIBLE
        Thread {
            try {
                val profiles = store.listUserProfiles()
                // Sessions only add the per-user chat counts: if that second read
                // fails, the user list is still worth showing.
                var allSessions = emptyList<AdminSession>()
                var warning = ""
                try {
                    allSessions = store.listAllSessions()
                } catch (e: Exception) {
                    warning = e.message ?: "chat count unavailable"
                }
                val merged = AdminParsing.mergeUsers(profiles, allSessions)
                val count = allSessions.size
                val deletedCount = allSessions.count { it.userDeleted }
                runOnUiThread {
                    busy = false
                    progress.visibility = View.GONE
                    chatCount = count
                    adapter.submit(merged)
                    emptyView.visibility = if (merged.isEmpty()) View.VISIBLE else View.GONE
                    if (warning.isEmpty()) {
                        var statusText = "${merged.size} users • $count chats"
                        if (deletedCount > 0) statusText += " • $deletedCount user-deleted"
                        status(statusText)
                    } else {
                        status(
                            "${merged.size} users (chat count unavailable: $warning)",
                            error = true,
                        )
                    }
                    updateHeader()
                }
            } catch (e: Exception) {
                val message = e.message ?: "Load failed."
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
        signOutButton.visibility = if (selecting) View.GONE else View.VISIBLE
        allButton.visibility = if (selecting) View.VISIBLE else View.GONE
        deleteButton.visibility = if (selecting) View.VISIBLE else View.GONE
        closeButton.visibility = if (selecting) View.VISIBLE else View.GONE

        titleView.text = if (selecting) {
            "${adapter.selectedCount()} selected"
        } else {
            "VibeMate Admin"
        }
        subtitleView.text = if (selecting) {
            "Tap Delete to remove all data of the selected users"
        } else {
            "$chatCount chats • tap = view chats • long-press = select"
        }
    }

    /** Open one user's chats. */
    private fun openChats(user: AdminUser) {
        val intent = Intent(this, AdminChatsActivity::class.java)
        intent.putExtra(AdminChatsActivity.EXTRA_UID, user.uid)
        intent.putExtra(AdminChatsActivity.EXTRA_LABEL, user.label)
        intent.putExtra(AdminChatsActivity.EXTRA_META, metaFor(user))
        startActivity(intent)
    }

    private fun metaFor(user: AdminUser): String {
        val parts = ArrayList<String>()
        parts.add(if (user.isOrphan) "owner_id empty (orphan chats)" else user.uid)
        parts.add(if (user.sessionCount == 1) "1 chat" else "${user.sessionCount} chats")
        if (user.privateCount > 0) parts.add("${user.privateCount} private (18+)")
        // Chats the user deleted from their app: still stored, badge = "USER DELETED".
        if (user.deletedCount > 0) parts.add("${user.deletedCount} user-deleted")
        return parts.joinToString(" • ")
    }

    /** Confirmation before wiping users (this deletes data for real). */
    private fun confirmDelete() {
        val selected = adapter.selectedUsers()
        if (selected.isEmpty()) return
        val names = selected.take(4).joinToString(", ") { it.label }
        val more = if (selected.size > 4) " +${selected.size - 4} more" else ""
        AlertDialog.Builder(this)
            .setTitle("Delete ${selected.size} user(s)?")
            .setMessage(
                "$names$more\n\n" +
                    "ALL of their chats, messages, call records and profile will be " +
                    "deleted forever. This cannot be undone.\n\n" +
                    "(The Firebase Authentication login is separate — remove it in " +
                    "Console → Authentication.)"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteSelected(selected) }
            .show()
    }

    private fun deleteSelected(selected: List<AdminUser>) {
        adapter.clearSelection()
        busy = true
        progress.visibility = View.VISIBLE
        Thread {
            var failed = 0
            for ((index, user) in selected.withIndex()) {
                val label = user.label
                post("${index + 1}/${selected.size}: deleting $label…")
                try {
                    store.deleteUser(user.uid) { step -> post("$label — $step") }
                } catch (e: Exception) {
                    failed++
                    post("$label — ${e.message ?: "delete failed"}", error = true)
                }
            }
            runOnUiThread {
                busy = false
                progress.visibility = View.GONE
                if (failed == 0) {
                    status("All data of ${selected.size} user(s) deleted.")
                } else {
                    status("$failed/${selected.size} user(s) could not be deleted.", error = true)
                }
                // Refresh on the UI thread, otherwise `busy` is still set in the
                // worker thread's view and load() would return without reloading.
                load()
            }
        }.start()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setMessage("The admin login will be removed — you will need the email/password again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Sign out") { _, _ ->
                sessions.clear()
                goToLogin()
            }
            .show()
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