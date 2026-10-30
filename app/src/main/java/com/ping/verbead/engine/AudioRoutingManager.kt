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
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Manages audio recording input routing.
 * Prioritizes external headsets (Bluetooth SCO / BLE headset > Wired headset) over built-in mic.
 * Handles dynamic plug/unplug, Bluetooth SCO mode activation/deactivation, and AudioRecord preferred device binding.
 * Optimizes Android 12+ CommunicationDevice routing to ensure headsets operate at 16kHz mSBC (Wideband Speech)
 * or BLE Audio LC3.
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

    /**
     * Encapsulates the resolved audio routing state for a recording session.
     */
    data class RoutingSession(
        val isExternal: Boolean,
        val isBluetooth: Boolean,
        val communicationDevice: AudioDeviceInfo?,
        val inputDevice: AudioDeviceInfo?,
        val audioSource: Int,
        val isWidebandSpeechSupported: Boolean
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isScoActive = false
    @Volatile
    private var isScoConnected = false
    private var isListening = false

    private var commDeviceListener: Any? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.i(TAG, "Audio devices added: ${addedDevices?.map { "${it.productName}(type=${it.type})" }}")
            logCurrentPreferredInput()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.i(TAG, "Audio devices removed: ${removedDevices?.map { "${it.productName}(type=${it.type})" }}")
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
                    isScoConnected = (scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED)
                    Log.i(TAG, "Bluetooth SCO state updated: $scoState (connected=$isScoConnected)")
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                    Log.i(TAG, "OnCommunicationDeviceChanged: ${device?.productName} (type=${device?.type})")
                }
                audioManager.addOnCommunicationDeviceChangedListener(
                    ContextCompat.getMainExecutor(context),
                    listener
                )
                commDeviceListener = listener
            }

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && commDeviceListener != null) {
                @Suppress("UNCHECKED_CAST")
                audioManager.removeOnCommunicationDeviceChangedListener(
                    commDeviceListener as AudioManager.OnCommunicationDeviceChangedListener
                )
                commDeviceListener = null
            }
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
     * 判斷指定音訊裝置是否屬於藍牙耳機（Classic SCO 或 BLE Audio）。
     */
    fun isBluetoothDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
               (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    /**
     * 檢查目標裝置是否支援 16kHz 寬頻語音（Wideband Speech / mSBC / LC3）。
     * 若 sampleRates 為空或包含 16000，表示支援或不限制。
     */
    fun isWidebandSpeechSupported(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        val rates = device.sampleRates
        return rates.isEmpty() || rates.contains(ModelConfig.ASR_SAMPLE_RATE)
    }

    /**
     * Determines the best available recording device based on priority:
     * 1. Bluetooth SCO or BLE Headset
     * 2. Wired / USB Headset with microphone
     * 3. Built-in microphone
     */
    fun getPreferredInputDevice(): AudioDeviceInfo? {
        try {
            if (!ModelConfig.isPreferExternalAudio(context)) {
                val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                return inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    ?: inputDevices.firstOrNull()
            }

            // 1. Bluetooth headset (SCO or BLE) from input devices
            if (hasBluetoothPermission()) {
                val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val bluetoothDevice = inputDevices.firstOrNull { device ->
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
                if (bluetoothDevice != null) return bluetoothDevice

                // Check API 31+ availableCommunicationDevices for Bluetooth
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val commBluetooth = audioManager.availableCommunicationDevices.firstOrNull { device ->
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    if (commBluetooth != null) {
                        return inputDevices.firstOrNull { it.type == commBluetooth.type && it.isSource }
                            ?: commBluetooth
                    }
                }
            }

            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            // 2. Wired or USB Headset
            val wiredDevice = inputDevices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && device.type == AudioDeviceInfo.TYPE_USB_DEVICE)
            }
            if (wiredDevice != null) return wiredDevice

            // 3. Fallback: Built-in mic
            return inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: inputDevices.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting preferred input device: ${e.message}")
            return null
        }
    }

    /**
     * Prepares audio routing before AudioRecord creation.
     * Sets AudioManager.MODE_IN_COMMUNICATION, activates Android 12+ CommunicationDevice
     * or pre-Android 12 Bluetooth SCO, waits for route settling, and returns complete RoutingSession info.
     *
     * Ensures Bluetooth headsets route via VOICE_COMMUNICATION to establish 16kHz mSBC (Wideband Speech)
     * at the hardware HAL level rather than falling back to 8kHz CVSD or built-in microphone.
     */
    @SuppressLint("MissingPermission")
    suspend fun prepareForRecording(preferredDevice: AudioDeviceInfo? = null): RoutingSession {
        if (!ModelConfig.isPreferExternalAudio(context)) {
            val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val builtinMic = inputDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                ?: inputDevices.firstOrNull()
            return RoutingSession(
                isExternal = false,
                isBluetooth = false,
                communicationDevice = null,
                inputDevice = builtinMic,
                audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                isWidebandSpeechSupported = true
            )
        }

        val hasBtPerm = hasBluetoothPermission()
        var commDevice: AudioDeviceInfo? = null
        var inputDevice: AudioDeviceInfo? = preferredDevice
        var isBluetooth = false

        // 1. Identify Bluetooth Communication Device (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBtPerm) {
            try {
                val availableComm = audioManager.availableCommunicationDevices
                commDevice = availableComm.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (commDevice != null) {
                    isBluetooth = true
                    val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    inputDevice = inputs.firstOrNull {
                        it.type == commDevice.type && it.isSource && (it.address.isEmpty() || it.address == commDevice.address)
                    } ?: inputs.firstOrNull { it.type == commDevice.type && it.isSource } ?: commDevice
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query availableCommunicationDevices: ${e.message}")
            }
        }

        // 2. Identify Bluetooth Input Device from GET_DEVICES_INPUTS (Pre-API 31 or if commDevice was not found)
        if (!isBluetooth && hasBtPerm) {
            try {
                val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val btInput = inputs.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
                }
                if (btInput != null) {
                    isBluetooth = true
                    inputDevice = btInput
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        commDevice = audioManager.availableCommunicationDevices.firstOrNull {
                            it.type == btInput.type
                        } ?: btInput
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query Bluetooth input devices: ${e.message}")
            }
        }

        // 3. Identify Wired / USB Headsets
        if (!isBluetooth) {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val wiredDevice = inputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.type == AudioDeviceInfo.TYPE_USB_DEVICE)
            }
            if (wiredDevice != null) {
                return RoutingSession(
                    isExternal = true,
                    isBluetooth = false,
                    communicationDevice = null,
                    inputDevice = wiredDevice,
                    audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    isWidebandSpeechSupported = true
                )
            }
        }

        // 4. Activate Communication Routing for Bluetooth Headset
        if (isBluetooth) {
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                delay(30L) // Small pause between mode switch and device routing to avoid HAL race condition

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && commDevice != null) {
                    val success = audioManager.setCommunicationDevice(commDevice)
                    isScoActive = success
                    Log.i(TAG, "Audio routing: setCommunicationDevice to ${commDevice.productName} (type=${commDevice.type}, success=$success)")

                    // Wait for communicationDevice to take effect (up to 250ms)
                    val deadline = System.currentTimeMillis() + 250L
                    while (System.currentTimeMillis() < deadline) {
                        if (audioManager.communicationDevice?.type == commDevice.type) {
                            Log.i(TAG, "Communication device active: ${audioManager.communicationDevice?.productName}")
                            break
                        }
                        delay(25L)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    if (audioManager.isBluetoothScoAvailableOffCall) {
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        isScoActive = true
                        Log.i(TAG, "Audio routing: started Bluetooth SCO (pre-Android 12)")

                        val deadline = System.currentTimeMillis() + 400L
                        while (System.currentTimeMillis() < deadline && !isScoConnected) {
                            delay(30L)
                        }
                    } else {
                        Log.w(TAG, "Bluetooth SCO off-call is not available on this device")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prepare Bluetooth audio routing: ${e.message}", e)
            }

            val rates = inputDevice?.sampleRates ?: IntArray(0)
            val wbsSupported = rates.isEmpty() || rates.contains(ModelConfig.ASR_SAMPLE_RATE)
            Log.i(TAG, "Bluetooth headset routing prepared: inputDevice=${inputDevice?.productName}, wbs16kHz=$wbsSupported, rates=${rates.joinToString()}")

            return RoutingSession(
                isExternal = true,
                isBluetooth = true,
                communicationDevice = commDevice,
                inputDevice = inputDevice,
                // Using VOICE_COMMUNICATION ensures Android Audio HAL routes to the 16kHz mSBC communication path
                audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                isWidebandSpeechSupported = wbsSupported
            )
        }

        // 5. Fallback: Built-in mic
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val builtinMic = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: inputs.firstOrNull()
        return RoutingSession(
            isExternal = false,
            isBluetooth = false,
            communicationDevice = null,
            inputDevice = builtinMic,
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            isWidebandSpeechSupported = true
        )
    }

    /**
     * Applies the resolved input device to the initialized AudioRecord instance.
     * Ensures the device has isSource == true before calling setPreferredDevice.
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
                if (preferredDevice.isSource) {
                    val success = recorder.setPreferredDevice(preferredDevice)
                    Log.i(TAG, "AudioRecord setPreferredDevice: ${preferredDevice.productName} (type=${preferredDevice.type}, success=$success)")
                } else {
                    val matchingInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .firstOrNull { it.type == preferredDevice.type && it.isSource }
                    if (matchingInput != null) {
                        val success = recorder.setPreferredDevice(matchingInput)
                        Log.i(TAG, "AudioRecord setPreferredDevice (matched input): ${matchingInput.productName} (type=${matchingInput.type}, success=$success)")
                    } else {
                        Log.w(TAG, "Cannot setPreferredDevice: ${preferredDevice.productName} is not an input source")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to setPreferredDevice on AudioRecord: ${e.message}")
            }
        } else {
            Log.d(TAG, "AudioRecord: maintaining system default routing")
        }
    }

    /**
     * Verifies and logs the active routed device and sample rate (16kHz mSBC confirmation).
     */
    fun logActiveRoute(recorder: AudioRecord, tag: String = TAG) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val routed = recorder.routedDevice
            if (routed != null) {
                val isBtSco = routed.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                val isBle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && routed.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                val rates = routed.sampleRates
                val has16k = rates.isEmpty() || rates.contains(ModelConfig.ASR_SAMPLE_RATE)
                val codecInfo = when {
                    isBtSco && has16k -> " -> mSBC 16kHz Wideband Speech verified"
                    isBtSco && !has16k -> " -> CVSD 8kHz narrowband speech, AudioFlinger resampled to 16kHz"
                    isBle -> " -> BLE Audio LC3 16kHz+ verified"
                    routed.type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> " -> Wired Headset"
                    routed.type == AudioDeviceInfo.TYPE_USB_HEADSET -> " -> USB Headset"
                    routed.type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> " -> Built-in Mic"
                    else -> ""
                }
                Log.i(tag, "AudioRecord active route: ${routed.productName} [type=${routed.type}, sampleRate=${recorder.sampleRate}Hz]$codecInfo")
            }
        }
    }

    /**
     * Releases audio routing changes after recording ends.
     */
    @SuppressLint("MissingPermission")
    fun releaseAfterRecording() {
        try {
            if (isScoActive) {
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release Bluetooth SCO: ${e.message}")
        } finally {
            isScoActive = false
            try {
                if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_NORMAL
                    Log.i(TAG, "Audio mode reset to MODE_NORMAL")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reset audio mode to normal: ${e.message}")
            }
        }
    }

    private fun logCurrentPreferredInput() {
        val preferred = getPreferredInputDevice()
        Log.i(TAG, "Current preferred audio input: ${preferred?.productName ?: "Built-in"} (type=${preferred?.type ?: -1})")
    }
}
