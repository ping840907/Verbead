package com.ping.verbead.engine

import android.content.Context
import com.ping.verbead.UserDictionary

/**
 * ASR 原始輸出的共用後處理（繁體轉換 → 自訂詞庫 → 正則修正 → 標點過濾）。
 *
 * 由懸浮球與手錶橋接共用，確保不論音訊來自手機麥克風或 Pebble 手錶，
 * 填入文字框的結果完全一致。
 */
object TranscriptPostProcessor {

    fun process(context: Context, raw: String): String {
        val converted =
            if (ModelConfig.selectedEngine(context) == ModelConfig.ENGINE_X_ASR &&
                !ModelConfig.isDualEngineActive(context)
            ) {
                ModelConfig.normalizeTaiwanVariants(raw)
            } else {
                ModelConfig.toTaiwanTraditional(raw)
            }
        val userReplaced = UserDictionary.apply(converted, UserDictionary.load(context))
        val postReplaced = if (userReplaced == converted) {
            converted
        } else {
            ModelConfig.applyPostRegexReplacements(userReplaced)
        }
        return if (ModelConfig.isFilterPunctuationEnabled(context)) {
            ModelConfig.filterChinesePunctuation(postReplaced)
        } else {
            postReplaced
        }
    }
}
