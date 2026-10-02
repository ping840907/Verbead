package com.ping.voiceime.engine

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages audio recording input routing.
 * Prioritizes external headsets (Bluetooth SCO / BLE headset > Wired headset) over built-in mic.
 * Handles dynamic plug/unplug, Bluetooth SCO mode activation/deactivation, and AudioRecord preferred device binding.
 */
class AudioRoutingManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRoutingManager"

        @Volatile
        private var instance: AudioRoutingManager? = null

        fun getInstance(context: Context): AudioRoutingManager =
            instance ?: synchronized(this) {
                instance ?: AudioRoutingManager(context.applicationContext).also { instance = it }
            }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isScoActive = false
    private var isListening = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.i(TAG, "Audio devices added: ${addedDevices?.map { it.type }}")
            logCurrentPreferredInput()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.i(TAG, "Audio devices removed: ${removedDevices?.map { it.type }}")
            logCurrentPreferredInput()
        }
    }

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    val name = intent.getStringExtra("name")
                    val hasMicrophone = intent.getIntExtra("microphone", 0) == 1
                    Log.i(TAG, "Headset plug event: state=$state, name=$name, hasMic=$hasMicrophone")
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                    Log.i(TAG, "Bluetooth SCO state updated: $scoState (connected=${scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED})")
                }
            }
        }
    }

    fun start() = startListening()
    fun stop() = stopListening()

    fun startListening() {
        if (isListening) return
        isListening = true
        try {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            context.registerReceiver(headsetReceiver, filter)
            logCurrentPreferredInput()
        } catch (e: Exception) {
            Log.w(TAG, "Error starting audio device listening: ${e.message}")
        }
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            context.unregisterReceiver(headsetReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio device listening: ${e.message}")
        }
    }

    /**
     * Determines the best available recording device based on priority:
     * 1. Bluetooth SCO or BLE Headset
     * 2. Wired / USB Headset with microphone
     * 3. Built-in microphone
     */
    fun getPreferredInputDevice(): AudioDeviceInfo? {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        // 1. Bluetooth headset (SCO or BLE)
        val bluetoothDevice = inputDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        if (bluetoothDevice != null) return bluetoothDevice

        // 2. Wired or USB Headset
        val wiredDevice = inputDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && device.type == AudioDeviceInfo.TYPE_USB_DEVICE)
        }
        if (wiredDevice != null) return wiredDevice

        // 3. Fallback: Built-in mic
        return inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    /**
     * Prepares audio routing before recording starts.
     * 暫時維持系統預設音訊路由，後續排查 AudioRecord 來源設定、AudioManager 藍牙 SCO 連線管理（startBluetoothSco）與權限生命週期。
     */
    @SuppressLint("MissingPermission")
    fun prepareForRecording(preferredDevice: AudioDeviceInfo?) {
        // 暫時維持系統預設音訊路由，避免 SCO 連線狀態衝突或無聲問題
        Log.d(TAG, "Audio routing: maintaining system default audio routing")
    }

    /**
     * Applies the preferred device to the initialized AudioRecord instance.
     * 暫時維持系統預設音訊路由。
     */
    fun applyToAudioRecord(recorder: AudioRecord, preferredDevice: AudioDeviceInfo?) {
        // 暫時維持系統預設音訊路由，由系統底層策略自動調度已連接之輸入裝置
        Log.d(TAG, "Audio routing: maintaining system default AudioRecord routing (detected: ${preferredDevice?.productName ?: "default"})")
    }

    /**
     * Releases audio routing changes after recording ends.
     */
    @SuppressLint("MissingPermission")
    fun releaseAfterRecording() {
        if (isScoActive) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                } else {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
                Log.i(TAG, "Released Bluetooth SCO")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release Bluetooth SCO: ${e.message}")
            } finally {
                isScoActive = false
            }
        }
    }

    private fun logCurrentPreferredInput() {
        val preferred = getPreferredInputDevice()
        Log.i(TAG, "Current preferred audio input: ${preferred?.productName ?: "Built-in"} (type=${preferred?.type ?: -1})")
    }
}
