package com.example.vibemate.admin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Read-only view of one chat's history, oldest message first.
 *
 * Mirrors what the user actually saw: the user's messages in a green bubble, the
 * mate's in a white one, photos shown inline (the same base64 JPEG the app stored)
 * and voice notes / documents as a labelled file row.
 */
class AdminMessagesAdapter : RecyclerView.Adapter<AdminMessagesAdapter.MessageViewHolder>() {

    private val messages = ArrayList<AdminMessage>()

    // Photo decode cache: base64 → bitmap, so scrolling stays smooth.
    private val photoCache = object : LruCache<String, Bitmap>(6) {}

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val head: AppCompatTextView = itemView.findViewById(R.id.adminMsgHead)
        val image: AppCompatImageView = itemView.findViewById(R.id.adminMsgImage)
        val body: AppCompatTextView = itemView.findViewById(R.id.adminMsgBody)
        val file: AppCompatTextView = itemView.findViewById(R.id.adminMsgFile)
    }

    fun submit(list: List<AdminMessage>) {
        messages.clear()
        messages.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun getItemCount(): Int = messages.size

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]

        holder.itemView.setBackgroundResource(
            if (message.fromUser) R.drawable.bubble_admin_user else R.drawable.bubble_admin_mate
        )
        holder.head.text = buildString {
            append(if (message.fromUser) "USER" else "MATE")
            append(" • ").append(stamp(message.createdAt))
            if (message.attachmentLabel.isNotEmpty()) append(" • ").append(message.attachmentLabel)
        }

        val caption = message.content.ifEmpty {
            if (message.attachmentLabel.isNotEmpty()) "(" + message.attachmentLabel + ")" else ""
        }
        holder.body.text = caption
        holder.body.visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE

        // Inline photo (the base64 JPEG the app saved for the mate).
        if (message.hasPhoto) {
            val bitmap = decodeScaled(message.data)
            if (bitmap != null) {
                holder.image.setImageBitmap(bitmap)
                holder.image.visibility = View.VISIBLE
            } else {
                holder.image.setImageDrawable(null)
                holder.image.visibility = View.GONE
            }
        } else {
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
        }

        // Voice note / document / video: a labelled row with the stored size.
        val isFile = message.attachmentLabel.isNotEmpty() &&
            !message.type.equals("IMAGE", ignoreCase = true)
        if (isFile) {
            val icon = if (message.type.equals("AUDIO", ignoreCase = true)) "\uD83C\uDF99" else "\uD83D\uDCC4"
            val name = message.fileName.ifEmpty { message.attachmentLabel }
            val kb = message.data.length * 3 / 4 / 1024
            holder.file.text = if (kb > 0) "$icon  $name  (${kb} KB)" else "$icon  $name"
            holder.file.visibility = View.VISIBLE
        } else {
            holder.file.text = ""
            holder.file.visibility = View.GONE
        }
    }

    /** "2026-01-02 03:04:05" from the stored ISO-8601 UTC timestamp. */
    private fun stamp(createdAt: String): String {
        if (createdAt.isEmpty()) return "?"
        return createdAt.replace("T", " ").removeSuffix("Z").take(19)
    }

    /** Decode a base64 JPEG down to a bubble-sized bitmap (≤ 640 px wide). */
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
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, options) ?: return null
        photoCache.put(base64, bitmap)
        return bitmap
    }
}