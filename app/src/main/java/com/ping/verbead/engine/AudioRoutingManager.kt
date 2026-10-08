package com.ping.verbead.engine

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

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

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 判斷指定音訊裝置是否屬於外部裝置（藍牙耳機、有線耳機、USB 耳麥等）。
     */
    fun isExternalDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
               device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
               device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
               (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
               (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && device.type == AudioDeviceInfo.TYPE_USB_DEVICE)
    }

    /**
     * Determines the best available recording device based on priority:
     * 1. Bluetooth SCO or BLE Headset
     * 2. Wired / USB Headset with microphone
     * 3. Built-in microphone
     */
    fun getPreferredInputDevice(): AudioDeviceInfo? {
        try {
            // When external audio prioritization is disabled, always prefer the built-in mic
            if (!ModelConfig.isPreferExternalAudio(context)) {
                val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                return inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?: inputDevices.firstOrNull()
            }

            // 1. Check API 31+ availableCommunicationDevices for Bluetooth
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBluetoothPermission()) {
                val commBluetooth = audioManager.availableCommunicationDevices.firstOrNull { device ->
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (commBluetooth != null) return commBluetooth
            }

            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            // 2. Bluetooth headset (SCO or BLE) from input devices
            if (hasBluetoothPermission()) {
                val bluetoothDevice = inputDevices.firstOrNull { device ->
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
                if (bluetoothDevice != null) return bluetoothDevice
            }

            // 3. Wired or USB Headset
            val wiredDevice = inputDevices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && device.type == AudioDeviceInfo.TYPE_USB_DEVICE)
            }
            if (wiredDevice != null) return wiredDevice

            // 4. Fallback: Built-in mic
            return inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: inputDevices.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting preferred input device: ${e.message}")
            return null
        }
    }

    /**
     * Prepares audio routing before recording starts.
     * Prioritizes Bluetooth headset by establishing communication device (Android 12+)
     * or activating Bluetooth SCO mode (Android 11 and lower).
     */
    @SuppressLint("MissingPermission")
    fun prepareForRecording(preferredDevice: AudioDeviceInfo?) {
        if (!ModelConfig.isPreferExternalAudio(context)) return
        if (preferredDevice == null) return
        val isBluetooth = preferredDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && preferredDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

        if (isBluetooth) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (hasBluetoothPermission()) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        val commDevice = audioManager.availableCommunicationDevices.firstOrNull {
                            it.id == preferredDevice.id ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        } ?: preferredDevice
                        val success = audioManager.setCommunicationDevice(commDevice)
                        isScoActive = success
                        Log.i(TAG, "Audio routing: setCommunicationDevice to ${commDevice.productName} (success=$success)")
                    } else {
                        Log.w(TAG, "Audio routing: BLUETOOTH_CONNECT permission not granted, skipping setCommunicationDevice")
                    }
                } else {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                    isScoActive = true
                    Log.i(TAG, "Audio routing: started Bluetooth SCO (pre-Android 12)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prepare Bluetooth audio routing: ${e.message}")
            }
        } else {
            Log.d(TAG, "Audio routing: using non-Bluetooth device (${preferredDevice.productName})")
        }
    }

    /**
     * Applies the preferred device to the initialized AudioRecord instance.
     */
    fun applyToAudioRecord(recorder: AudioRecord, preferredDevice: AudioDeviceInfo?) {
        if (!ModelConfig.isPreferExternalAudio(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val builtIn = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                if (builtIn != null) {
                    try {
                        recorder.setPreferredDevice(builtIn)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set built-in mic on AudioRecord: ${e.message}")
                    }
                }
            }
            return
        }
        if (preferredDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val success = recorder.setPreferredDevice(preferredDevice)
                Log.i(TAG, "AudioRecord setPreferredDevice: ${preferredDevice.productName} (type=${preferredDevice.type}, success=$success)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to setPreferredDevice on AudioRecord: ${e.message}")
            }
        } else {
            Log.d(TAG, "AudioRecord: maintaining system default routing")
        }
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
                    Log.i(TAG, "Released communication device")
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                    Log.i(TAG, "Released Bluetooth SCO")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release Bluetooth SCO: ${e.message}")
            } finally {
                isScoActive = false
                try {
                    audioManager.mode = AudioManager.MODE_NORMAL
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reset audio mode to normal: ${e.message}")
                }
            }
        }
    }

    private fun logCurrentPreferredInput() {
        val preferred = getPreferredInputDevice()
        Log.i(TAG, "Current preferred audio input: ${preferred?.productName ?: "Built-in"} (type=${preferred?.type ?: -1})")
    }
}
