package com.example.vibemate.admin

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Users list adapter with WhatsApp-style long-press selection:
 * long-press a row to start selecting, tap rows to add/remove them, "All" selects
 * every loaded user and "Delete" removes the selected users with ALL their data.
 */
class AdminUsersAdapter(
    private val onUserClicked: (AdminUser) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
) : RecyclerView.Adapter<AdminUsersAdapter.UserViewHolder>() {

    private val users = ArrayList<AdminUser>()
    private val selectedIds = LinkedHashSet<String>()

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val row: View = itemView.findViewById(R.id.adminUserRow)
        val avatar: TextView = itemView.findViewById(R.id.adminUserAvatar)
        val name: TextView = itemView.findViewById(R.id.adminUserName)
        val meta: TextView = itemView.findViewById(R.id.adminUserMeta)
        val uid: TextView = itemView.findViewById(R.id.adminUserUid)
        val check: TextView = itemView.findViewById(R.id.adminUserCheck)
    }

    /** True while at least one user is selected (header switches to All/Delete). */
    fun isSelectionMode(): Boolean = selectedIds.isNotEmpty()

    fun selectedCount(): Int = selectedIds.size

    /** The selected users themselves (needed for the delete confirmation). */
    fun selectedUsers(): List<AdminUser> = users.filter { selectedIds.contains(it.uid) }

    /** Replace the whole list (load/refresh). Clears the selection. */
    fun submit(list: List<AdminUser>) {
        users.clear()
        users.addAll(list)
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun toggle(user: AdminUser) {
        if (!selectedIds.add(user.uid)) {
            selectedIds.remove(user.uid)
        }
        notifyItemChanged(positionOf(user.uid))
        onSelectionChanged(selectedIds.size)
    }

    fun selectAll() {
        for (user in users) selectedIds.add(user.uid)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_user, parent, false)
        return UserViewHolder(view)
    }

    override fun getItemCount(): Int = users.size

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = users[position]

        holder.avatar.text = user.label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        holder.name.text = user.label
        holder.meta.text = metaFor(user)
        holder.uid.typeface = Typeface.MONOSPACE
        holder.uid.text = if (user.isOrphan) {
            "orphan sessions — owner_id is empty"
        } else {
            user.uid
        }

        val checked = selectedIds.contains(user.uid)
        holder.check.visibility = if (checked) View.VISIBLE else View.INVISIBLE
        holder.row.setBackgroundColor(if (checked) 0x264CAF50 else Color.TRANSPARENT)

        holder.row.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            if (selectedIds.isNotEmpty()) toggle(users[pos]) else onUserClicked(users[pos])
        }
        holder.row.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            toggle(users[pos])
            true
        }
    }

    /** "4 chats • 2 private • last chat 2026-01-02 03:04 • joined 2026-01-02". */
    private fun metaFor(user: AdminUser): String {
        val parts = ArrayList<String>()
        parts.add(if (user.sessionCount == 1) "1 chat" else "${user.sessionCount} chats")
        if (user.privateCount > 0) parts.add("${user.privateCount} private (18+)")
        // The user deleted these from their own app — they are still stored here.
        if (user.deletedCount > 0) parts.add("${user.deletedCount} user-deleted")
        if (!user.hasProfile) parts.add("no profile doc")
        // The user's most recent chat: the list is SORTED by this (newest on top),
        // so the row has to show it — otherwise the order looks random.
        if (user.lastChatAt.length >= 16) {
            parts.add("last chat " + user.lastChatAt.replace("T", " ").take(16))
        }
        if (user.createdAt.length >= 10) parts.add("joined ${user.createdAt.take(10)}")
        return parts.joinToString(" • ")
    }

    private fun positionOf(uid: String): Int = users.indexOfFirst { it.uid == uid }
}