package com.ping.verbead.watch

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.TranscriptPostProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 一次「按下說話 → 轉寫 → 填入」。
 *
 * 音訊由手錶經 PKJS 以約 100ms 一包的 PCM16 送進 [feed]；這裡邊收邊餵給 X-ASR 產生即時預覽，
 * 結束時（手錶按鍵、Verbead 偵測到靜音、或連線中斷）依引擎模式定稿，套用與懸浮球相同的
 * 後處理，再交給 [deliver] 填入焦點文字框。
 *
 * 對手錶端送出的 JSON 事件：
 *  - `loading`   模型載入中（音訊會先排隊）
 *  - `ready`     已開始辨識
 *  - `partial`   `{text}` 即時預覽
 *  - `autostop`  `{reason}` 請手錶停止收音（偵測到說完或達上限）
 *  - `processing` 收音結束，正在定稿
 *  - `final`     `{text, injected}` 完成
 *  - `error`     `{code, message}` 失敗
 */
class WatchDictationSession(
    private val context: Context,
    private val engines: WatchEngines,
    scope: CoroutineScope,
    private val autoStop: Boolean,
    private val emit: (JSONObject) -> Unit,
    /** 將定稿文字填入焦點欄位；回傳是否成功直接填入（false 表示退而求其次複製到剪貼簿）。 */
    private val deliver: suspend (String) -> Boolean,
    private val onFinished: () -> Unit,
    /** 每包音訊的 RMS，供懸浮球脈動動畫使用。 */
    private val onAudioLevel: ((Float) -> Unit)? = null,
) {
    private val chunks = Channel<FloatArray>(Channel.UNLIMITED)
    @Volatile private var accepting = true
    private val job: Job = scope.launch(Dispatchers.Default) {
        try {
            run()
        } finally {
            accepting = false
            chunks.cancel()
            onFinished()
        }
    }

    /** 可由任何執行緒呼叫；結束後的資料一律忽略。 */
    fun feed(pcm16le: ByteArray) {
        if (accepting) chunks.trySend(PcmUtil.toFloats(pcm16le))
    }

    /** 手錶已停止收音：處理已收到的音訊並定稿。 */
    fun end() {
        accepting = false
        chunks.close()
    }

    /** 使用者在手機端（懸浮球）按下完成：請手錶停止收音，並處理已收到的音訊後定稿。 */
    fun stopFromPhone() {
        if (!accepting) return
        accepting = false
        send("autostop") { put("reason", "phone") }
        chunks.close()
    }

    /** 丟棄一切，不填入任何文字。 */
    fun cancel() {
        accepting = false
        chunks.cancel()
        job.cancel()
    }

    private fun send(type: String, build: JSONObject.() -> Unit = {}) {
        emit(JSONObject().put("type", type).apply(build))
    }

    private fun sendError(code: String, message: String) =
        send("error") { put("code", code); put("message", message) }

    private suspend fun run() {
        val mode = engines.resolveMode()
        if (mode == null) {
            sendError("models_missing", "尚未安裝所需的語音模型，請先在珠璣設定中下載。")
            return
        }
        // 模型已在記憶體時不必叫手錶顯示「載入中」
        if (!engines.isLoaded(mode)) send("loading")
        val loadStart = SystemClock.elapsedRealtime()
        val loadError = engines.acquire(mode)
        if (loadError != null) {
            sendError("load_failed", loadError)
            return
        }
        val loadSeconds = (SystemClock.elapsedRealtime() - loadStart) / 1000f
        try {
            process(mode, loadSeconds)
        } finally {
            // 被 cancel() 時此協程已取消，必須用 NonCancellable，否則計數不會歸還
            withContext(NonCancellable) { engines.release() }
        }
    }

    /**
     * @param loadSeconds 載入模型花的時間。手錶在這段期間仍持續送音訊（多半是靜音），
     *   載入完成後會一次餵進來，必須把這段時間加進「等待開口」的時限，否則慢速載入後
     *   會被誤判成沒有說話。
     */
    private suspend fun process(mode: WatchEngines.Mode, loadSeconds: Float) {
        val xAsr = engines.xAsr
        val stream = if (mode != WatchEngines.Mode.OFFLINE) xAsr.createStream() else null
        if (mode != WatchEngines.Mode.OFFLINE && stream == null) {
            sendError("load_failed", "無法建立串流辨識")
            return
        }

        val keepAudio = mode != WatchEngines.Mode.STREAMING
        val audio = ArrayList<FloatArray>()
        var audioSamples = 0
        var totalSamples = 0L
        var accumulated = ""
        var lastShown = ""

        val detector = if (autoStop) {
            SilenceDetector(
                sampleRate = SAMPLE_RATE,
                speechThreshold = ModelConfig.VAD_SPEECH_THRESHOLD,
                silenceThreshold = ModelConfig.VAD_SILENCE_THRESHOLD,
                trailingSilenceSeconds = ModelConfig.vadSilenceSeconds(context),
                initialTimeoutSeconds = ModelConfig.VAD_INITIAL_TIMEOUT_SECONDS + loadSeconds,
                minSeconds = ModelConfig.MIN_RECORD_SECONDS,
            )
        } else null

        send("ready")

        try {
            for (chunk in chunks) {
                totalSamples += chunk.size
                onAudioLevel?.invoke(PcmUtil.rms(chunk))

                if (stream != null) {
                    xAsr.acceptWaveform(stream, chunk)
                    while (xAsr.isReady(stream)) xAsr.decode(stream)
                    val partial = xAsr.getResult(stream)
                    val combined = accumulated + partial
                    if (combined.isNotBlank() && combined != lastShown) {
                        lastShown = combined
                        send("partial") { put("text", combined) }
                    }
                    if (xAsr.isEndpoint(stream)) {
                        if (partial.isNotBlank()) accumulated += partial
                        xAsr.reset(stream)
                    }
                }

                if (keepAudio && audioSamples < MAX_BUFFERED_SAMPLES) {
                    audio.add(chunk)
                    audioSamples += chunk.size
                }

                when (detector?.feed(chunk)) {
                    SilenceDetector.Event.AUTO_STOP -> {
                        Log.i(
                            TAG,
                            "auto-stop after ${totalSamples * 1000 / SAMPLE_RATE}ms: " +
                                "noise=${detector?.currentNoiseFloor} speech=${detector?.currentSpeechLevel} " +
                                "silenceGate=${detector?.effectiveSilenceThreshold}",
                        )
                        accepting = false
                        send("autostop") { put("reason", "silence") }
                        break
                    }
                    SilenceDetector.Event.NO_SPEECH -> {
                        accepting = false
                        send("autostop") { put("reason", "no_speech") }
                        sendError("no_speech", "沒有偵測到說話")
                        return
                    }
                    else -> Unit
                }

                if (totalSamples >= MAX_SESSION_SAMPLES) {
                    accepting = false
                    send("autostop") { put("reason", "max_duration") }
                    break
                }
            }

            send("processing")

            // 先收尾串流（X-ASR 的最後一段）
            var streamingText = ""
            if (stream != null) {
                runCatching { xAsr.inputFinished(stream) }
                runCatching { while (xAsr.isReady(stream)) xAsr.decode(stream) }
                streamingText = (accumulated + xAsr.getResult(stream)).trim()
            }

            val raw: String = when (mode) {
                WatchEngines.Mode.STREAMING -> streamingText
                WatchEngines.Mode.OFFLINE, WatchEngines.Mode.DUAL -> {
                    val samples = concat(audio, audioSamples)
                    // 模型若在錄音期間被釋放，transcribe 只會回傳空字串；先確認並補載
                    if (!engines.isLoaded(mode)) {
                        Log.w(TAG, "models not in memory before transcribe; reloading")
                        engines.ensureLoaded(mode)?.let { Log.e(TAG, "reload failed: $it") }
                    }
                    val qwenText = if (samples.isEmpty()) "" else try {
                        engines.qwen3.transcribe(samples)
                    } catch (e: Exception) {
                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                        Log.e(TAG, "Qwen3 transcribe failed: ${e.message}", e)
                        ""
                    }
                    // 與懸浮球相同的降級：定稿失敗時退回串流結果
                    qwenText.ifBlank { streamingText }
                }
            }

            val text = if (raw.isBlank()) "" else TranscriptPostProcessor.process(context, raw).trim()
            if (text.isEmpty()) {
                if (!engines.isLoaded(mode)) {
                    sendError("load_failed", "語音模型未載入，請重試")
                } else {
                    sendError("no_text", "沒有辨識到文字")
                }
                return
            }
            val injected = deliver(text)
            send("final") { put("text", text); put("injected", injected) }
        } finally {
            stream?.let { runCatching { it.release() } }
        }
    }

    private fun concat(parts: List<FloatArray>, total: Int): FloatArray {
        val out = FloatArray(total)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    companion object {
        private const val TAG = "WatchDictationSession"
        private const val SAMPLE_RATE = ModelConfig.ASR_SAMPLE_RATE
        /** 單次最長 60 秒；Qwen3 一次解碼的音訊也以此為上限。 */
        private const val MAX_SESSION_SAMPLES = 60L * SAMPLE_RATE
        private const val MAX_BUFFERED_SAMPLES = 60 * SAMPLE_RATE
    }
}
