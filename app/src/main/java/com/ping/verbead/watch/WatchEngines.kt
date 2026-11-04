package com.ping.verbead.watch

import android.content.Context
import android.util.Log
import com.ping.verbead.UserDictionary
import com.ping.verbead.engine.AsrEngineHub
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.Qwen3AsrEngine
import com.ping.verbead.engine.XAsrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 手錶橋接專用的 ASR 引擎持有者。
 *
 * 與懸浮球各自持有一份引擎（懸浮球的引擎是它的私有成員），因此這裡採「按需載入、
 * 閒置釋放」：沒有手錶工作階段時不佔用記憶體（Qwen3 約 900MB 模型）。
 */
class WatchEngines(context: Context, private val scope: CoroutineScope) {

    enum class Mode {
        /** 只用 X-ASR：邊收邊辨識，結束即定稿。 */
        STREAMING,
        /** 只用 Qwen3：收完整段音訊後一次辨識。 */
        OFFLINE,
        /** 雙引擎：X-ASR 即時預覽，Qwen3 定稿。 */
        DUAL,
    }

    private val app = context.applicationContext
    // 與懸浮球共用同一份引擎（見 AsrEngineHub），不再各載入一份
    val xAsr: XAsrEngine get() = AsrEngineHub.xAsr(app)
    val qwen3: Qwen3AsrEngine get() = AsrEngineHub.qwen3(app)

    private val mutex = Mutex()
    private var active = 0
    private var idleJob: Job? = null

    /** 與懸浮球相同的選擇邏輯；所需模型未安裝時回傳 null。 */
    fun resolveMode(): Mode? = when {
        ModelConfig.isDualEngineActive(app) -> Mode.DUAL
        ModelConfig.selectedEngine(app) == ModelConfig.ENGINE_X_ASR ->
            if (ModelConfig.isXAsrReady(app)) Mode.STREAMING else null
        else -> if (ModelConfig.isQwen3Ready(app)) Mode.OFFLINE else null
    }

    /** [mode] 所需的引擎目前是否都已在記憶體中。 */
    fun isLoaded(mode: Mode): Boolean =
        (mode == Mode.OFFLINE || xAsr.isLoaded()) && (mode == Mode.STREAMING || qwen3.isLoaded())

    /** @return 失敗原因；成功回傳 null。成功後必須配對呼叫 [release]。 */
    suspend fun acquire(mode: Mode): String? = mutex.withLock {
        idleJob?.cancel()
        idleJob = null
        try {
            val error = loadLocked(mode)
            if (error == null) {
                active++
                AsrEngineHub.watchSessions.incrementAndGet()
            }
            error
        } finally {
            // 載入失敗或被取消時沒有人會 release()，仍要確保閒置後釋放已載入的模型
            if (active == 0) scheduleIdleRelease()
        }
    }

    /**
     * 確認 [mode] 所需的模型仍在記憶體中，不在就重新載入（不改變使用計數）。
     * 給定稿前的最後防線：模型被系統或閒置釋放後，transcribe 只會回傳空字串。
     */
    suspend fun ensureLoaded(mode: Mode): String? = mutex.withLock { loadLocked(mode) }

    /**
     * 手錶一連上線或開啟 App 就先把模型載入，按下說話時多半已就緒；用完照常閒置釋放。
     * 與 [acquire] 共用同一把鎖，不會重複載入。
     */
    suspend fun preload() {
        val mode = resolveMode() ?: return
        if (acquire(mode) == null) release()
    }

    private suspend fun loadLocked(mode: Mode): String? {
        if (mode != Mode.OFFLINE) {
            val r = xAsr.load()
            if (!r.success) return r.error ?: "X-ASR 載入失敗"
        }
        if (mode != Mode.STREAMING) {
            val hotwords = UserDictionary.load(app).values.distinct().sorted().joinToString("\n")
            val r = qwen3.load(hotwords)
            if (!r.success) return r.error ?: "Qwen3-ASR 載入失敗"
        }
        return null
    }

    suspend fun release() {
        mutex.withLock {
            if (active > 0) {
                active--
                AsrEngineHub.watchSessions.decrementAndGet()
            }
            if (active == 0) scheduleIdleRelease()
        }
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock {
                if (active == 0) releaseAllLocked()
            }
        }
    }

    /** 服務結束時呼叫。 */
    suspend fun shutdown() {
        mutex.withLock {
            idleJob?.cancel()
            idleJob = null
            releaseAllLocked()
        }
    }

    private fun releaseAllLocked() {
        // 懸浮球存活時模型由它持有（與它自己載入時的行為一致），這裡不能釋放
        if (AsrEngineHub.bubbleAlive) return
        runCatching { AsrEngineHub.releaseAll() }
            .onFailure { Log.w(TAG, "engines release failed", it) }
        Log.i(TAG, "engines released")
    }

    companion object {
        private const val TAG = "WatchEngines"
        private const val IDLE_RELEASE_MS = 90_000L
    }
}
