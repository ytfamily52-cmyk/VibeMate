package com.example.vibemate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * WhatsApp-style adapter: user's messages go right (green bubble + ticks),
 * VibeMate's messages go left (white bubble with the partner name header).
 * Attachment messages (photo / voice note / document) render like WhatsApp:
 * photos show inline in the bubble, audio/documents show a tappable file row.
 *
 * onAttachmentClicked fires when the user taps a photo / audio / document so
 * MainActivity can open it (player / viewer) — WhatsApp "open attachment".
 */
class ChatAdapter(
    private val onAttachmentClicked: (ChatMessage) -> Unit = {},
    private val onActionClicked: (ChatMessage) -> Unit = {},
    private val onSelectionChanged: (Int) -> Unit = {},
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = ArrayList<ChatMessage>()
    private val selectedIds = LinkedHashSet<String>()

    // Photo decode cache: base64 → bitmap. Decoding a full JPEG on every bind
    // stutters the scroll; the LruCache keeps ~6 photos resident.
    private val photoCache = object : LruCache<String, Bitmap>(6) {}

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val row: LinearLayout = itemView.findViewById(R.id.messageRow)
        val bubble: LinearLayout = itemView.findViewById(R.id.messageBubble)
        val nameText: AppCompatTextView = itemView.findViewById(R.id.senderName)
        val bodyText: AppCompatTextView = itemView.findViewById(R.id.messageBody)
        val metaText: AppCompatTextView = itemView.findViewById(R.id.messageMeta)
        val image: AppCompatImageView = itemView.findViewById(R.id.messageImage)
        val actionButton: AppCompatButton = itemView.findViewById(R.id.messageAction)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context

        if (message.sender == ChatMessage.Sender.USER) {
            // Own message: right side, WhatsApp-green bubble, time + ticks.
            // Name header shows the user's display name (like a two-person
            // WhatsApp chat where each side is labelled).
            holder.row.gravity = Gravity.END
            // Wide gap on the left, tight to the right edge — mirror image of
            // VibeMate's side so this bubble truly hugs the right end.
            setRowPadding(holder.row, startDp = 56, endDp = 8)
            holder.bubble.setBackgroundResource(R.drawable.bubble_user)
            holder.nameText.visibility = View.VISIBLE
            holder.nameText.text =
                if (message.displayName.isEmpty()) "You" else message.displayName
            holder.nameText.setTextColor(
                ContextCompat.getColor(context, android.R.color.holo_blue_dark),
            )
            holder.metaText.text = message.timeLabel + "  " + tickFor(message.status)
            holder.metaText.setTextColor(
                ContextCompat.getColor(context, tickColorFor(message.status)),
            )
        } else if (message.sender == ChatMessage.Sender.SYSTEM) {
            // Error / info row: centered grey pill, no name header, no ticks.
            holder.row.gravity = Gravity.CENTER_HORIZONTAL
            setRowPadding(holder.row, startDp = 56, endDp = 56)
            holder.bubble.setBackgroundResource(R.drawable.bubble_system)
            holder.nameText.visibility = View.GONE
            holder.metaText.text = message.timeLabel
            holder.metaText.setTextColor(
                ContextCompat.getColor(context, android.R.color.darker_gray),
            )
        } else {
            // VibeMate's side (real reply OR the typing indicator): left side,
            // white bubble with a green "VibeMate" name header, like a contact
            // name in a WhatsApp group. TYPING rows hide the timestamp so only
            // the name + animated dots show.
            holder.row.gravity = Gravity.START
            // Tight to the left edge, wide gap on the right — mirror image of
            // the user's side so this bubble truly hugs the left end.
            setRowPadding(holder.row, startDp = 8, endDp = 56)
            holder.bubble.setBackgroundResource(R.drawable.bubble_vibemate)
            holder.nameText.visibility = View.VISIBLE
            holder.nameText.text =
                if (message.displayName.isEmpty()) "Aisha" else message.displayName
            if (message.sender == ChatMessage.Sender.TYPING) {
                holder.metaText.text = ""
            } else {
                holder.metaText.text = message.timeLabel
            }
            holder.metaText.setTextColor(
                ContextCompat.getColor(context, android.R.color.darker_gray),
            )
        }
        // Attachment rendering (photo inline / tappable file row) + caption.
        bindAttachment(holder, message)
        bindSelection(holder, message)
    }

    /**
     * WhatsApp-style attachment bubbles:
     *  - IMAGE: photo shown inline (scaled to ≤ 240dp wide), caption below.
     *  - AUDIO / DOC: 🎙 / 📄 emoji + file name, tap opens it in a player/viewer.
     * TYPING rows hide the body (animated dots come from the metaText area).
     */
    private fun bindAttachment(holder: ChatViewHolder, message: ChatMessage) {
        val context = holder.itemView.context
        when (message.type) {
            "IMAGE" -> {
                holder.image.visibility = View.VISIBLE
                val bmp = decodeScaled(message.data)
                if (bmp != null) {
                    holder.image.setImageBitmap(bmp)
                    holder.image.visibility = View.VISIBLE
                    holder.image.setOnClickListener { onAttachmentClicked(message) }
                } else {
                    holder.image.visibility = View.GONE
                }
                holder.bodyText.visibility =
                    if (message.text.isEmpty()) View.GONE else View.VISIBLE
                holder.bodyText.text = message.text
            }
            "AUDIO", "DOC" -> {
                holder.image.visibility = View.GONE
                holder.bodyText.visibility = View.VISIBLE
                val icon = if (message.type == "AUDIO") "🎙" else "📄"
                val nm = message.fileName.ifEmpty {
                    if (message.type == "AUDIO") "Voice note" else "Document"
                }
                val caption = message.text
                holder.bodyText.text = "$icon  $nm"
                // A caption under the file row shows like WhatsApp's caption.
                if (caption.isNotEmpty()) {
                    holder.bodyText.text = "$icon  $nm\n$caption"
                }
                holder.bodyText.setTextColor(
                    ContextCompat.getColor(context, android.R.color.holo_blue_dark),
                )
                holder.bodyText.setOnClickListener { onAttachmentClicked(message) }
            }
            else -> {
                holder.image.visibility = View.GONE
                holder.bodyText.visibility = View.VISIBLE
                holder.bodyText.text = message.text
                holder.bodyText.setTextColor("#111B21".toIntColor())
                holder.bodyText.setOnClickListener(null)
            }
        }
        bindAction(holder, message)
    }

    /**
     * Call-to-action rows (the "Join Private Chat" invite after explicit content
     * in normal chat) render their message text plus a tappable button below it.
     */
    private fun bindAction(holder: ChatViewHolder, message: ChatMessage) {
        if (message.actionLabel.isEmpty()) {
            holder.actionButton.visibility = View.GONE
            holder.actionButton.setOnClickListener(null)
            return
        }
        holder.actionButton.visibility = View.VISIBLE
        holder.actionButton.text = message.actionLabel
        holder.actionButton.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * holder.itemView.resources.displayMetrics.density
            setColor(android.graphics.Color.parseColor("#128C7E"))
        }
        holder.actionButton.setOnClickListener { onActionClicked(message) }
    }

    /**
     * Message selection: long-press a user / VibeMate bubble to select it, then
     * tap bubbles to toggle more. SYSTEM / TYPING / action rows (and rows without
     * a stored id) are not selectable, so they get no listeners.
     */
    private fun bindSelection(holder: ChatViewHolder, message: ChatMessage) {
        val selectable = (message.sender == ChatMessage.Sender.USER ||
            message.sender == ChatMessage.Sender.VIBEMATE) && message.id.isNotEmpty()
        if (!selectable) {
            holder.bubble.setOnClickListener(null)
            holder.bubble.setOnLongClickListener(null)
            return
        }
        val inSelection = selectedIds.isNotEmpty()
        val selected = selectedIds.contains(message.id)
        holder.bubble.alpha = if (selected) 0.55f else 1f
        holder.bubble.setOnClickListener {
            if (inSelection) toggleSelection(message)
        }
        holder.bubble.setOnLongClickListener {
            holder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            toggleSelection(message)
            true
        }
        // While selecting, the attachment's own tap (open photo / document) is
        // disabled so a tap toggles the selection instead of opening the file.
        if (inSelection) {
            holder.bodyText.setOnClickListener(null)
            holder.image.setOnClickListener(null)
        }
    }

    /** Decode base64 JPEG down to a reasonable bubble size (≤ 640px wide). */
    private fun decodeScaled(base64: String): Bitmap? {
        if (base64.isEmpty()) return null
        val cached = photoCache.get(base64)
        if (cached != null) return cached
        val raw = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 640) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
        photoCache.put(base64, bmp)
        return bmp
    }

    /** "#111B21" → color int (the XML layout uses the same text colour). */
    private fun String.toIntColor(): Int = android.graphics.Color.parseColor(this)

    override fun getItemCount(): Int = messages.size

    fun add(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun remove(message: ChatMessage) {
        val index = messages.indexOf(message)
        if (index < 0) return
        messages.removeAt(index)
        selectedIds.remove(message.id)
        notifyItemRemoved(index)
    }

    fun updateStatus(message: ChatMessage, status: ChatMessage.Status) {
        val index = messages.indexOf(message)
        if (index < 0) return
        messages[index].status = status
        notifyItemChanged(index)
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        selectedIds.clear()
        if (size > 0) notifyItemRangeRemoved(0, size)
        onSelectionChanged(0)
    }

    // ---- in-chat message selection (long-press -> select -> delete) ----

    fun isSelectionMode(): Boolean = selectedIds.isNotEmpty()

    fun selectedCount(): Int = selectedIds.size

    fun selectedMessages(): List<ChatMessage> = messages.filter { it.id in selectedIds }

    fun toggleSelection(message: ChatMessage) {
        if (message.id.isEmpty()) return
        if (!selectedIds.add(message.id)) {
            selectedIds.remove(message.id)
        }
        notifyItemChanged(messages.indexOf(message))
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    /** Drop every selected bubble from the list (after they were deleted). */
    fun removeSelected() {
        val toRemove = messages.filter { it.id in selectedIds }
        if (toRemove.isEmpty()) return
        messages.removeAll(toRemove)
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun tickFor(status: ChatMessage.Status): String {
        return when (status) {
            ChatMessage.Status.SENDING -> "◷"
            ChatMessage.Status.SENT -> "✓"
            ChatMessage.Status.DELIVERED -> "✓✓"
            ChatMessage.Status.READ -> "✓✓"
            ChatMessage.Status.FAILED -> "⚠"
        }
    }

    private fun tickColorFor(status: ChatMessage.Status): Int {
        return when (status) {
            ChatMessage.Status.READ -> android.R.color.holo_blue_dark
            ChatMessage.Status.FAILED -> android.R.color.holo_red_dark
            else -> android.R.color.darker_gray
        }
    }

    /**
     * Per-side row padding so the two chat sides are true mirror images.
     *
     * The row is `match_parent`, so recycled rows must reset their padding —
     * otherwise a user row can keep a wide right gap (or a VibeMate row a
     * wide left gap) and the bubble stops hugging its edge. Top/bottom stay
     * the 3dp from the XML layout.
     */
    private fun setRowPadding(row: LinearLayout, startDp: Int, endDp: Int) {
        val density = row.resources.displayMetrics.density
        val startPx = (startDp * density + 0.5f).toInt()
        val endPx = (endDp * density + 0.5f).toInt()
        val topPx = (3 * density + 0.5f).toInt()
        row.setPaddingRelative(startPx, topPx, endPx, topPx)
    }
}
