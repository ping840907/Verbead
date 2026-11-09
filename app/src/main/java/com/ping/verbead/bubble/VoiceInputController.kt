package com.ping.verbead.bubble

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OnlineStream
import com.ping.verbead.R
import com.ping.verbead.UserDictionary
import com.ping.verbead.engine.AudioRecorder
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.Qwen3AsrEngine
import com.ping.verbead.engine.XAsrEngine
import com.ping.verbead.engine.lang.ScriptMode
import com.ping.verbead.engine.lang.ZhTwProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ping.verbead.bubble.BubbleState as State

/**
 * 語音輸入流程：錄音、串流／離線辨識、雙引擎、辨識後處理。
 * 從 FloatingBubbleService 拆出；與泡泡 UI 的互動全部透過 [Host]，本類別不碰任何 View。
 */
class VoiceInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val host: Host
) {
    /** 泡泡端需要提供的 UI／狀態操作 */
    interface Host {
        fun setState(state: State)
        fun showXButton()
        fun hideXButton()
        fun showPreview(msg: String, autoHide: Boolean)
        fun hidePreview()
        fun hidePulse()
        fun onRms(rms: Float)
        /** 辨識（與後處理）完成，交給泡泡填入 */
        fun onTranscribed(text: String)
        fun untuckAnchor()
    }

    private val recorder = AudioRecorder(context)
    private val qwen3Asr by lazy { Qwen3AsrEngine(context) }
    private val xAsr by lazy { XAsrEngine(context) }

    private var activeStream: OnlineStream? = null
    private var recordingJob: Job? = null
    var isRecording = false
        private set
    private var isAborted = false
    private var lastStreamingText = ""

    /** 使用者主動結束錄音（點泡泡）：讓錄音自然收尾並進入辨識 */
    fun stopEarly() = recorder.stopEarly()

    /** ✕ 鍵於錄音中：中止錄音並丟棄音訊 */
    fun abortRecording() {
        isAborted = true
        isRecording = false
        recordingJob?.cancel()
        recorder.stopEarly()
        activeStream?.let { runCatching { it.release() } }
        activeStream = null
    }

    /** ✕ 鍵於辨識中：中斷推論並阻止後續填入 */
    fun abortTranscribing() {
        isAborted = true
        recordingJob?.cancel()
    }

    /** Service 結束時釋放所有資源 */
    fun release() {
        runCatching { recordingJob?.cancel() }
        runCatching { recorder.stopEarly() }
        runCatching { activeStream?.release() }
        activeStream = null
        runCatching { if (qwen3Asr.isLoaded()) qwen3Asr.release() }
        runCatching { if (xAsr.isLoaded()) xAsr.release() }
    }

    private fun isDualEngineActive(): Boolean = ModelConfig.isDualEngineActive(context)

    fun start() {
        isAborted = false
        host.untuckAnchor()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, R.string.toast_mic_permission_voice, Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return
        }

        try {
            if (isDualEngineActive()) {
                if (!xAsr.isLoaded() || !qwen3Asr.isLoaded() || qwen3NeedsReload()) {
                    preloadDualEngineAndStart()
                    return
                }
                startDualEngineRecording()
                return
            }

            val engine = ModelConfig.selectedEngine(context)
            if (engine == ModelConfig.ENGINE_X_ASR) {
                if (!xAsr.isLoaded()) {
                    preloadAndStart(engine)
                    return
                }
                startStreamingRecording()
            } else {
                if (!qwen3Asr.isLoaded() || qwen3NeedsReload()) {
                    preloadAndStart(engine)
                    return
                }
                startOfflineRecording()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording failed: ${e.message}", e)
            host.setState(State.IDLE)
            host.showPreview(context.getString(R.string.preview_voice_start_failed, e.localizedMessage ?: context.getString(R.string.common_unknown_error)), autoHide = true)
        }
    }

    private fun preloadDualEngineAndStart() {
        host.setState(State.LOADING)
        host.showPreview(context.getString(R.string.preview_loading_dual_models), autoHide = false)

        scope.launch {
            val (xOk, qOk) = withContext(Dispatchers.IO) {
                val x = xAsr.load().success
                val q = qwen3Asr.load(buildHotwords()).success
                x to q
            }
            if (xOk && qOk) {
                startDualEngineRecording()
            } else {
                host.setState(State.IDLE)
                host.showPreview(context.getString(R.string.preview_model_load_failed), autoHide = true)
            }
        }
    }

    private fun preloadAndStart(engine: String) {
        host.setState(State.LOADING)
        host.showPreview(context.getString(R.string.preview_loading_models), autoHide = false)

        scope.launch {
            val success = withContext(Dispatchers.IO) {
                if (engine == ModelConfig.ENGINE_X_ASR) {
                    xAsr.load().success
                } else {
                    qwen3Asr.load(buildHotwords()).success
                }
            }
            if (success) {
                if (engine == ModelConfig.ENGINE_X_ASR) {
                    startStreamingRecording()
                } else {
                    startOfflineRecording()
                }
            } else {
                host.setState(State.IDLE)
                host.showPreview(context.getString(R.string.preview_model_load_failed), autoHide = true)
            }
        }
    }

    // 雙引擎模式底層運作機制
    private fun startDualEngineRecording() {
        val stream = xAsr.createStream() ?: run {
            host.showPreview(context.getString(R.string.preview_cannot_create_stream), autoHide = true)
            return
        }
        activeStream = stream
        isRecording = true
        isAborted = false
        host.setState(State.RECORDING)
        host.showXButton()
        host.showPreview(context.getString(R.string.preview_listening), autoHide = false)

        val audioBuffer = mutableListOf<FloatArray>()
        var accumulatedXAsr = ""
        lastStreamingText = ""

        recordingJob = scope.launch {
            val stopReason = withContext(Dispatchers.IO) {
                recorder.recordStreaming(
                    silenceSeconds = ModelConfig.vadSilenceSeconds(context),
                    onChunk = { chunk ->
                        if (isAborted) return@recordStreaming
                        audioBuffer.add(chunk.copyOf())
                        xAsr.acceptWaveform(stream, chunk)
                        while (xAsr.isReady(stream)) {
                            xAsr.decode(stream)
                        }
                        val partial = xAsr.getResult(stream)
                        val combined = accumulatedXAsr + partial
                        if (combined.isNotBlank() && combined != lastStreamingText) {
                            lastStreamingText = combined
                            mainHandler.post {
                                if (isRecording && !isAborted) {
                                    host.showPreview(combined, autoHide = false)
                                }
                            }
                        }
                        if (xAsr.isEndpoint(stream)) {
                            if (partial.isNotBlank()) accumulatedXAsr += partial
                            xAsr.reset(stream)
                        }
                    },
                    onRmsUpdate = { rms -> host.onRms(rms) }
                )
            }

            isRecording = false
            host.hidePulse()
            activeStream = null
            runCatching { xAsr.inputFinished(stream) }
            runCatching {
                while (xAsr.isReady(stream)) {
                    xAsr.decode(stream)
                }
            }
            val finalPartial = xAsr.getResult(stream).trim()
            val totalXAsrText = (accumulatedXAsr + finalPartial).trim()
            lastStreamingText = totalXAsrText
            runCatching { stream.release() }

            if (isAborted) return@launch

            if (stopReason == AudioRecorder.StopReason.INITIAL_TIMEOUT) {
                host.setState(State.IDLE)
                host.hideXButton()
                host.showPreview(context.getString(R.string.preview_no_speech_detected), autoHide = true)
                return@launch
            }

            // 錄音結束，預覽氣泡淡出，維持目標框純淨
            mainHandler.post {
                host.hidePreview()
                host.setState(State.TRANSCRIBING)
            }

            // 合併完整的音訊 buffer
            val totalSamples = audioBuffer.sumOf { it.size }
            if (totalSamples == 0) {
                host.setState(State.IDLE)
                host.hideXButton()
                host.showPreview(context.getString(R.string.preview_no_speech_detected), autoHide = true)
                return@launch
            }
            val combinedAudio = FloatArray(totalSamples)
            var offset = 0
            for (chunk in audioBuffer) {
                System.arraycopy(chunk, 0, combinedAudio, offset, chunk.size)
                offset += chunk.size
            }

            // §2.4 Qwen3 轉譯與降級防護 (Fallback)
            try {
                val raw = withContext(Dispatchers.Default) {
                    if (isAborted) return@withContext ""
                    qwen3Asr.transcribe(combinedAudio)
                }
                if (isAborted) return@launch
                val text = if (raw.isNotBlank()) postProcess(raw) else ""
                host.onTranscribed(text)
            } catch (ex: Throwable) {
                if (ex is kotlinx.coroutines.CancellationException || isAborted) return@launch
                Log.e(TAG, "Qwen3 batch inference failed: ${ex.message}", ex)
                val fallbackText = lastStreamingText
                if (fallbackText.isNotEmpty()) {
                    val processedFallback = postProcess(fallbackText)
                    host.onTranscribed(processedFallback)
                    Toast.makeText(context, R.string.toast_offline_model_error_applied_streaming, Toast.LENGTH_SHORT).show()
                } else {
                    host.setState(State.IDLE)
                    host.hideXButton()
                    host.showPreview(context.getString(R.string.preview_transcribe_failed), autoHide = true)
                    Toast.makeText(context, R.string.toast_transcribe_failed_retry, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startOfflineRecording() {
        isRecording = true
        isAborted = false
        host.setState(State.RECORDING)
        host.showXButton()
        host.showPreview(context.getString(R.string.preview_listening), autoHide = false)

        recordingJob = scope.launch {
            val recording = withContext(Dispatchers.IO) {
                recorder.recordUntilSilence(
                    silenceSeconds = ModelConfig.vadSilenceSeconds(context),
                    onRmsUpdate = { rms -> host.onRms(rms) }
                )
            }
            isRecording = false
            host.hidePulse()
            if (isAborted) return@launch

            if (recording.samples.isNotEmpty()) {
                host.setState(State.TRANSCRIBING)
                host.hidePreview()
                try {
                    val raw = withContext(Dispatchers.Default) {
                        if (isAborted) return@withContext ""
                        qwen3Asr.transcribe(recording.samples)
                    }
                    if (isAborted) return@launch
                    val text = if (raw.isNotBlank()) postProcess(raw) else ""
                    host.onTranscribed(text)
                } catch (ex: Throwable) {
                    if (ex is kotlinx.coroutines.CancellationException || isAborted) return@launch
                    host.setState(State.IDLE)
                    host.hideXButton()
                    host.showPreview(context.getString(R.string.preview_transcribe_failed), autoHide = true)
                }
            } else {
                host.setState(State.IDLE)
                host.hideXButton()
                host.showPreview(context.getString(R.string.preview_no_speech_detected), autoHide = true)
            }
        }
    }

    private fun startStreamingRecording() {
        val engine = xAsr
        val stream = engine.createStream() ?: run {
            host.showPreview(context.getString(R.string.preview_cannot_create_stream), autoHide = true)
            return
        }
        activeStream = stream
        isRecording = true
        isAborted = false
        host.setState(State.RECORDING)
        host.showXButton()
        host.showPreview(context.getString(R.string.preview_listening), autoHide = false)

        var accumulated = ""
        var lastShown = ""

        recordingJob = scope.launch {
            val stopReason = withContext(Dispatchers.IO) {
                recorder.recordStreaming(
                    silenceSeconds = ModelConfig.vadSilenceSeconds(context),
                    onChunk = { chunk ->
                        if (isAborted) return@recordStreaming
                        engine.acceptWaveform(stream, chunk)
                        while (engine.isReady(stream)) {
                            engine.decode(stream)
                        }
                        val partial = engine.getResult(stream)
                        val combined = accumulated + partial
                        if (combined.isNotBlank() && combined != lastShown) {
                            lastShown = combined
                            mainHandler.post {
                                if (isRecording && !isAborted) {
                                    host.showPreview(combined, autoHide = false)
                                }
                            }
                        }
                        if (engine.isEndpoint(stream)) {
                            if (partial.isNotBlank()) accumulated += partial
                            engine.reset(stream)
                        }
                    },
                    onRmsUpdate = { rms -> host.onRms(rms) }
                )
            }

            isRecording = false
            host.hidePulse()
            activeStream = null

            runCatching { engine.inputFinished(stream) }
            runCatching {
                while (engine.isReady(stream)) {
                    engine.decode(stream)
                }
            }
            val finalSegment = engine.getResult(stream).trim()
            runCatching { stream.release() }

            if (isAborted) return@launch

            if (stopReason == AudioRecorder.StopReason.INITIAL_TIMEOUT) {
                host.setState(State.IDLE)
                host.hideXButton()
                host.showPreview(context.getString(R.string.preview_no_speech_detected), autoHide = true)
                return@launch
            }

            val fullText = (accumulated + finalSegment).trim()
            host.onTranscribed(fullText)
        }
    }

    private fun qwen3NeedsReload(): Boolean =
        buildHotwords() != qwen3Asr.loadedHotwords

    private fun buildHotwords(): String =
        if (ModelConfig.isHotwordsEnabled(context)) {
            UserDictionary.load(context).values.distinct().sorted().joinToString("\n")
        } else {
            ""
        }

    /** 辨識後處理：依 [ZhTwProfile] 的規則依序套用。規則以字元證據逐句判斷，日韓文句子不會被動到。 */
    private fun postProcess(raw: String): String {
        val scriptMode = when {
            !ModelConfig.isConvertToTraditionalEnabled(context) -> ScriptMode.OFF
            ModelConfig.selectedEngine(context) == ModelConfig.ENGINE_X_ASR && !isDualEngineActive() -> ScriptMode.VARIANTS_ONLY
            else -> ScriptMode.FULL
        }
        val converted = ZhTwProfile.convertScript(raw, scriptMode)
        val userReplaced = UserDictionary.apply(converted, UserDictionary.load(context))
        val postReplaced = if (userReplaced == converted || scriptMode == ScriptMode.OFF) {
            userReplaced
        } else {
            ZhTwProfile.applyVariantRules(userReplaced)
        }
        // 數字轉換要在標點過濾之前：它依標點切小句，且不能被過濾後的文字影響
        val numbered = if (ModelConfig.isSpokenNumbersEnabled(context)) {
            ZhTwProfile.convertSpokenNumbers(postReplaced)
        } else {
            postReplaced
        }
        return if (ModelConfig.isFilterPunctuationEnabled(context)) {
            ZhTwProfile.filterPunctuation(numbered)
        } else {
            numbered
        }
    }

    companion object {
        private const val TAG = "VoiceInput"
    }
}
