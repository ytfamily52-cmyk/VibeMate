package com.example.vibemate

import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * WhatsApp-style chat-list adapter for the full-screen mates home panel.
 *
 * Each row mirrors a WhatsApp chat: a green circle avatar with the mate's
 * initial, the mate's name as the title and the relationship (Girlfriend /
 * Boyfriend / Crush / Gay) as the subtitle.
 *
 * WhatsApp-like long-press selection: long-press a row to start selecting,
 * tap rows to add/remove them, "All" selects everything, "Delete" removes
 * the selected chats together with their whole history.
 */
class MatesAdapter(
    private val onMateClicked: (SessionDto) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
) : RecyclerView.Adapter<MatesAdapter.MateViewHolder>() {

    private val sessions = ArrayList<SessionDto>()
    private val selectedIds = LinkedHashSet<String>()
    // True while selection mode is active (entered via long-press OR the header
    // "Select" button) even before any row is ticked, so the user can simply tap
    // rows to select them instead of having to long-press first.
    private var selecting = false

    class MateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatar: TextView = itemView.findViewById(R.id.mateAvatar)
        val name: TextView = itemView.findViewById(R.id.mateName)
        val subtitle: TextView = itemView.findViewById(R.id.mateSubtitle)
        val check: TextView = itemView.findViewById(R.id.mateCheck)
    }

    /** True while selection mode is active (header switches to All/Delete). */
    fun isSelectionMode(): Boolean = selecting || selectedIds.isNotEmpty()

    fun selectedCount(): Int = selectedIds.size

    /** IDs of the currently selected chats (for deletion). */
    fun selectedSessionIds(): List<String> = selectedIds.toList()

    /** Replace the whole list (used on load/refresh). Clears the selection. */
    fun submit(list: List<SessionDto>) {
        sessions.clear()
        sessions.addAll(list)
        selectedIds.clear()
        selecting = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    /** Add/remove one row from the selection (tap while selecting / long-press). */
    fun toggle(session: SessionDto) {
        if (!selectedIds.add(session.id)) {
            selectedIds.remove(session.id)
        }
        notifyItemChanged(positionOf(session.id))
        onSelectionChanged(selectedIds.size)
    }

    /** Select every loaded chat ("All" button). */
    fun selectAll() {
        for (s in sessions) selectedIds.add(s.id)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    /** Enter selection mode from the header "Select" button (no long-press needed). */
    fun enterSelectionMode() {
        selecting = true
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    /** Leave selection mode. */
    fun clearSelection() {
        selectedIds.clear()
        selecting = false
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MateViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mate, parent, false)
        return MateViewHolder(view)
    }

    override fun getItemCount(): Int = sessions.size

    override fun onBindViewHolder(holder: MateViewHolder, position: Int) {
        val session = sessions[position]
        // Mate's name: the user-given name first, otherwise the persona's
        // default model name (Aisha / Arjun / Sana) — never "VibeMate".
        val name = session.mateName.ifEmpty { PersonaPrompts.defaultName(session.persona) }
        holder.avatar.setText(
            name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "V"
        )
        holder.name.setText(name)
        holder.subtitle.setText(relationshipLabel(session.persona))
        val inSelection = selecting || selectedIds.isNotEmpty()
        val checked = selectedIds.contains(session.id)
        holder.check.setVisibility(if (checked) View.VISIBLE else View.INVISIBLE)
        holder.itemView.setBackgroundColor(if (checked) 0x264CAF50 else Color.TRANSPARENT)
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                if (inSelection) toggle(sessions[pos]) else onMateClicked(sessions[pos])
            }
        }
        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                // WhatsApp-style tick: the long-press that starts selection gives
                // a short vibration so the user knows the row "locked" into select.
                holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                toggle(sessions[pos])
                true
            } else {
                false
            }
        }
    }

    private fun positionOf(id: String): Int {
        return sessions.indexOfFirst { it.id == id }
    }

    /** Relationship label for a persona id (list subtitle). Both gay personas are
     *  same-sex couples, so the label says which couple the mate is. */
    private fun relationshipLabel(persona: String): String {
        return when (PersonaPrompts.normalizePersona(persona)) {
            "boyfriend" -> "Boyfriend"
            "crush" -> "Crush"
            "friend" -> "Friend"
            PersonaPrompts.GAY_BOY -> "Boy Gay"
            PersonaPrompts.GAY_GIRL -> "Girl Gay"
            else -> "Girlfriend"
        }
    }
}