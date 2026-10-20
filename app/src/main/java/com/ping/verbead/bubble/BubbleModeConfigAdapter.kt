package com.ping.verbead.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig
import java.util.Collections

class BubbleModeConfigAdapter(
    private val context: Context,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onConfigChanged: () -> Unit,
    private val onLastModeProtected: () -> Unit
) : RecyclerView.Adapter<BubbleModeConfigAdapter.ModeViewHolder>() {

    private val modeOrder = mutableListOf<Int>()

    init {
        reloadFromConfig()
    }

    fun reloadFromConfig() {
        modeOrder.clear()
        modeOrder.addAll(ModelConfig.getBubbleModeOrder(context))
        notifyDataSetChanged()
    }

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition !in modeOrder.indices || toPosition !in modeOrder.indices) return false
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(modeOrder, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(modeOrder, i, i - 1)
            }
        }
        ModelConfig.setBubbleModeOrder(context, modeOrder)
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    fun onDragFinished() {
        notifyItemRangeChanged(0, modeOrder.size)
        onConfigChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModeViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bubble_mode_config, parent, false)
        return ModeViewHolder(v)
    }

    override fun getItemCount(): Int = modeOrder.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ModeViewHolder, position: Int) {
        val mode = modeOrder[position]
        val meta = ModelConfig.getBubbleModeMeta(mode)
        val isEnabled = ModelConfig.isBubbleModeEnabled(context, mode)

        holder.tvTitle.text = meta.title
        holder.tvDesc.text = meta.description
        holder.ivIcon.setImageResource(getIconResForMode(mode))

        // Compute 1-based order rank among enabled modes
        if (isEnabled) {
            var enabledRank = 1
            for (i in 0 until position) {
                if (ModelConfig.isBubbleModeEnabled(context, modeOrder[i])) {
                    enabledRank++
                }
            }
            holder.tvOrderBadge.text = enabledRank.toString()
            holder.tvOrderBadge.setTextColor(ContextCompat.getColor(context, R.color.md_theme_light_primary))
            holder.cardRoot.alpha = 1.0f
        } else {
            holder.tvOrderBadge.text = "—"
            holder.tvOrderBadge.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            holder.cardRoot.alpha = 0.55f
        }

        holder.btnMoveUp.isEnabled = position > 0
        holder.btnMoveUp.alpha = if (position > 0) 1.0f else 0.25f
        holder.btnMoveDown.isEnabled = position < modeOrder.size - 1
        holder.btnMoveDown.alpha = if (position < modeOrder.size - 1) 1.0f else 0.25f

        holder.btnMoveUp.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos > 0) {
                onItemMove(pos, pos - 1)
                notifyItemRangeChanged(pos - 1, 2)
                onConfigChanged()
            }
        }

        holder.btnMoveDown.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos in 0 until modeOrder.size - 1) {
                onItemMove(pos, pos + 1)
                notifyItemRangeChanged(pos, 2)
                onConfigChanged()
            }
        }

        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = isEnabled

        val toggleAction = { targetEnabled: Boolean ->
            val updated = ModelConfig.setBubbleModeEnabled(context, mode, targetEnabled)
            if (!updated) {
                holder.switchEnabled.isChecked = true
                onLastModeProtected()
            } else {
                notifyItemRangeChanged(0, modeOrder.size)
                onConfigChanged()
            }
        }

        holder.switchEnabled.setOnClickListener {
            toggleAction(holder.switchEnabled.isChecked)
        }

        holder.layoutRoot.setOnClickListener {
            val next = !holder.switchEnabled.isChecked
            holder.switchEnabled.isChecked = next
            toggleAction(next)
        }

        holder.ivDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }
    }

    private fun getIconResForMode(mode: Int): Int {
        return when (mode) {
            ModelConfig.BUBBLE_MODE_OCR -> R.drawable.ic_ocr
            ModelConfig.BUBBLE_MODE_BARCODE -> R.drawable.ic_barcode
            ModelConfig.BUBBLE_MODE_PHRASES -> R.drawable.ic_quick_phrases
            ModelConfig.BUBBLE_MODE_SLM -> R.drawable.ic_ai_sparkle
            else -> R.drawable.ic_mic
        }
    }

    class ModeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardRoot: MaterialCardView = itemView.findViewById(R.id.card_mode_item)
        val layoutRoot: View = itemView.findViewById(R.id.layout_mode_item_root)
        val ivDragHandle: ImageView = itemView.findViewById(R.id.iv_mode_drag_handle)
        val tvOrderBadge: TextView = itemView.findViewById(R.id.tv_mode_order_badge)
        val ivIcon: ImageView = itemView.findViewById(R.id.iv_mode_icon)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_mode_title)
        val tvDesc: TextView = itemView.findViewById(R.id.tv_mode_desc)
        val btnMoveUp: ImageButton = itemView.findViewById(R.id.btn_mode_move_up)
        val btnMoveDown: ImageButton = itemView.findViewById(R.id.btn_mode_move_down)
        val switchEnabled: MaterialSwitch = itemView.findViewById(R.id.switch_mode_enabled)
    }
}
