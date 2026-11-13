package com.ping.verbead.bubble

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OnlineStream
import com.ping.verbead.R
import com.ping.verbead.UserDictionary
import com.ping.verbead.engine.AudioRecorder
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.AsrMode
import com.ping.verbead.engine.AsrModel
import com.ping.verbead.engine.FinalEngine
import com.ping.verbead.engine.Qwen3AsrEngine
import com.ping.verbead.engine.FinalEngines
import com.ping.verbead.engine.StreamingAsrEngine
import com.ping.verbead.engine.VoicePreloadPolicy
import com.ping.verbead.engine.lang.ScriptMode
import com.ping.verbead.engine.lang.ZhTwProfile
import com.ping.verbead.util.ToastUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
        /** A state of the bubble (listening, loading): the card stays until the state ends */
        fun showStatus(msg: String)

        /** The live recognition text */
        fun showLive(text: String)

        fun hidePreview()
        fun hidePulse()
        fun onRms(rms: Float)
        /** 辨識（與後處理）完成，交給泡泡填入 */
        fun onTranscribed(text: String)
        fun untuckAnchor()
    }

    private val recorder = AudioRecorder(context)

    // ── Background preload (see VoicePreloadPolicy for what keeps it from ever looping) ──
    enum class PreloadReason { USER_CHOSE, BUBBLE_SHOWN }

    private val preloadPolicy = VoicePreloadPolicy(context)
    private var preloadJob: Job? = null
    private var lastShownCheck = 0L
    /** A load the user started by tapping the bubble is running. */
    @Volatile
    private var userLoading = false
    /** The engines of the two slots; rebuilt when the user picks other models (see [syncEngines]). */
    private var liveEngine: StreamingAsrEngine? = null
    private var finalEngine: FinalEngine? = null
    private var finalModel: AsrModel? = null

    private var activeStream: OnlineStream? = null
    private var recordingJob: Job? = null
    @Volatile
    var isRecording = false
        private set
    // Read from the recorder / inference threads and written from the main thread.
    @Volatile
    private var isAborted = false
    @Volatile
    private var lastStreamingText = ""
    /** Bumped whenever a pending model load is abandoned, so a late load result does not start recording. */
    @Volatile
    private var loadGeneration = 0
    /** The final engine is not safe to run twice at once (an aborted inference may still be finishing). */
    private val inferenceLock = kotlinx.coroutines.sync.Mutex()

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

    /** ✕ while the models are still loading: drop the pending start. */
    fun abortLoading() {
        loadGeneration++
        isAborted = true
    }

    /** ✕ 鍵於辨識中：中斷推論並阻止後續填入 */
    fun abortTranscribing() {
        isAborted = true
        recordingJob?.cancel()
    }

    /**
     * Loads the chosen models in the background so that the first tap does not have to wait. Called when the user
     * picked a model (or turned the option on) and, at most once a minute, when the bubble comes into view.
     * It only ever loads engines that are not loaded; a change of hot words is left for the next tap.
     */
    fun requestPreload(reason: PreloadReason) {
        val userChose = reason == PreloadReason.USER_CHOSE
        if (!userChose) {
            val t = android.os.SystemClock.elapsedRealtime()
            if (t - lastShownCheck < SHOWN_CHECK_INTERVAL_MS) return
            lastShownCheck = t
        }
        if (isRecording || userLoading || preloadJob?.isActive == true) return

        val liveModel = ModelConfig.readyAsrModel(context, AsrMode.LIVE)
        val finalModel = ModelConfig.readyAsrModel(context, AsrMode.FINAL)?.takeIf { it.id != liveModel?.id }
        if (liveModel == null && finalModel == null) return
        syncEngines(liveModel, finalModel)
        val live = liveEngine
        val final = finalEngine
        if (live?.isLoaded() != false && final?.isLoaded() != false) return // nothing to load

        val required = (liveModel?.sizeMb ?: 0) + (finalModel?.sizeMb ?: 0)
        val verdict = preloadPolicy.check(required, userChose)
        if (verdict != VoicePreloadPolicy.Verdict.GO) {
            Log.i(TAG, "no background load: $verdict")
            return
        }
        preloadPolicy.markAttemptStarted()
        preloadJob = scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                val previous = android.os.Process.getThreadPriority(android.os.Process.myTid())
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    val liveOk = live?.let { it.isLoaded() || it.load().success } ?: true
                    val finalOk = final?.let { it.isLoaded() || it.load(buildHotwords()).success } ?: true
                    liveOk && finalOk
                } catch (e: Throwable) {
                    Log.w(TAG, "background load failed: ${e.message}")
                    false
                } finally {
                    android.os.Process.setThreadPriority(previous)
                }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                preloadPolicy.markAttemptAbandoned()
                throw e
            }
            if (ok) preloadPolicy.markAttemptSucceeded() else preloadPolicy.markAttemptFailed()
        }
    }

    /**
     * The system wants memory back: let the models go (unless they are in use) and stop loading them on our own,
     * so that the next load is the user's tap and nothing can cycle load and release.
     */
    fun onMemoryPressure() {
        preloadPolicy.markEvicted()
        preloadJob?.cancel()
        if (isRecording || userLoading) return
        if (!inferenceLock.tryLock()) return // a transcription is running: leave the engines alone
        try {
            runCatching { liveEngine?.release() }
            runCatching { finalEngine?.release() }
        } finally {
            inferenceLock.unlock()
        }
    }

    /** Service 結束時釋放所有資源 */
    fun release() {
        runCatching { recordingJob?.cancel() }
        runCatching { recorder.stopEarly() }
        runCatching { activeStream?.release() }
        activeStream = null
        runCatching { finalEngine?.release() }
        runCatching { liveEngine?.release() }
        finalEngine = null
        finalModel = null
        liveEngine = null
    }

    fun start() {
        isAborted = false
        host.untuckAnchor()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ToastUtil.show(context, R.string.toast_mic_permission_voice)
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return
        }

        try {
            val liveModel = ModelConfig.readyAsrModel(context, AsrMode.LIVE)
            // the same model in both slots would decode the audio twice for the same text: use it live only
            val finalModel = ModelConfig.readyAsrModel(context, AsrMode.FINAL)?.takeIf { it.id != liveModel?.id }
            if (liveModel == null && finalModel == null) {
                host.hidePreview() // no stale \"listening\" / \"loading\" pill after a failed or empty run
                host.setState(State.IDLE)
                ToastUtil.show(context, R.string.toast_model_not_downloaded)
                return
            }
            syncEngines(liveModel, finalModel)
            val needsLoad = (liveEngine?.isLoaded() == false) || finalNeedsLoad()
            if (needsLoad) {
                preloadAndStart(dual = liveModel != null && finalModel != null)
                return
            }
            beginRecording()
                } catch (e: Throwable) {
            Log.e(TAG, "startRecording failed: ${e.message}", e)
            host.hidePreview() // no stale \"listening\" / \"loading\" pill after a failed or empty run
            host.setState(State.IDLE)
            ToastUtil.show(context, context.getString(R.string.preview_voice_start_failed, e.localizedMessage ?: context.getString(R.string.common_unknown_error)))
        }
    }

    /** Makes the engines match the models the user picked: engines of models no longer selected are released. */
    private fun syncEngines(liveModel: AsrModel?, finalModel: AsrModel?) {
        if (liveEngine?.model?.id != liveModel?.id) {
            runCatching { liveEngine?.release() }
            liveEngine = liveModel?.let { StreamingAsrEngine(context, it) }
        }
        if (this.finalModel?.id != finalModel?.id) {
            runCatching { finalEngine?.release() }
            finalEngine = finalModel?.let { FinalEngines.create(context, it) }
            this.finalModel = finalModel
        }
    }

    /** The final engine is not loaded yet, or (Qwen3) its hot words changed. */
    private fun finalNeedsLoad(): Boolean {
        val fe = finalEngine ?: return false
        return !fe.isLoaded() || (fe is Qwen3AsrEngine && fe.loadedHotwords != buildHotwords())
    }

    private fun preloadAndStart(dual: Boolean) {
        host.setState(State.LOADING)
        host.showStatus(context.getString(if (dual) R.string.preview_loading_dual_models else R.string.preview_loading_models))

        val generation = ++loadGeneration
        userLoading = true
        scope.launch {
            val success = try {
                withContext(Dispatchers.IO) {
                    val liveOk = liveEngine?.let { it.isLoaded() || it.load().success } ?: true
                    val finalOk = finalEngine?.load(buildHotwords())?.success ?: true
                    liveOk && finalOk
                }
            } finally {
                userLoading = false
            }
            // The user may have pressed X (or started over) while the models were loading.
            if (generation != loadGeneration) return@launch
            if (success) {
                // A load the user asked for worked: the pauses of the background loading end here.
                preloadPolicy.onUserLoadSucceeded()
                beginRecording()
            } else {
                host.hidePreview() // no stale \"listening\" / \"loading\" pill after a failed or empty run
                host.setState(State.IDLE)
                ToastUtil.show(context, R.string.preview_model_load_failed)
            }
        }
    }

    /** Both slots filled: live text first, final model decides. Only one: that one alone. */
    private fun beginRecording() {
        when {
            liveEngine != null && finalEngine != null -> startDualEngineRecording()
            liveEngine != null -> startStreamingRecording()
            else -> startOfflineRecording()
        }
    }

    // 雙引擎模式底層運作機制
    private fun startDualEngineRecording() {
        val xAsr = liveEngine ?: return
        val finalEngine = finalEngine ?: return
        val stream = xAsr.createStream() ?: run {
            ToastUtil.show(context, R.string.preview_cannot_create_stream)
            return
        }
        activeStream = stream
        isRecording = true
        isAborted = false
        host.setState(State.RECORDING)
        host.showXButton()
        host.showStatus(context.getString(R.string.preview_listening))

        val audioBuffer = mutableListOf<FloatArray>()
        var accumulatedXAsr = ""
        lastStreamingText = ""
        val livePreview = LivePreview(liveEngine?.model)

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
                            val shown = livePreview.text(partial)
                            mainHandler.post {
                                if (isRecording && !isAborted) {
                                    host.showLive(shown)
                                }
                            }
                        }
                        if (xAsr.isEndpoint(stream)) {
                            if (partial.isNotBlank()) {
                                accumulatedXAsr += partial
                                livePreview.commit(partial)
                            }
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
                host.hidePreview() // no stale \"listening\" / \"loading\" pill after a failed or empty run
                host.setState(State.IDLE)
                host.hideXButton()
                ToastUtil.show(context, R.string.preview_no_speech_detected)
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
                host.hidePreview() // no stale \"listening\" / \"loading\" pill after a failed or empty run
                host.setState(State.IDLE)
                host.hideXButton()
                ToastUtil.show(context, R.string.preview_no_speech_detected)
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
                    inferenceLock.withLock { finalEngine.transcribe(combinedAudio) }
                }
                if (isAborted) return@launch
                host.onTranscribed(finalizeText(raw, finalModel))
            } catch (ex: Throwable) {
                if (ex is kotlinx.coroutines.CancellationException || isAborted) return@launch
                Log.e(TAG, "Qwen3 batch inference failed: ${ex.message}", ex)
                val fallbackText = finalizeText(lastStreamingText, liveEngine?.model)
                if (fallbackText.isNotEmpty()) {
                    host.onTranscribed(fallbackText)
                    ToastUtil.show(context, R.string.toast_offline_model_error_applied_streaming)
                } else {
                    host.hidePreview() // no stale \"listening\" / \"loading\" pill after a failed or empty run
                    host.setState(State.IDLE)
                    host.hideXButton()
                    ToastUtil.show(context, R.string.toast_transcribe_failed_retry)
                }
            }
        }
    }

    private fun startOfflineRecording() {
        val finalEngine = finalEngine ?: return
        isRecording = true
        isAborted = false
        host.setState(State.RECORDING)
        host.showXButton()
        host.showStatus(context.getString(R.string.preview_listening))

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
                        inferenceLock.withLock { finalEngine.transcribe(recording.samples) }
                    }
                    if (isAborted) return@launch
                    host.onTranscribed(finalizeText(raw, finalModel))
                } catch (ex: Throwable) {
                    if (ex is kotlinx.coroutines.CancellationException || isAborted) return@launch
                    host.hidePreview() // no stale \"listening\" / \"loading\" pill after a failed or empty run
                    host.setState(State.IDLE)
                    host.hideXButton()
                    ToastUtil.show(context, R.string.toast_transcribe_failed_retry)
                }
            } else {
                host.hidePreview() // no stale \"listening\" / \"loading\" pill after a failed or empty run
                host.setState(State.IDLE)
                host.hideXButton()
                ToastUtil.show(context, R.string.preview_no_speech_detected)
            }
        }
    }

    private fun startStreamingRecording() {
        val engine = liveEngine ?: return
        val stream = engine.createStream() ?: run {
            ToastUtil.show(context, R.string.preview_cannot_create_stream)
            return
        }
        activeStream = stream
        isRecording = true
        isAborted = false
        host.setState(State.RECORDING)
        host.showXButton()
        host.showStatus(context.getString(R.string.preview_listening))

        var accumulated = ""
        var lastShown = ""
        val livePreview = LivePreview(engine.model)

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
                            val shown = livePreview.text(partial)
                            mainHandler.post {
                                if (isRecording && !isAborted) {
                                    host.showLive(shown)
                                }
                            }
                        }
                        if (engine.isEndpoint(stream)) {
                            if (partial.isNotBlank()) {
                                accumulated += partial
                                livePreview.commit(partial)
                            }
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
                host.hidePreview() // no stale \"listening\" / \"loading\" pill after a failed or empty run
                host.setState(State.IDLE)
                host.hideXButton()
                ToastUtil.show(context, R.string.preview_no_speech_detected)
                return@launch
            }

            // 整句完成：用模型的原文（未經即時轉換）整段重新做一次簡繁轉換與後處理
            val fullText = (accumulated + finalSegment).trim()
            host.onTranscribed(finalizeText(fullText, engine.model))
        }
    }

    /**
     * The one way every recognized text is finished, whichever model made it and whichever way it was recorded
     * (live only, final only, or live then final): the model's own tidying, then [postProcess].
     */
    private fun finalizeText(raw: String, producer: AsrModel?): String {
        if (raw.isBlank()) return ""
        return postProcess(producer?.normalizeText(raw) ?: raw, producer)
    }

    /** The script conversion the convert-to-Traditional setting asks for, given the model that wrote the text. */
    private fun scriptModeFor(producer: AsrModel?): ScriptMode = when {
        !ModelConfig.isConvertToTraditionalEnabled(context) -> ScriptMode.OFF
        // a model that writes Traditional Chinese by itself only needs the Taiwan wording rules
        producer?.nativeTraditional == true -> ScriptMode.VARIANTS_ONLY
        else -> ScriptMode.FULL
    }

    /**
     * 串流預覽的即時簡繁轉換（OpenCC）。每個已到端點的段落用它自己的原文轉一次後快取，
     * 之後的每個 chunk 只需轉換尚未結束的那一段，所以長句錄音也不會越轉越慢。
     * 這只影響預覽；最後送出的文字一律由 [finalizeText] 以整段原文重新轉換。
     */
    private inner class LivePreview(private val model: AsrModel?) {
        private val mode = scriptModeFor(model)
        private val committed = StringBuilder()

        private fun convert(raw: String): String {
            val tidied = model?.normalizeText(raw) ?: raw
            if (mode == ScriptMode.OFF) return tidied
            return runCatching { ZhTwProfile.convertScript(tidied, mode) }.getOrDefault(tidied)
        }

        /** An endpoint closed this segment: keep its converted form. */
        fun commit(segment: String) {
            committed.append(convert(segment))
        }

        /** The preview text: finished segments plus the segment still being spoken, both converted. */
        fun text(partial: String): String = committed.toString() + convert(partial)
    }

    private fun buildHotwords(): String =
        if (ModelConfig.isHotwordsEnabled(context)) {
            UserDictionary.load(context).values.distinct().sorted().joinToString("\n")
        } else {
            ""
        }

    /** 辨識後處理：依 [ZhTwProfile] 的規則依序套用。規則以字元證據逐句判斷，日韓文句子不會被動到。 */
    private fun postProcess(raw: String, producer: AsrModel?): String {
        val scriptMode = scriptModeFor(producer)
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
        val noFullWidth = if (ModelConfig.isFilterPunctuationEnabled(context)) {
            ZhTwProfile.filterPunctuation(numbered)
        } else {
            numbered
        }
        return if (ModelConfig.isFilterHalfWidthPunctuationEnabled(context)) {
            ZhTwProfile.filterHalfWidthPunctuation(noFullWidth)
        } else {
            noFullWidth
        }
    }

    companion object {
        private const val SHOWN_CHECK_INTERVAL_MS = 60_000L
        private const val TAG = "VoiceInput"
    }
}
