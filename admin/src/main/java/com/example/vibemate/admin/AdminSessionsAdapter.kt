package com.example.vibemate.admin

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * One user's chats, with the same long-press selection as the users list:
 * long-press to select, "All" selects every loaded chat, "Delete" removes the
 * selected chats together with all of their messages.
 *
 * A chat whose row says "USER DELETED" is a chat the user removed from THEIR app
 * — it is still listed here (the app only flags it) and deleting it here is the
 * FINAL delete.
 */
class AdminSessionsAdapter(
    private val onSessionClicked: (AdminSession) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
) : RecyclerView.Adapter<AdminSessionsAdapter.SessionViewHolder>() {

    private val sessions = ArrayList<AdminSession>()
    private val selectedIds = LinkedHashSet<String>()

    class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val row: View = itemView.findViewById(R.id.adminSessionRow)
        val avatar: TextView = itemView.findViewById(R.id.adminSessionAvatar)
        val name: TextView = itemView.findViewById(R.id.adminSessionName)
        val privateBadge: TextView = itemView.findViewById(R.id.adminSessionPrivate)
        val deletedBadge: TextView = itemView.findViewById(R.id.adminSessionDeleted)
        val title: TextView = itemView.findViewById(R.id.adminSessionTitle)
        val meta: TextView = itemView.findViewById(R.id.adminSessionMeta)
        val check: TextView = itemView.findViewById(R.id.adminSessionCheck)
    }

    fun isSelectionMode(): Boolean = selectedIds.isNotEmpty()

    fun selectedCount(): Int = selectedIds.size

    fun selectedSessions(): List<AdminSession> = sessions.filter { selectedIds.contains(it.id) }

    fun submit(list: List<AdminSession>) {
        sessions.clear()
        sessions.addAll(list)
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun toggle(session: AdminSession) {
        if (!selectedIds.add(session.id)) {
            selectedIds.remove(session.id)
        }
        notifyItemChanged(positionOf(session.id))
        onSelectionChanged(selectedIds.size)
    }

    fun selectAll() {
        for (session in sessions) selectedIds.add(session.id)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun getItemCount(): Int = sessions.size

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val session = sessions[position]

        holder.avatar.text =
            session.label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "V"
        holder.name.text = session.label
        holder.privateBadge.visibility = if (session.privateMode) View.VISIBLE else View.GONE
        holder.deletedBadge.visibility = if (session.userDeleted) View.VISIBLE else View.GONE
        holder.title.text = session.title.ifEmpty { "(no title)" }
        holder.meta.text = metaFor(session)

        val checked = selectedIds.contains(session.id)
        holder.check.visibility = if (checked) View.VISIBLE else View.INVISIBLE
        holder.row.setBackgroundColor(if (checked) 0x264CAF50 else Color.TRANSPARENT)

        holder.row.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            if (selectedIds.isNotEmpty()) toggle(sessions[pos]) else onSessionClicked(sessions[pos])
        }
        holder.row.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            toggle(sessions[pos])
            true
        }
    }

    /**
     * "updated 2026-01-02 03:04 • Boyfriend • 1a2b3c4d…" plus, when the USER
     * deleted (or cleared) this chat from their own app, what they did and when.
     * The chat is still listed on purpose: the app only FLAGS it, so nothing is
     * lost until an ADMIN deletes it here (that delete is the final one).
     */
    private fun metaFor(session: AdminSession): String {
        val parts = ArrayList<String>()
        parts.add("updated ${shortStamp(session)}")
        parts.add(session.personaLabel)
        parts.add(session.id.take(8) + "…")
        if (session.deleteNote.isNotEmpty()) parts.add(session.deleteNote)
        return parts.joinToString(" • ")
    }

    /** "2026-01-02 03:04" from the stored ISO-8601 UTC timestamp. */
    private fun shortStamp(session: AdminSession): String {
        val stamp = session.updatedAt.ifEmpty { session.createdAt }
        if (stamp.isEmpty()) return "?"
        return stamp.replace("T", " ").take(16)
    }

    private fun positionOf(id: String): Int = sessions.indexOfFirst { it.id == id }
}