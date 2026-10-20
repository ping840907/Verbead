package com.ping.verbead.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ping.verbead.QuickPhrasesManager
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.util.HapticUtil

/**
 * Controller for the Quick Phrases and History Drawer overlay in FloatingBubbleService.
 * Handles drawer view inflation, window management, animations, drag-and-drop reordering,
 * add/edit/delete dialogs, and item selection callbacks.
 */
class PhrasesDrawerController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val listener: Listener
) {

    interface Listener {
        fun onPhraseSelected(phrase: String)
        fun onDrawerClosed(hideX: Boolean)
        fun onShowXButton()
        fun onHideXButton()
    }

    companion object {
        private const val TAG = "PhrasesDrawerController"
    }

    private var phrasesDrawerView: View? = null
    private var phrasesDrawerLayoutParams: WindowManager.LayoutParams? = null
    private var phrasesItemTouchHelper: ItemTouchHelper? = null
    private var quickPhrasesAdapter: QuickPhrasesAdapter? = null
    private val drawerItems: MutableList<DrawerItem> = mutableListOf()

    var isActive: Boolean = false
        private set

    fun open(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        if (isActive) return

        if (phrasesDrawerView == null) {
            phrasesDrawerView = LayoutInflater.from(context).inflate(R.layout.layout_floating_phrases_drawer, null)
        }
        val drawer = phrasesDrawerView ?: return

        val density = context.resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val maxDrawerWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val drawerWidth = (260 * density).toInt().coerceAtMost(maxDrawerWidth)

        val btnClose = drawer.findViewById<ImageButton>(R.id.btn_close_phrases_drawer)
        val btnAdd = drawer.findViewById<ImageButton>(R.id.btn_add_phrase)
        val rvPhrases = drawer.findViewById<RecyclerView>(R.id.rv_phrases)

        btnClose.setOnClickListener {
            HapticUtil.click(context)
            close()
        }

        btnAdd.setOnClickListener {
            HapticUtil.click(context)
            showAddPhraseDialog()
        }

        quickPhrasesAdapter = QuickPhrasesAdapter(
            items = drawerItems,
            onItemClick = { text ->
                listener.onPhraseSelected(text)
            },
            onEditPhrase = { phrase ->
                HapticUtil.click(context)
                showEditPhraseDialog(phrase)
            },
            onDeletePhrase = { phrase ->
                HapticUtil.click(context)
                showDeletePhraseDialog(phrase)
            },
            onSaveHistoryAsPhrase = { text ->
                HapticUtil.click(context)
                QuickPhrasesManager.add(context, text)
                Toast.makeText(context, R.string.toast_phrase_saved_from_history, Toast.LENGTH_SHORT).show()
                refreshPhrasesList()
            },
            onDeleteHistory = { historyIndex ->
                HapticUtil.click(context)
                QuickPhrasesManager.removeHistoryAt(context, historyIndex)
                refreshPhrasesList()
            },
            onClearHistory = {
                HapticUtil.click(context)
                QuickPhrasesManager.clearHistory(context)
                Toast.makeText(context, R.string.toast_history_cleared, Toast.LENGTH_SHORT).show()
                refreshPhrasesList()
            },
            onStartDrag = { viewHolder ->
                phrasesItemTouchHelper?.startDrag(viewHolder)
            }
        )
        rvPhrases.layoutManager = LinearLayoutManager(context)
        rvPhrases.adapter = quickPhrasesAdapter

        setupDragAndDrop(rvPhrases)
        refreshPhrasesList()

        // 測量抽屜實際高度以計算貼齊懸浮球的精確 Y 座標與縮放軸心
        drawer.measure(
            View.MeasureSpec.makeMeasureSpec(drawerWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxAllowedHeight = maxOf((120 * density).toInt(), safeBottom - (16 * density).toInt())
        val actualHeight = drawer.measuredHeight.coerceAtLeast((100 * density).toInt())
        val finalHeight = actualHeight.coerceAtMost(maxAllowedHeight)

        val maxAllowedY = safeBottom - finalHeight - (8 * density).toInt()
        val targetY = anchorY.coerceIn(0, maxOf(0, maxAllowedY))

        phrasesDrawerLayoutParams = WindowManager.LayoutParams(
            drawerWidth,
            if (actualHeight > maxAllowedHeight) maxAllowedHeight else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (isDockedOnRight) Gravity.TOP or Gravity.RIGHT else Gravity.TOP or Gravity.LEFT
            x = bubbleWidthPx + gap
            y = targetY
        }

        // 以懸浮球垂直中心為軸心展開動畫，營造從懸浮球直接呼出的視覺感受
        val bubbleCenterY = anchorY + (30 * density)
        val relativePivotY = (bubbleCenterY - targetY).coerceIn(0f, finalHeight.toFloat())

        if (isDockedOnRight) {
            drawer.pivotX = drawerWidth.toFloat()
            drawer.translationX = (24 * density)
        } else {
            drawer.pivotX = 0f
            drawer.translationX = -(24 * density)
        }
        drawer.pivotY = relativePivotY
        drawer.alpha = 0f
        drawer.scaleX = 0.6f
        drawer.scaleY = 0.6f

        try {
            windowManager.addView(drawer, phrasesDrawerLayoutParams)
            isActive = true
            listener.onShowXButton()

            drawer.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add phrasesDrawerView", e)
        }
    }

    fun close(
        hideX: Boolean = true,
        anchorY: Int = 0,
        isDockedOnRight: Boolean = false
    ) {
        if (!isActive) return
        isActive = false
        if (hideX) {
            listener.onHideXButton()
        }
        val drawer = phrasesDrawerView ?: return
        val density = context.resources.displayMetrics.density
        val targetTranslationX = if (isDockedOnRight) (24 * density) else -(24 * density)
        val bubbleCenterY = anchorY + (30 * density)
        val currentTargetY = phrasesDrawerLayoutParams?.y ?: anchorY
        val drawerHeight = drawer.height.toFloat().takeIf { it > 0f } ?: (100 * density)
        drawer.pivotY = (bubbleCenterY - currentTargetY).coerceIn(0f, drawerHeight)
        drawer.pivotX = if (isDockedOnRight) (drawer.width.toFloat().takeIf { it > 0f } ?: (260 * density)) else 0f

        drawer.animate()
            .alpha(0f)
            .translationX(targetTranslationX)
            .scaleX(0.6f)
            .scaleY(0.6f)
            .setDuration(180)
            .withEndAction {
                if (drawer.isAttachedToWindow) {
                    try {
                        windowManager.removeView(drawer)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove phrasesDrawerView", e)
                    }
                }
                phrasesDrawerView = null
                phrasesItemTouchHelper = null
                listener.onDrawerClosed(hideX)
            }
            .start()
    }

    fun updatePosition(
        anchorY: Int,
        isDockedOnRight: Boolean,
        screenWidth: Int,
        safeBottom: Int
    ) {
        val drawer = phrasesDrawerView ?: return
        val lp = phrasesDrawerLayoutParams ?: return
        val density = context.resources.displayMetrics.density
        val bubbleWidthPx = (60 * density).toInt()
        val gap = (10 * density).toInt()
        val maxDrawerWidth = screenWidth - bubbleWidthPx - gap - (12 * density).toInt()
        val drawerWidth = (260 * density).toInt().coerceAtMost(maxDrawerWidth)

        drawer.measure(
            View.MeasureSpec.makeMeasureSpec(drawerWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val maxAllowedHeight = maxOf((120 * density).toInt(), safeBottom - (16 * density).toInt())
        val actualHeight = drawer.measuredHeight.coerceAtLeast((100 * density).toInt())
        val finalHeight = actualHeight.coerceAtMost(maxAllowedHeight)

        lp.gravity = if (isDockedOnRight) Gravity.TOP or Gravity.RIGHT else Gravity.TOP or Gravity.LEFT
        lp.x = bubbleWidthPx + gap
        val maxAllowedY = safeBottom - finalHeight - (8 * density).toInt()
        lp.width = drawerWidth
        lp.height = if (actualHeight > maxAllowedHeight) maxAllowedHeight else WindowManager.LayoutParams.WRAP_CONTENT
        lp.y = anchorY.coerceIn(0, maxOf(0, maxAllowedY))
        if (drawer.isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(drawer, lp)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update phrasesDrawerView layout", e)
            }
        }
    }

    fun destroy() {
        val drawer = phrasesDrawerView
        if (drawer != null && drawer.isAttachedToWindow) {
            try {
                windowManager.removeView(drawer)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove phrasesDrawerView in destroy", e)
            }
        }
        phrasesDrawerView = null
        isActive = false
    }

    fun refreshPhrasesList() {
        val drawer = phrasesDrawerView ?: return
        val emptyView = drawer.findViewById<View>(R.id.ll_empty_phrases)
        val rvPhrases = drawer.findViewById<RecyclerView>(R.id.rv_phrases) ?: return

        val history = QuickPhrasesManager.loadHistory(context)
        val phrases = QuickPhrasesManager.load(context)

        drawerItems.clear()

        // 歷史紀錄置頂（依設定保留筆數）
        if (history.isNotEmpty()) {
            val historyLimit = ModelConfig.getHistoryLimit(context)
            drawerItems.add(DrawerItem.Header(context.getString(R.string.header_recent_history, historyLimit), canClear = true))
            history.forEachIndexed { i, text ->
                drawerItems.add(DrawerItem.History(text, i))
            }
        }

        // 常用片語區段
        if (phrases.isNotEmpty()) {
            drawerItems.add(DrawerItem.Header(context.getString(R.string.header_quick_phrases), canClear = false))
            phrases.forEachIndexed { i, text ->
                drawerItems.add(DrawerItem.Phrase(text, i))
            }
        }

        if (drawerItems.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            rvPhrases.visibility = View.GONE
        } else {
            emptyView?.visibility = View.GONE
            rvPhrases.visibility = View.VISIBLE
            val density = context.resources.displayMetrics.density
            rvPhrases.layoutParams = rvPhrases.layoutParams.apply {
                height = if (drawerItems.size >= 5) {
                    (280 * density).toInt()
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            quickPhrasesAdapter?.notifyDataSetChanged()
        }
    }

    private fun setupDragAndDrop(recyclerView: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true
            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun getMovementFlags(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (viewHolder is QuickPhrasesAdapter.PhraseViewHolder) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    0
                }
            }

            override fun canDropOver(
                rv: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return target is QuickPhrasesAdapter.PhraseViewHolder
            }

            override fun onMove(
                rv: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                if (viewHolder !is QuickPhrasesAdapter.PhraseViewHolder || target !is QuickPhrasesAdapter.PhraseViewHolder) return false

                quickPhrasesAdapter?.moveItem(fromPos, toPos)
                HapticUtil.tick(context)
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    HapticUtil.heavyClick(context)
                    viewHolder.itemView.animate()
                        .scaleX(1.03f)
                        .scaleY(1.03f)
                        .setDuration(120)
                        .start()
                    viewHolder.itemView.translationZ = 12f * context.resources.displayMetrics.density
                    viewHolder.itemView.setBackgroundResource(R.drawable.bg_phrase_item_dragging)
                }
            }

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                viewHolder.itemView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .start()
                viewHolder.itemView.translationZ = 0f
                viewHolder.itemView.setBackgroundResource(R.drawable.bg_phrase_item)
                HapticUtil.click(context)
                persistPhrasesFromAdapter()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }

        phrasesItemTouchHelper = ItemTouchHelper(callback).also {
            it.attachToRecyclerView(recyclerView)
        }
    }

    private fun persistPhrasesFromAdapter() {
        val updatedPhrases = drawerItems
            .filterIsInstance<DrawerItem.Phrase>()
            .map { it.text }
        QuickPhrasesManager.save(context, updatedPhrases)
    }

    private fun showAddPhraseDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_phrase_entry, null)
        val etPhrase = dialogView.findViewById<EditText>(R.id.et_phrase)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_phrase_add_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                HapticUtil.click(context)
                val text = etPhrase.text.toString().trim()
                if (text.isNotBlank()) {
                    QuickPhrasesManager.add(context, text)
                    Toast.makeText(context, R.string.toast_phrase_added, Toast.LENGTH_SHORT).show()
                    refreshPhrasesList()
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                HapticUtil.click(context)
            }
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        etPhrase.requestFocus()
    }

    private fun showEditPhraseDialog(currentPhrase: String) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_phrase_entry, null)
        val etPhrase = dialogView.findViewById<EditText>(R.id.et_phrase)
        etPhrase.setText(currentPhrase)
        etPhrase.setSelection(currentPhrase.length)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_phrase_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                HapticUtil.click(context)
                val newText = etPhrase.text.toString().trim()
                if (newText.isNotBlank()) {
                    val currentPhrases = QuickPhrasesManager.load(context).toMutableList()
                    val idx = currentPhrases.indexOf(currentPhrase)
                    if (idx >= 0) {
                        currentPhrases[idx] = newText
                        QuickPhrasesManager.save(context, currentPhrases)
                    }
                    Toast.makeText(context, R.string.toast_phrase_updated, Toast.LENGTH_SHORT).show()
                    refreshPhrasesList()
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                HapticUtil.click(context)
            }
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        etPhrase.requestFocus()
    }

    private fun showDeletePhraseDialog(phrase: String) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_phrase_delete_title)
            .setMessage(context.getString(R.string.dialog_phrase_delete_confirm, phrase))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                HapticUtil.click(context)
                val currentPhrases = QuickPhrasesManager.load(context).toMutableList()
                currentPhrases.remove(phrase)
                QuickPhrasesManager.save(context, currentPhrases)
                Toast.makeText(context, R.string.toast_phrase_deleted, Toast.LENGTH_SHORT).show()
                refreshPhrasesList()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                HapticUtil.click(context)
            }
            .create()

        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    // --- Data structures and Adapter for Phrases RecyclerView ---

    sealed class DrawerItem {
        data class Header(val title: String, val canClear: Boolean = false) : DrawerItem()
        data class History(val text: String, val originalIndex: Int) : DrawerItem()
        data class Phrase(val text: String, val originalIndex: Int) : DrawerItem()
    }

    class QuickPhrasesAdapter(
        private val items: MutableList<DrawerItem>,
        private val onItemClick: (String) -> Unit,
        private val onEditPhrase: (String) -> Unit,
        private val onDeletePhrase: (String) -> Unit,
        private val onSaveHistoryAsPhrase: (String) -> Unit,
        private val onDeleteHistory: (Int) -> Unit,
        private val onClearHistory: () -> Unit,
        private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_HISTORY = 1
            private const val TYPE_PHRASE = 2
        }

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTitle: TextView = view.findViewById(R.id.tv_header_title)
            val btnAction: TextView = view.findViewById(R.id.btn_header_action)
        }

        class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view.findViewById(R.id.layout_history_item)
            val tvText: TextView = view.findViewById(R.id.tv_history_text)
            val btnSave: ImageButton = view.findViewById(R.id.btn_save_as_phrase)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_history)
        }

        class PhraseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view.findViewById(R.id.layout_phrase_item)
            val tvText: TextView = view.findViewById(R.id.tv_phrase_text)
            val btnEdit: ImageButton = view.findViewById(R.id.btn_edit_phrase)
            val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_phrase)
            val ivDragHandle: ImageView = view.findViewById(R.id.iv_drag_handle)
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is DrawerItem.Header -> TYPE_HEADER
            is DrawerItem.History -> TYPE_HISTORY
            is DrawerItem.Phrase -> TYPE_PHRASE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_drawer_header, parent, false))
                TYPE_HISTORY -> HistoryViewHolder(inflater.inflate(R.layout.item_quick_history, parent, false))
                else -> PhraseViewHolder(inflater.inflate(R.layout.item_quick_phrase, parent, false))
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is DrawerItem.Header -> {
                    val h = holder as HeaderViewHolder
                    h.tvTitle.text = item.title
                    if (item.canClear) {
                        h.btnAction.visibility = View.VISIBLE
                        h.btnAction.setOnClickListener { onClearHistory() }
                    } else {
                        h.btnAction.visibility = View.GONE
                    }
                }
                is DrawerItem.History -> {
                    val h = holder as HistoryViewHolder
                    h.tvText.text = item.text
                    h.root.setOnClickListener { onItemClick(item.text) }
                    h.btnSave.setOnClickListener { onSaveHistoryAsPhrase(item.text) }
                    h.btnDelete.setOnClickListener { onDeleteHistory(item.originalIndex) }
                }
                is DrawerItem.Phrase -> {
                    val h = holder as PhraseViewHolder
                    h.tvText.text = item.text
                    h.root.setOnClickListener { onItemClick(item.text) }
                    h.btnEdit.setOnClickListener { onEditPhrase(item.text) }
                    h.btnDelete.setOnClickListener { onDeletePhrase(item.text) }
                    h.ivDragHandle.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            onStartDrag(holder)
                        }
                        false
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        fun moveItem(fromPos: Int, toPos: Int) {
            if (fromPos == toPos) return
            val item = items.removeAt(fromPos)
            items.add(toPos, item)
            notifyItemMoved(fromPos, toPos)
        }
    }
}
