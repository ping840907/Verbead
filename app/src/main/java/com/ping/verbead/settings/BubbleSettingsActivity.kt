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

    override fun onResume() {
        super.onResume()
        updatePermissions()
        updateMultiBubbleUI()
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
            HapticUtil.click(this)
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
            HapticUtil.click(this)
            switchMultiBubble.toggle()
        }
        switchMultiBubble.isChecked = ModelConfig.isMultiBubbleEnabled(this)
        switchMultiBubble.setOnCheckedChangeListener { _, isChecked ->
            HapticUtil.click(this)
            ModelConfig.setMultiBubbleEnabled(this, isChecked)
            updateMultiBubbleUI()
            FloatingBubbleService.instance?.updateMultiBubbleMode()
        }

        // Multi bubble count slider (2..5 -> progress 0..3)
        val curCount = ModelConfig.getMultiBubbleCount(this)
        sbMultiBubbleCount.progress = (curCount - 2).coerceIn(0, 3)
        tvMultiBubbleCountValue.text = "${curCount} 個"
        sbMultiBubbleCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val count = progress + 2
                    ModelConfig.setMultiBubbleCount(this@BubbleSettingsActivity, count)
                    tvMultiBubbleCountValue.text = "${count} 個"
                    HapticUtil.tick(this@BubbleSettingsActivity)
                    FloatingBubbleService.instance?.updateMultiBubbleMode()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Paste mode
        layoutPasteModeToggle.setOnClickListener {
            HapticUtil.click(this)
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
            HapticUtil.click(this)
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
        tvHistoryLimitValue.text = "${curLimit} 筆"
        sbHistoryLimit.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val limit = progress + 1
                    ModelConfig.setHistoryLimit(this@BubbleSettingsActivity, limit)
                    tvHistoryLimitValue.text = "${limit} 筆"
                    HapticUtil.tick(this@BubbleSettingsActivity)
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
                Toast.makeText(this, "至少需保留 1 個啟用的懸浮球功能模式", Toast.LENGTH_SHORT).show()
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
            tvOverlayStatus.text = "已取得顯示在上層權限"
            btnGrantOverlay.text = "已授權"
            btnGrantOverlay.isEnabled = false
            tvOverlayRestrictedHelp.visibility = View.GONE
        } else {
            tvOverlayStatus.text = "尚未授予顯示在上層權限"
            btnGrantOverlay.text = "前往授權"
            btnGrantOverlay.isEnabled = true
            tvOverlayRestrictedHelp.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
        }

        val accessibilityEnabled = isAccessibilityServiceEnabled()
        if (accessibilityEnabled) {
            tvAccessibilityStatus.text = "無障礙服務執行中，支援自動填入"
            btnGrantAccessibility.text = "已開啟"
            btnGrantAccessibility.isEnabled = false
            tvRestrictedSettingsHelp.visibility = View.GONE
        } else {
            tvAccessibilityStatus.text = "尚未啟用無障礙服務"
            btnGrantAccessibility.text = "前往開啟"
            btnGrantAccessibility.isEnabled = true
            tvRestrictedSettingsHelp.visibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
        }

        val allReady = overlayGranted && accessibilityEnabled
        if (allReady) {
            tvBubbleBadge.text = "已就緒"
            tvBubbleBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success_text))
            tvBubbleBadge.setBackgroundResource(R.drawable.bg_status_badge_success)
            layoutAppInfoRow.visibility = View.GONE
            dividerAppInfoRow.visibility = View.GONE
            tvOverlayRestrictedHelp.visibility = View.GONE
            tvRestrictedSettingsHelp.visibility = View.GONE
        } else {
            tvBubbleBadge.text = "未就緒"
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
        sbMultiBubbleCount.progress = (count - 2).coerceIn(0, 3)
        tvMultiBubbleCountValue.text = "${count} 個"

        tvBubbleModesSummary.text = if (enabled) {
            "已開啟多球：已啟用 ${enabledModes.size} 個功能模式，畫面將同時呈現多顆懸浮球"
        } else {
            "已關閉多球：單顆懸浮球模式，長按懸浮球可叫出垂直膠囊選單切換功能"
        }
        bubbleModeConfigAdapter?.notifyDataSetChanged()
    }

    private fun updatePasteModeDesc(isPaste: Boolean) {
        tvPasteModeDesc.text = if (isPaste) {
            "辨識完成後自動將文字直接填入目前焦點所在的文字框"
        } else {
            "辨識完成後不直接填入，僅將文字複製至系統剪貼簿供您隨時手動貼上"
        }
    }

    private fun updateHistoryUI(enabled: Boolean) {
        layoutHistoryLimit.visibility = if (enabled) View.VISIBLE else View.GONE
        dividerHistoryLimit.visibility = if (enabled) View.VISIBLE else View.GONE
        sbHistoryLimit.isEnabled = enabled

        val limit = ModelConfig.getHistoryLimit(this)
        sbHistoryLimit.progress = (limit - 1).coerceIn(0, 11)
        tvHistoryLimitValue.text = "${limit} 筆"

        tvHistoryDesc.text = if (enabled) {
            "在快速常用語抽屜上方即時顯示近期的轉譯歷史紀錄"
        } else {
            "已關閉歷史紀錄，常用語抽屜將僅顯示固定常用語"
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
            .setTitle("如何解除 Android 13+ 受限制的設定")
            .setMessage(
                "在 Android 13 及以上版本中，自外部安裝的應用程式，系統會預設限制「無障礙服務」與「懸浮窗權限」的開啟。\n\n" +
                        "請依照以下步驟解除限制：\n" +
                        "1. 點擊下方「前往應用程式資訊」\n" +
                        "2. 在應用程式詳細資訊頁面中，點擊右上角的「︙」（三點選單）\n" +
                        "3. 點選「解除受限制的設定」\n" +
                        "4. 通過指紋或鎖定密碼驗證後返回本頁，即可順利開啟權限！"
            )
            .setPositiveButton("前往應用程式資訊") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("知道了", null)
            .show()
    }
}
