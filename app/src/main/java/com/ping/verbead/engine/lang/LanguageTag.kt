package com.ping.verbead.engine.lang

import androidx.annotation.StringRes
import com.ping.verbead.R
import com.ping.verbead.engine.ModelConfig

/**
 * 模型的語言標籤。標籤只描述「這個模型適合哪些語言」，用來在介面標示，
 * 之後加入其他語言的模型時，也可以拿它挑選或建議引擎，不必再改介面。
 *
 * 一個模型可以有多個標籤：
 *  - 指定語言的標籤（[ZH_TW]、[EN]…）：針對那個語言最佳化。
 *  - [MULTILINGUAL]：不針對單一語言，涵蓋多種語言（[ModelLanguages.supports] 對任何語言都為真）。
 */
enum class LanguageTag(
    val id: String,
    @StringRes val labelRes: Int,
    /** 通用標籤（多語言）：顯示樣式與指定語言的標籤不同 */
    val isGeneric: Boolean = false,
) {
    ZH_TW("zh-TW", R.string.settings_language_zh_tw),
    ZH_CN("zh-CN", R.string.settings_language_zh_cn),
    EN("en", R.string.settings_language_en),
    JA("ja", R.string.settings_language_ja),
    KO("ko", R.string.settings_language_ko),
    MULTILINGUAL("multilingual", R.string.language_tag_multilingual, isGeneric = true),
}

/**
 * 各辨識引擎的語言標籤。新增模型時，在 [TAGS] 登記它的標籤即可；
 * 介面會自動顯示（見 util/LanguageTagViews.kt）。
 */
object ModelLanguages {
    private val TAGS: Map<String, Set<LanguageTag>> = mapOf(
        // X-ASR：針對台灣繁體中文（含國台英語夾雜）最佳化
        ModelConfig.ENGINE_X_ASR to setOf(LanguageTag.ZH_TW),
        // Qwen3-ASR：多語言，語言由模型自動偵測
        ModelConfig.ENGINE_QWEN3 to setOf(LanguageTag.MULTILINGUAL),
    )

    /** 引擎的標籤；沒登記的引擎回傳空集合。 */
    fun tagsFor(engine: String): Set<LanguageTag> = TAGS[engine].orEmpty()

    /** 引擎是否適用於這個語言：標有該語言，或標為多語言。 */
    fun supports(engine: String, language: LanguageTag): Boolean {
        val tags = tagsFor(engine)
        return language in tags || LanguageTag.MULTILINGUAL in tags
    }

    /** 適用於某語言的引擎，指定語言的排在多語言之前（較專精者優先）。 */
    fun enginesFor(language: LanguageTag): List<String> =
        TAGS.keys.filter { supports(it, language) }
            .sortedBy { if (language in tagsFor(it)) 0 else 1 }
}
