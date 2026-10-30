package com.ping.verbead.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

class AudioRecorder(private val context: Context? = null) {

    companion object {
        private const val TAG          = "AudioRecorder"
        private const val SAMPLE_RATE  = ModelConfig.ASR_SAMPLE_RATE
        private const val CHANNEL_CFG  = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_FRAMES = 1024

        // 外部/藍牙 SCO 穩定前 250ms 消除連線暫態爆震，內建麥克風前 50ms 消除手勢觸控震動
        private const val WARMUP_EXTERNAL_SECONDS = 0.25f
        private const val WARMUP_INTERNAL_SECONDS = 0.05f
        private const val RAMP_SECONDS            = 0.05f
    }

    private fun processWarmup(
        buf: ShortArray,
        read: Int,
        startFrame: Int,
        warmupFrames: Int,
        rampFrames: Int
    ) {
        if (warmupFrames <= 0) return
        for (i in 0 until read) {
            val f = startFrame + i
            if (f < warmupFrames) {
                buf[i] = 0
            } else if (f < warmupFrames + rampFrames) {
                val factor = (f - warmupFrames).toFloat() / rampFrames
                buf[i] = (buf[i] * factor).toInt().toShort()
            }
        }
    }

    // RECORD_AUDIO 權限由呼叫端於錄音前檢查
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(
        bufSize: Int,
        preferredSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION
    ): AudioRecord {
        val sourcesToTry = when (preferredSource) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
            else -> listOf(
                preferredSource,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        }

        for (source in sourcesToTry) {
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT, bufSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    val sourceName = when (source) {
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION (Bluetooth/mSBC optimized)"
                        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                        MediaRecorder.AudioSource.MIC -> "MIC"
                        else -> "Source $source"
                    }
                    Log.i(TAG, "AudioRecord initialized with $sourceName at ${SAMPLE_RATE}Hz")
                    return record
                }
                record.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to initialize AudioRecord with source $source: ${e.message}")
            }
        }
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT, bufSize
        )
    }

    private fun applyGain(buf: ShortArray, len: Int, gain: Float) {
        if (gain == 1.0f) return
        for (i in 0 until len) {
            val v = (buf[i] * gain).toInt()
            buf[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    enum class StopReason { SILENCE, INITIAL_TIMEOUT, TIMEOUT, MANUAL, ERROR }

    data class Recording(
        val samples: FloatArray,
        val durationSeconds: Float,
        val stopReason: StopReason,
    )

    @Volatile private var shouldStop = false

    @SuppressLint("MissingPermission")
    suspend fun recordUntilSilence(
        maxSeconds:            Float = ModelConfig.MAX_RECORD_SECONDS,
        silenceSeconds:        Float = ModelConfig.VAD_SILENCE_SECONDS,
        minSeconds:            Float = ModelConfig.MIN_RECORD_SECONDS,
        silenceThreshold:      Float = ModelConfig.VAD_SILENCE_THRESHOLD,
        initialTimeoutSeconds: Float = ModelConfig.VAD_INITIAL_TIMEOUT_SECONDS,
        speechThreshold:       Float = ModelConfig.VAD_SPEECH_THRESHOLD,
        onRmsUpdate: ((Float) -> Unit)? = null,
    ): Recording = withContext(Dispatchers.IO) {

        shouldStop = false

        // 1. 先準備音訊路由（設定 MODE_IN_COMMUNICATION 並等待 CommunicationDevice / Bluetooth SCO 連線穩定）
        val routingManager = context?.let { AudioRoutingManager.getInstance(it) }
        val routingSession = routingManager?.prepareForRecording()
        val isExternal = routingSession?.isExternal == true
        val externalGain = context?.let { ModelConfig.externalAudioGain(it) } ?: ModelConfig.DEFAULT_EXTERNAL_AUDIO_GAIN
        val preferredSource = routingSession?.audioSource ?: MediaRecorder.AudioSource.VOICE_RECOGNITION

        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT),
            CHUNK_FRAMES * 2
        )

        // 2. 依路由最佳化音源（藍牙 SCO/mSBC 使用 VOICE_COMMUNICATION，內建麥克風使用 VOICE_RECOGNITION）
        val recorder = try {
            createAudioRecord(bufSize, preferredSource)
        } catch (e: Throwable) {
            Log.e(TAG, "AudioRecord init failed: ${e.message}", e)
            routingManager?.releaseAfterRecording()
            return@withContext Recording(FloatArray(0), 0f, StopReason.ERROR)
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            recorder.release()
            routingManager?.releaseAfterRecording()
            return@withContext Recording(FloatArray(0), 0f, StopReason.ERROR)
        }

        // 3. 綁定輸入設備至 AudioRecord (確保傳入 isSource == true 的輸入裝置)
        routingManager?.applyToAudioRecord(recorder, routingSession?.inputDevice)

        // 監聽並記錄動態路由變化
        val mainHandler = Handler(Looper.getMainLooper())
        var routingListener: AudioRouting.OnRoutingChangedListener? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            routingListener = AudioRouting.OnRoutingChangedListener { router ->
                val rec = router as? AudioRecord ?: return@OnRoutingChangedListener
                routingManager?.logActiveRoute(rec, TAG)
            }
            recorder.addOnRoutingChangedListener(routingListener, mainHandler)
        }

        val maxFrames             = (maxSeconds * SAMPLE_RATE).toInt()
        val minFrames             = (minSeconds * SAMPLE_RATE).toInt()
        val trailingSilenceFrames = (silenceSeconds * SAMPLE_RATE).toInt()
        val initialTimeoutFrames  = (initialTimeoutSeconds * SAMPLE_RATE).toInt()
        val minSpeechFrames       = (0.10f * SAMPLE_RATE).toInt() // ~100ms 語音確認開口
        val warmupFrames          = if (isExternal) (WARMUP_EXTERNAL_SECONDS * SAMPLE_RATE).toInt() else (WARMUP_INTERNAL_SECONDS * SAMPLE_RATE).toInt()
        val rampFrames            = (RAMP_SECONDS * SAMPLE_RATE).toInt()
        val startupIgnoreFrames   = warmupFrames + rampFrames

        val allSamples  = ShortArray(maxFrames)
        var sampleCount = 0
        val chunkBuffer = ShortArray(CHUNK_FRAMES)
        var stopReason   = StopReason.TIMEOUT

        var hasSpoken = false
        var consecutiveSpeechFrames = 0
        var trailingSilenceCount = 0
        var initialSilenceCount = 0
        var routeLogged = false

        try {
            recorder.startRecording()
            while (coroutineContext.isActive && !shouldStop) {
                val read = recorder.read(chunkBuffer, 0, CHUNK_FRAMES)
                if (read <= 0) continue

                // 首次讀取到樣本後記錄確認硬體實際錄音路由與 mSBC 16kHz 狀態
                if (!routeLogged) {
                    routeLogged = true
                    routingManager?.logActiveRoute(recorder, TAG)
                }

                // 消除連線暫態爆震與平滑淡入
                processWarmup(chunkBuffer, read, sampleCount, warmupFrames, rampFrames)

                if (isExternal) {
                    applyGain(chunkBuffer, read, externalGain)
                }

                val copy = minOf(read, maxFrames - sampleCount)
                System.arraycopy(chunkBuffer, 0, allSamples, sampleCount, copy)
                sampleCount += copy

                val rms = computeRms(chunkBuffer, read)
                onRmsUpdate?.invoke(rms)

                if (!hasSpoken) {
                    // 階段一：等待使用者開口說話（給予使用者充足的起話機會，不以停頓短秒數誤殺）
                    if (sampleCount > startupIgnoreFrames) {
                        if (rms >= speechThreshold) {
                            consecutiveSpeechFrames += read
                            if (consecutiveSpeechFrames >= minSpeechFrames) {
                                hasSpoken = true
                                trailingSilenceCount = 0
                            }
                        } else {
                            consecutiveSpeechFrames = maxOf(0, consecutiveSpeechFrames - read / 2)
                            initialSilenceCount += read
                            if (initialSilenceCount >= initialTimeoutFrames) {
                                stopReason = StopReason.INITIAL_TIMEOUT
                                break
                            }
                        }
                    }
                } else {
                    // 階段二：使用者已開口，正式啟用斷句停頓偵測 (VAD)
                    if (rms < silenceThreshold) {
                        trailingSilenceCount += read
                    } else {
                        trailingSilenceCount = 0
                    }

                    if (sampleCount >= minFrames && trailingSilenceCount >= trailingSilenceFrames) {
                        stopReason = StopReason.SILENCE
                        break
                    }
                }

                if (sampleCount >= maxFrames) {
                    stopReason = StopReason.TIMEOUT
                    break
                }
            }
            if (shouldStop) stopReason = StopReason.MANUAL
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && routingListener != null) {
                try {
                    recorder.removeOnRoutingChangedListener(routingListener)
                } catch (e: Exception) {
                    Log.w(TAG, "removeOnRoutingChangedListener failed: ${e.message}")
                }
            }
            try {
                recorder.stop()
            } catch (e: Exception) {
                Log.w(TAG, "recorder.stop() failed", e)
            }
            recorder.release()
            routingManager?.releaseAfterRecording()
        }

        val finalSamples = if (stopReason == StopReason.INITIAL_TIMEOUT) {
            FloatArray(0)
        } else {
            convertToFloat(allSamples, sampleCount)
        }

        Recording(finalSamples, sampleCount.toFloat() / SAMPLE_RATE, stopReason)
    }

    fun stopEarly() { shouldStop = true }

    @SuppressLint("MissingPermission")
    suspend fun recordStreaming(
        silenceThreshold:      Float = ModelConfig.VAD_SILENCE_THRESHOLD,
        silenceSeconds:        Float = 0f,
        minSeconds:            Float = ModelConfig.MIN_RECORD_SECONDS,
        maxSeconds:            Float = ModelConfig.MAX_RECORD_SECONDS,
        initialTimeoutSeconds: Float = ModelConfig.VAD_INITIAL_TIMEOUT_SECONDS,
        speechThreshold:       Float = ModelConfig.VAD_SPEECH_THRESHOLD,
        onChunk:          (FloatArray) -> Unit,
        onRmsUpdate:      ((Float) -> Unit)? = null,
    ): StopReason = withContext(Dispatchers.IO) {

        shouldStop = false

        // 1. 先準備音訊路由（設定 MODE_IN_COMMUNICATION 並等待 CommunicationDevice / Bluetooth SCO 連線穩定）
        val routingManager = context?.let { AudioRoutingManager.getInstance(it) }
        val routingSession = routingManager?.prepareForRecording()
        val isExternal = routingSession?.isExternal == true
        val externalGain = context?.let { ModelConfig.externalAudioGain(it) } ?: ModelConfig.DEFAULT_EXTERNAL_AUDIO_GAIN
        val preferredSource = routingSession?.audioSource ?: MediaRecorder.AudioSource.VOICE_RECOGNITION

        val bufSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT),
            CHUNK_FRAMES * 2
        )

        // 2. 依路由最佳化音源（藍牙 SCO/mSBC 使用 VOICE_COMMUNICATION，內建麥克風使用 VOICE_RECOGNITION）
        val recorder = try {
            createAudioRecord(bufSize, preferredSource)
        } catch (e: Throwable) {
            Log.e(TAG, "AudioRecord init failed: ${e.message}", e)
            routingManager?.releaseAfterRecording()
            return@withContext StopReason.ERROR
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            recorder.release()
            routingManager?.releaseAfterRecording()
            return@withContext StopReason.ERROR
        }

        // 3. 綁定輸入設備至 AudioRecord (確保傳入 isSource == true 的輸入裝置)
        routingManager?.applyToAudioRecord(recorder, routingSession?.inputDevice)

        // 監聽並記錄動態路由變化
        val mainHandler = Handler(Looper.getMainLooper())
        var routingListener: AudioRouting.OnRoutingChangedListener? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            routingListener = AudioRouting.OnRoutingChangedListener { router ->
                val rec = router as? AudioRecord ?: return@OnRoutingChangedListener
                routingManager?.logActiveRoute(rec, TAG)
            }
            recorder.addOnRoutingChangedListener(routingListener, mainHandler)
        }

        val maxFrames             = (maxSeconds * SAMPLE_RATE).toInt()
        val minFrames             = (minSeconds * SAMPLE_RATE).toInt()
        val trailingSilenceFrames = (silenceSeconds * SAMPLE_RATE).toInt()
        val initialTimeoutFrames  = (initialTimeoutSeconds * SAMPLE_RATE).toInt()
        val minSpeechFrames       = (0.10f * SAMPLE_RATE).toInt()
        val warmupFrames          = if (isExternal) (WARMUP_EXTERNAL_SECONDS * SAMPLE_RATE).toInt() else (WARMUP_INTERNAL_SECONDS * SAMPLE_RATE).toInt()
        val rampFrames            = (RAMP_SECONDS * SAMPLE_RATE).toInt()
        val startupIgnoreFrames   = warmupFrames + rampFrames

        var totalFrames   = 0
        val chunkBuffer   = ShortArray(CHUNK_FRAMES)
        var stopReason    = StopReason.TIMEOUT

        var hasSpoken = false
        var consecutiveSpeechFrames = 0
        var trailingSilenceCount = 0
        var initialSilenceCount = 0
        var routeLogged = false

        try {
            recorder.startRecording()
            while (coroutineContext.isActive && !shouldStop) {
                val read = recorder.read(chunkBuffer, 0, CHUNK_FRAMES)
                if (read <= 0) continue

                // 首次讀取到樣本後記錄確認硬體實際錄音路由與 mSBC 16kHz 狀態
                if (!routeLogged) {
                    routeLogged = true
                    routingManager?.logActiveRoute(recorder, TAG)
                }

                // 消除連線暫態爆震與平滑淡入
                processWarmup(chunkBuffer, read, totalFrames, warmupFrames, rampFrames)

                if (isExternal) {
                    applyGain(chunkBuffer, read, externalGain)
                }

                val floats = FloatArray(read) { chunkBuffer[it] / 32768.0f }
                onChunk(floats)
                totalFrames += read

                val rms = computeRms(chunkBuffer, read)
                onRmsUpdate?.invoke(rms)

                if (silenceSeconds > 0f) {
                    if (!hasSpoken) {
                        // 階段一：等待使用者開口說話（給予使用者充足的起話機會）
                        if (totalFrames > startupIgnoreFrames) {
                            if (rms >= speechThreshold) {
                                consecutiveSpeechFrames += read
                                if (consecutiveSpeechFrames >= minSpeechFrames) {
                                    hasSpoken = true
                                    trailingSilenceCount = 0
                                }
                            } else {
                                consecutiveSpeechFrames = maxOf(0, consecutiveSpeechFrames - read / 2)
                                initialSilenceCount += read
                                if (initialSilenceCount >= initialTimeoutFrames) {
                                    stopReason = StopReason.INITIAL_TIMEOUT
                                    break
                                }
                            }
                        }
                    } else {
                        // 階段二：使用者已開口，正式啟用斷句停頓偵測 (VAD)
                        if (rms < silenceThreshold) {
                            trailingSilenceCount += read
                        } else {
                            trailingSilenceCount = 0
                        }

                        if (totalFrames >= minFrames && trailingSilenceCount >= trailingSilenceFrames) {
                            stopReason = StopReason.SILENCE
                            break
                        }
                    }
                }

                if (totalFrames >= maxFrames) { stopReason = StopReason.TIMEOUT; break }
            }
            if (shouldStop) stopReason = StopReason.MANUAL
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && routingListener != null) {
                try {
                    recorder.removeOnRoutingChangedListener(routingListener)
                } catch (e: Exception) {
                    Log.w(TAG, "removeOnRoutingChangedListener failed: ${e.message}")
                }
            }
            try {
                recorder.stop()
            } catch (e: Exception) {
                Log.w(TAG, "recorder.stop() failed in streaming", e)
            }
            recorder.release()
            routingManager?.releaseAfterRecording()
        }

        stopReason
    }

    private fun computeRms(buf: ShortArray, len: Int): Float {
        if (len <= 0) return 0f
        var meanSum = 0.0
        for (i in 0 until len) meanSum += buf[i]
        val mean = meanSum / len
        var sumSquares = 0.0
        for (i in 0 until len) {
            val diff = (buf[i] - mean) / 32768.0
            sumSquares += diff * diff
        }
        return sqrt(sumSquares / len).toFloat()
    }

    private fun convertToFloat(shorts: ShortArray, count: Int) = FloatArray(count) { shorts[it] / 32768.0f }
}
