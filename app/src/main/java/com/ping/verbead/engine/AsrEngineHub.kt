package com.ping.verbead.engine

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全行程共用的 ASR 引擎。懸浮球與手錶橋接用同一份已載入的模型，避免各載入一份
 * （X-ASR + Qwen3 合計超過 1GB）。
 *
 * 生命週期：
 *  - 懸浮球存活期間由它持有模型（與原本行為相同，直到服務結束才釋放）。
 *  - 懸浮球不在時，手錶橋接按需載入、閒置後釋放。
 *  兩邊都必須遵守：只有在「對方不需要」時才釋放，見 [bubbleAlive] 與 [watchSessions]。
 *
 * 引擎本身的 load() 已由各自的互斥鎖保護且對相同設定是冪等的；
 * 但同一份辨識器不得同時被兩邊解碼，使用端要自行避免並行（見 FloatingBubbleService.isVoiceBusyForWatch）。
 */
object AsrEngineHub {

    @Volatile private var xAsrInstance: XAsrEngine? = null
    @Volatile private var qwen3Instance: Qwen3AsrEngine? = null

    /** 懸浮球服務是否存活；存活時由它決定何時釋放模型。 */
    @Volatile var bubbleAlive = false

    /** 手錶橋接目前持有（正在使用）引擎的工作階段數。 */
    val watchSessions = AtomicInteger(0)

    fun xAsr(context: Context): XAsrEngine =
        xAsrInstance ?: synchronized(this) {
            xAsrInstance ?: XAsrEngine(context.applicationContext).also { xAsrInstance = it }
        }

    fun qwen3(context: Context): Qwen3AsrEngine =
        qwen3Instance ?: synchronized(this) {
            qwen3Instance ?: Qwen3AsrEngine(context.applicationContext).also { qwen3Instance = it }
        }

    /** 釋放已載入的模型。呼叫端需先確認對方沒有在使用。 */
    fun releaseAll() {
        xAsrInstance?.let { if (it.isLoaded()) it.release() }
        qwen3Instance?.let { if (it.isLoaded()) it.release() }
    }
}
