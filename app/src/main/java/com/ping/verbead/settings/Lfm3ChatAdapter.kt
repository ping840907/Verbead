package com.ping.verbead.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.ping.verbead.R
import com.ping.verbead.engine.Lfm3ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Lfm3ChatAdapter(
    private val messages: MutableList<Lfm3ChatMessage>,
    private val onPlayVoice: (Lfm3ChatMessage) -> Unit
) : RecyclerView.Adapter<Lfm3ChatAdapter.MessageViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addMessage(msg: Lfm3ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun clearMessages() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun getMessages(): List<Lfm3ChatMessage> = messages

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lfm3_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val layoutUser: LinearLayout = itemView.findViewById(R.id.layout_user_bubble)
        private val tvUserText: TextView = itemView.findViewById(R.id.tv_user_text)
        private val tvUserTime: TextView = itemView.findViewById(R.id.tv_user_time)

        private val layoutAssistant: LinearLayout = itemView.findViewById(R.id.layout_assistant_bubble)
        private val tvAssistantText: TextView = itemView.findViewById(R.id.tv_assistant_text)
        private val tvAssistantTime: TextView = itemView.findViewById(R.id.tv_assistant_time)
        private val btnPlayVoice: MaterialButton = itemView.findViewById(R.id.btn_play_voice)

        fun bind(msg: Lfm3ChatMessage) {
            val formattedTime = timeFormat.format(Date(msg.timestamp))
            if (msg.isUser) {
                layoutUser.visibility = View.VISIBLE
                layoutAssistant.visibility = View.GONE
                tvUserText.text = msg.text
                tvUserTime.text = formattedTime
            } else {
                layoutUser.visibility = View.GONE
                layoutAssistant.visibility = View.VISIBLE
                tvAssistantText.text = msg.text
                tvAssistantTime.text = formattedTime
                btnPlayVoice.visibility = if (msg.text.isNotBlank() || (msg.audioSamples != null && msg.audioSamples.isNotEmpty())) View.VISIBLE else View.GONE
                btnPlayVoice.setOnClickListener {
                    onPlayVoice(msg)
                }
            }
        }
    }
}
