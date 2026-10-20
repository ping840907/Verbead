package com.ping.verbead.slm

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ping.verbead.R
import com.ping.verbead.util.HapticUtil

/**
 * RecyclerView adapter for displaying SLM chat conversations.
 */
class SlmChatAdapter(
    private val messages: MutableList<SlmChatMessage>,
    private val onCopyClick: (SlmChatMessage) -> Unit,
    private val onPasteClick: (SlmChatMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].role) {
            SlmChatMessage.Role.USER -> VIEW_TYPE_USER
            else -> VIEW_TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_slm_message_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_slm_message_assistant, parent, false)
            AssistantViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        if (holder is UserViewHolder) {
            holder.bind(message)
        } else if (holder is AssistantViewHolder) {
            holder.bind(message, onCopyClick, onPasteClick) {
                notifyItemChanged(holder.bindingAdapterPosition)
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_user_message)

        fun bind(message: SlmChatMessage) {
            tvMessage.text = message.content
        }
    }

    class AssistantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvModelTag: TextView = itemView.findViewById(R.id.tv_slm_model_tag)
        private val layoutThinkingCard: LinearLayout = itemView.findViewById(R.id.layout_slm_thinking_card)
        private val layoutThinkingHeader: LinearLayout = itemView.findViewById(R.id.layout_slm_thinking_header)
        private val tvThinkingTitle: TextView = itemView.findViewById(R.id.tv_slm_thinking_title)
        private val tvThinkingChevron: TextView = itemView.findViewById(R.id.tv_slm_thinking_chevron)
        private val tvThinkingContent: TextView = itemView.findViewById(R.id.tv_slm_thinking_content)
        private val tvAnswer: TextView = itemView.findViewById(R.id.tv_slm_answer)
        private val pbStreaming: ProgressBar = itemView.findViewById(R.id.pb_slm_streaming)
        private val layoutActions: LinearLayout = itemView.findViewById(R.id.layout_slm_actions)
        private val btnCopy: View = itemView.findViewById(R.id.btn_slm_copy)
        private val btnPaste: View = itemView.findViewById(R.id.btn_slm_paste)

        fun bind(
            message: SlmChatMessage,
            onCopyClick: (SlmChatMessage) -> Unit,
            onPasteClick: (SlmChatMessage) -> Unit,
            onToggleThinking: () -> Unit
        ) {
            tvModelTag.text = message.modelTag ?: "端側 AI"

            // Thinking block logic
            val hasThinking = !message.thinkingContent.isNullOrBlank()
            if (hasThinking) {
                layoutThinkingCard.visibility = View.VISIBLE
                tvThinkingContent.text = message.thinkingContent

                if (message.isThinkingExpanded) {
                    tvThinkingContent.visibility = View.VISIBLE
                    tvThinkingTitle.text = "思考推導過程 (點擊收合)"
                    tvThinkingChevron.text = "▲"
                } else {
                    tvThinkingContent.visibility = View.GONE
                    tvThinkingTitle.text = "思考推導過程 (點擊展開)"
                    tvThinkingChevron.text = "▼"
                }

                layoutThinkingHeader.setOnClickListener {
                    HapticUtil.click(itemView.context)
                    message.isThinkingExpanded = !message.isThinkingExpanded
                    onToggleThinking()
                }
            } else {
                layoutThinkingCard.visibility = View.GONE
            }

            // Answer content
            val cleanAnswer = message.getCleanAnswer()
            tvAnswer.text = cleanAnswer
            tvAnswer.visibility = if (cleanAnswer.isNotEmpty()) View.VISIBLE else View.GONE

            // Streaming indicator
            if (message.isStreaming) {
                pbStreaming.visibility = View.VISIBLE
                layoutActions.visibility = View.GONE
            } else {
                pbStreaming.visibility = View.GONE
                layoutActions.visibility = if (cleanAnswer.isNotEmpty()) View.VISIBLE else View.GONE
            }

            // Actions
            btnCopy.setOnClickListener {
                HapticUtil.click(itemView.context)
                onCopyClick(message)
            }

            btnPaste.setOnClickListener {
                HapticUtil.click(itemView.context)
                onPasteClick(message)
            }
        }
    }
}
