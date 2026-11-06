package com.ping.verbead.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.ping.verbead.FloatingBubbleService
import com.ping.verbead.R
import com.ping.verbead.VoiceAccessibilityService
import com.ping.verbead.bubble.BubbleModeConfigAdapter
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.util.HapticUtil

class BubbleSettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvBubbleBadge: TextView
    private lateinit var btnGrantOverlay: MaterialButton
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvOverlayRestrictedHelp: TextView
    private lateinit var btnGrantAccessibility: MaterialButton
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvRestrictedSettingsHelp: TextView
    private lateinit var dividerAppInfoRow: View
    private lateinit var layoutAppInfoRow: View
    private lateinit var btnOpenAppInfo: MaterialButton

    private lateinit var layoutShowOnlyOnKeyboardToggle: View
    private lateinit var switchShowOnlyOnKeyboard: MaterialSwitch

    private lateinit var layoutMultiBubbleToggle: View
    private lateinit var switchMultiBubble: MaterialSwitch
    private lateinit var layoutMultiBubbleCount: View
    private lateinit var dividerMultiBubbleCount: View
    private lateinit var sbMultiBubbleCount: SeekBar
    private lateinit var tvMultiBubbleCountValue: TextView

    private lateinit var rvBubbleModes: RecyclerView
    private lateinit var tvBubbleModesSummary: TextView

    private lateinit var layoutPasteModeToggle: View
    private lateinit var switchPasteMode: MaterialSwitch
    private lateinit var tvPasteModeDesc: TextView

    private lateinit var layoutHistoryEnabledToggle: View
    private lateinit var switchHistoryEnabled: MaterialSwitch
    private lateinit var tvHistoryDesc: TextView
    private lateinit var layoutHistoryLimit: View
    private lateinit var dividerHistoryLimit: View
    private lateinit var sbHistoryLimit: SeekBar
    private lateinit var tvHistoryLimitValue: TextView

    private lateinit var sbPendingTextCount: SeekBar
    private lateinit var tvPendingTextCountValue: TextView

    private var bubbleModeConfigAdapter: BubbleModeConfigAdapter? = null
    private var bubbleModesTouchHelper: ItemTouchHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_bubble)

        bindViews()
        setupToolbar()
        setupListeners()
        setupBubbleModesRecyclerView()
    }

    /** 程式化同步開關時為 true，避免觸發使用者操作的監聽（會重複震動並再次通知懸浮球服務）。 */
    private var syncingMultiBubbleSwitch = false

    /**
     * 搖晃複製或拖曳刪除懸浮球時，服務端會直接改寫「多球並存」的設定值，
     * 這裡的開關與數量要跟著更新，否則設定頁會一直顯示舊狀態。
     */
    private val bubblePrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ModelConfig.KEY_MULTI_BUBBLE_ENABLED || key == ModelConfig.KEY_MULTI_BUBBLE_COUNT) {
            runOnUiThread { if (!isFinishing && !isDestroyed) syncMultiBubbleFromPrefs() }
        }
    }

    private fun syncMultiBubbleFromPrefs() {
        val enabled = ModelConfig.isMultiBubbleEnabled(this)
        if (switchMultiBubble.isChecked != enabled) {
            syncingMultiBubbleSwitch = true
            switchMultiBubble.isChecked = enabled
            syncingMultiBubbleSwitch = false
        }
        updateMultiBubbleUI()
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(ModelConfig.PREF_BUBBLE_SETTINGS, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(bubblePrefsListener)
    }

    override fun onStop() {
        getSharedPreferences(ModelConfig.PREF_BUBBLE_SETTINGS, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(bubblePrefsListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updatePermissions()
        syncMultiBubbleFromPrefs()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        tvBubbleBadge = findViewById(R.id.tv_bubble_badge)
        btnGrantOverlay = findViewById(R.id.btn_grant_overlay)
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        tvOverlayRestrictedHelp = findViewById(R.id.tv_overlay_restricted_help)
        btnGrantAccessibility = findViewById(R.id.btn_grant_accessibility)
        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status)
        tvRestrictedSettingsHelp = findViewById(R.id.tv_restricted_settings_help)
        dividerAppInfoRow = findViewById(R.id.divider_app_info_row)
        layoutAppInfoRow = findViewById(R.id.layout_app_info_row)
        btnOpenAppInfo = findViewById(R.id.btn_open_app_info)

        layoutShowOnlyOnKeyboardToggle = findViewById(R.id.layout_show_only_on_keyboard_toggle)
        switchShowOnlyOnKeyboard = findViewById(R.id.switch_show_only_on_keyboard)

        layoutMultiBubbleToggle = findViewById(R.id.layout_multi_bubble_toggle)
        switchMultiBubble = findViewById(R.id.switch_multi_bubble)
        layoutMultiBubbleCount = findViewById(R.id.layout_multi_bubble_count)
        dividerMultiBubbleCount = findViewById(R.id.divider_multi_bubble_count)
        sbMultiBubbleCount = findViewById(R.id.sb_multi_bubble_count)
        tvMultiBubbleCountValue = findViewById(R.id.tv_multi_bubble_count_value)

        rvBubbleModes = findViewById(R.id.rv_bubble_modes)
        tvBubbleModesSummary = findViewById(R.id.tv_bubble_modes_summary)

        layoutPasteModeToggle = findViewById(R.id.layout_paste_mode_toggle)
        switchPasteMode = findViewById(R.id.switch_paste_mode)
        tvPasteModeDesc = findViewById(R.id.tv_injection_method_desc)

        layoutHistoryEnabledToggle = findViewById(R.id.layout_history_enabled_toggle)
        switchHistoryEnabled = findViewById(R.id.switch_history_enabled)
        tvHistoryDesc = findViewById(R.id.tv_history_enabled_desc)
        layoutHistoryLimit = findViewById(R.id.layout_history_limit)
        dividerHistoryLimit = findViewById(R.id.divider_history_limit)
        sbHistoryLimit = findViewById(R.id.sb_history_limit)
        tvHistoryLimitValue = findViewById(R.id.tv_history_limit_value)
        sbPendingTextCount = findViewById(R.id.sb_pending_text_count)
        tvPendingTextCountValue = findViewById(R.id.tv_pending_text_count_value)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }
    }

    private fun setupListeners() {
        btnGrantOverlay.setOnClickListener {
            HapticUtil.click(this)
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        tvOverlayRestrictedHelp.setOnClickListener {
            showRestrictedSettingsDialog()
        }

        btnGrantAccessibility.setOnClickListener {
            HapticUtil.click(this)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        tvRestrictedSettingsHelp.setOnClickListener {
            showRestrictedSettingsDialog()
        }

        val openAppInfo = View.OnClickListener {
            HapticUtil.click(this)
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
        layoutAppInfoRow.setOnClickListener(openAppInfo)
        btnOpenAppInfo.setOnClickListener(openAppInfo)

        // Show only on keyboard
        layoutShowOnlyOnKeyboardToggle.setOnClickListener {
            switchShowOnlyOnKeyboard.toggle()
        }
        switchShowOnlyOnKeyboard.isChecked = ModelConfig.isShowOnlyOnKeyboard(this)
        switchShowOnlyOnKeyboard.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setShowOnlyOnKeyboard(this, isChecked)
            FloatingBubbleService.instance?.applyKeyboardOnlySetting()
        }

        // Multi bubble
        layoutMultiBubbleToggle.setOnClickListener {
            switchMultiBubble.toggle()
        }
        switchMultiBubble.isChecked = ModelConfig.isMultiBubbleEnabled(this)
        switchMultiBubble.setOnCheckedChangeListener { _, isChecked ->
            if (syncingMultiBubbleSwitch) return@setOnCheckedChangeListener
            HapticUtil.click(this)
            ModelConfig.setMultiBubbleEnabled(this, isChecked)
            updateMultiBubbleUI()
            FloatingBubbleService.instance?.updateMultiBubbleMode()
        }

        // Multi bubble count slider (2..4 -> progress 0..2)
        val curCount = ModelConfig.getMultiBubbleCount(this)
        sbMultiBubbleCount.progress = (curCount - 2).coerceIn(0, 2)
        tvMultiBubbleCountValue.text = getString(R.string.settings_bubble_count_value, curCount)
        sbMultiBubbleCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val count = progress + 2
                    ModelConfig.setMultiBubbleCount(this@BubbleSettingsActivity, count)
                    tvMultiBubbleCountValue.text = getString(R.string.settings_bubble_count_value, count)
                    HapticUtil.tick(this@BubbleSettingsActivity)
                    FloatingBubbleService.instance?.updateMultiBubbleMode()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Paste mode
        layoutPasteModeToggle.setOnClickListener {
            switchPasteMode.toggle()
        }
        switchPasteMode.isChecked = ModelConfig.isPasteModeEnabled(this)
        updatePasteModeDesc(switchPasteMode.isChecked)
        switchPasteMode.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setPasteModeEnabled(this, isChecked)
            updatePasteModeDesc(isChecked)
        }

        // History enabled
        layoutHistoryEnabledToggle.setOnClickListener {
            switchHistoryEnabled.toggle()
        }
        switchHistoryEnabled.isChecked = ModelConfig.isHistoryEnabled(this)
        updateHistoryUI(switchHistoryEnabled.isChecked)
        switchHistoryEnabled.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setHistoryEnabled(this, isChecked)
            updateHistoryUI(isChecked)
            FloatingBubbleService.instance?.updateBubbleModesConfig()
        }

        // History limit slider (1..12 -> progress 0..11)
        val curLimit = ModelConfig.getHistoryLimit(this)
        sbHistoryLimit.progress = (curLimit - 1).coerceIn(0, 11)
        tvHistoryLimitValue.text = getString(R.string.settings_bubble_history_count_value, curLimit)
        sbHistoryLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val limit = progress + 1
                    ModelConfig.setHistoryLimit(this@BubbleSettingsActivity, limit)
                    tvHistoryLimitValue.text = getString(R.string.settings_bubble_history_count_value, limit)
                    HapticUtil.tick(this@BubbleSettingsActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        setupPendingTextSlider()
    }

    private fun pendingCountLabel(count: Int): String =
        if (count == 0) getString(R.string.settings_bubble_pending_off) else getString(R.string.settings_bubble_pending_value, count)

    /** Floating quick-fill text: 0..5 waiting results, 0 = off. */
    private fun setupPendingTextSlider() {
        val cur = ModelConfig.getPendingTextCount(this)
        sbPendingTextCount.progress = cur
        tvPendingTextCountValue.text = pendingCountLabel(cur)
        sbPendingTextCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    ModelConfig.setPendingTextCount(this@BubbleSettingsActivity, progress)
                    tvPendingTextCountValue.text = pendingCountLabel(progress)
                    HapticUtil.tick(this@BubbleSettingsActivity)
                    FloatingBubbleService.instance?.onPendingTextCountChanged()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupBubbleModesRecyclerView() {
        rvBubbleModes.layoutManager = LinearLayoutManager(this)
        val adapter = BubbleModeConfigAdapter(
            context = this,
            onStartDrag = { viewHolder ->
                HapticUtil.tick(this)
                bubbleModesTouchHelper?.startDrag(viewHolder)
            },
            onConfigChanged = {
                HapticUtil.click(this)
                FloatingBubbleService.instance?.updateBubbleModesConfig()
                updateMultiBubbleUI()
            },
            onLastModeProtected = {
                HapticUtil.tick(this)
                Toast.makeText(this, getString(R.string.settings_bubble_toast_min_one_mode), Toast.LENGTH_SHORT).show()
            }
        )
        bubbleModeConfigAdapter = adapter
        rvBubbleModes.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                val moved = adapter.onItemMove(fromPos, toPos)
                if (moved) {
                    HapticUtil.tick(this@BubbleSettingsActivity)
                    FloatingBubbleService.instance?.updateBubbleModesConfig()
                }
                return moved
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.85f
                    viewHolder?.itemView?.scaleY = 1.02f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleY = 1.0f
                HapticUtil.click(this@BubbleSettingsActivity)
                FloatingBubbleService.instance?.updateBubbleModesConfig()
            }
        }
        bubbleModesTouchHelper = ItemTouchHelper(callback)
        bubbleModesTouchHelper?.attachToRecyclerView(rvBubbleModes)
    }

    private fun updatePermissions() {
        val overlayGranted = Settings.canDrawOverlays(this)
        if (overlayGranted) {
            tvOverlayStatus.text = getString(R.string.settings_bubble_overlay_granted)
            btnGrantOverlay.text = getString(R.string.settings_bubble_button_granted)
            btnGrantOverlay.isEnabled = false
            tvOverlayRestrictedHelp.visibility = View.GONE
        } else {
            tvOverlayStatus.text = getString(R.string.settings_bubble_overlay_not_granted)
            btnGrantOverlay.text = getString(R.string.settings_bubble_overlay_action)
            btnGrantOverlay.isEnabled = true
            tvOverlayRestrictedHelp.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
        }

        val accessibilityEnabled = isAccessibilityServiceEnabled()
        if (accessibilityEnabled) {
            tvAccessibilityStatus.text = getString(R.string.settings_bubble_accessibility_running)
            btnGrantAccessibility.text = getString(R.string.settings_bubble_button_enabled)
            btnGrantAccessibility.isEnabled = false
            tvRestrictedSettingsHelp.visibility = View.GONE
        } else {
            tvAccessibilityStatus.text = getString(R.string.settings_bubble_accessibility_not_enabled)
            btnGrantAccessibility.text = getString(R.string.settings_bubble_autofill_action)
            btnGrantAccessibility.isEnabled = true
            tvRestrictedSettingsHelp.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
        }

        val allReady = overlayGranted && accessibilityEnabled
        if (allReady) {
            tvBubbleBadge.text = getString(R.string.common_ready)
            tvBubbleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvBubbleBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
            layoutAppInfoRow.visibility = View.GONE
            dividerAppInfoRow.visibility = View.GONE
            tvOverlayRestrictedHelp.visibility = View.GONE
            tvRestrictedSettingsHelp.visibility = View.GONE
        } else {
            tvBubbleBadge.text = getString(R.string.settings_bubble_status_not_ready)
            tvBubbleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warning_text))
            tvBubbleBadge.setBackgroundResource(R.drawable.bg_status_badge_warning)
            layoutAppInfoRow.visibility = View.VISIBLE
            dividerAppInfoRow.visibility = View.VISIBLE
        }
    }

    private fun updateMultiBubbleUI() {
        val enabled = switchMultiBubble.isChecked
        val enabledModes = ModelConfig.getEnabledBubbleModes(this)

        layoutMultiBubbleCount.visibility = if (enabled) View.VISIBLE else View.GONE
        dividerMultiBubbleCount.visibility = if (enabled) View.VISIBLE else View.GONE
        sbMultiBubbleCount.isEnabled = enabled

        val count = ModelConfig.getMultiBubbleCount(this)
        sbMultiBubbleCount.progress = (count - 2).coerceIn(0, 2)
        tvMultiBubbleCountValue.text = getString(R.string.settings_bubble_count_value, count)

        tvBubbleModesSummary.text = if (enabled) {
            getString(R.string.settings_bubble_modes_summary_multi, enabledModes.size)
        } else {
            getString(R.string.settings_bubble_modes_summary_single)
        }
        bubbleModeConfigAdapter?.notifyDataSetChanged()
    }

    private fun updatePasteModeDesc(isPaste: Boolean) {
        tvPasteModeDesc.text = if (isPaste) {
            getString(R.string.settings_bubble_paste_desc_on)
        } else {
            getString(R.string.settings_bubble_paste_desc_off)
        }
    }

    private fun updateHistoryUI(enabled: Boolean) {
        layoutHistoryLimit.visibility = if (enabled) View.VISIBLE else View.GONE
        dividerHistoryLimit.visibility = if (enabled) View.VISIBLE else View.GONE
        sbHistoryLimit.isEnabled = enabled

        val limit = ModelConfig.getHistoryLimit(this)
        sbHistoryLimit.progress = (limit - 1).coerceIn(0, 11)
        tvHistoryLimitValue.text = getString(R.string.settings_bubble_history_count_value, limit)

        tvHistoryDesc.text = if (enabled) {
            getString(R.string.settings_bubble_history_desc_on)
        } else {
            getString(R.string.settings_bubble_history_desc_off)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "${packageName}/${VoiceAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    private fun showRestrictedSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_bubble_restricted_dialog_title)
            .setMessage(
                getString(R.string.settings_bubble_restricted_dialog_message)
            )
            .setPositiveButton(R.string.settings_bubble_restricted_dialog_action) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.settings_bubble_restricted_dialog_dismiss, null)
            .show()
    }
}
