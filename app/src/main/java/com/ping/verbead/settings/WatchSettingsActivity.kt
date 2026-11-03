package com.ping.verbead.settings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.ping.verbead.R
import com.ping.verbead.VoiceAccessibilityService
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.util.HapticUtil
import com.ping.verbead.watch.LocalWebSocketServer
import com.ping.verbead.watch.WatchBridgePrefs
import com.ping.verbead.watch.WatchBridgeService

/** 「手錶輸入」設定：啟用橋接、配對 Pebble 手錶、自動結束。 */
class WatchSettingsActivity : AppCompatActivity() {

    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var switchAutoStop: MaterialSwitch
    private lateinit var tvStatus: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_watch)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }

        switchEnabled = findViewById(R.id.switch_watch_enabled)
        switchAutoStop = findViewById(R.id.switch_watch_autostop)
        tvStatus = findViewById(R.id.tv_watch_status)

        switchEnabled.isChecked = WatchBridgePrefs.isEnabled(this)
        switchAutoStop.isChecked = WatchBridgePrefs.isAutoStop(this)

        switchEnabled.setOnCheckedChangeListener { _, checked ->
            HapticUtil.click(this)
            WatchBridgePrefs.setEnabled(this, checked)
            if (checked) WatchBridgeService.start(this) else WatchBridgeService.stop(this)
            handler.postDelayed({ updateStatus() }, 400)
        }
        switchAutoStop.setOnCheckedChangeListener { _, checked ->
            HapticUtil.tick(this)
            WatchBridgePrefs.setAutoStop(this, checked)
        }

        findViewById<android.view.View>(R.id.btn_watch_pair).setOnClickListener {
            HapticUtil.click(this)
            if (!WatchBridgeService.isRunning) {
                switchEnabled.isChecked = true // 觸發啟動服務
            }
            WatchBridgePrefs.openPairing(this)
            Toast.makeText(this, R.string.watch_pair_opened, Toast.LENGTH_LONG).show()
        }

        findViewById<android.view.View>(R.id.btn_watch_reset_token).setOnClickListener {
            HapticUtil.click(this)
            AlertDialog.Builder(this)
                .setTitle(R.string.watch_reset_confirm_title)
                .setMessage(R.string.watch_reset_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.watch_reset_confirm_ok) { _, _ ->
                    WatchBridgePrefs.regenerateToken(this)
                    WatchBridgePrefs.closePairing(this)
                    Toast.makeText(this, R.string.watch_reset_done, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun updateStatus() {
        val lines = mutableListOf<String>()
        lines += when {
            !WatchBridgePrefs.isEnabled(this) -> getString(R.string.watch_status_off)
            !WatchBridgeService.isRunning -> getString(R.string.watch_status_starting)
            !WatchBridgeService.isListening ->
                getString(R.string.watch_status_port_busy, LocalWebSocketServer.DEFAULT_PORT)
            else -> getString(R.string.watch_status_listening, LocalWebSocketServer.DEFAULT_PORT)
        }
        if (WatchBridgePrefs.isPairingOpen(this)) lines += getString(R.string.watch_status_pairing_open)
        if (!VoiceAccessibilityService.isServiceRunning()) lines += getString(R.string.watch_status_no_accessibility)
        if (!ModelConfig.isXAsrReady(this) && !ModelConfig.isQwen3Ready(this)) {
            lines += getString(R.string.watch_status_no_model)
        }
        tvStatus.text = lines.joinToString("\n")
    }
}
