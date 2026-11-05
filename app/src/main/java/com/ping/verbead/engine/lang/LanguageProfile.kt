package com.ping.verbead.engine.lang

import com.ping.verbead.engine.SpokenNumberConverter

/** 簡轉繁的強度。 */
enum class ScriptMode {
    /** 不轉換，原樣保留辨識結果 */
    OFF,

    /** 只做台灣用詞與異體字修正（X-ASR 原生輸出繁體，不需要再簡轉繁） */
    VARIANTS_ONLY,

    /** 完整簡轉繁加台灣用詞 */
    FULL,
}

/**
 * 輸入語言（辨識語言）的後處理。與介面語言是兩條獨立的軸：
 * 介面語言決定畫面文字，輸入語言決定辨識後怎麼處理文字。
 *
 * 預設實作是「什麼都不改」，各語言只覆寫自己需要的步驟。
 */
interface LanguageProfile {
    /** 語言標籤，例如 "zh-TW"、"en"、"ja"、"ko" */
    val id: String

    /** 這個語言是否有簡轉繁（決定設定頁要不要顯示「轉換為繁體中文」） */
    val supportsScriptConversion: Boolean get() = false

    /**
     * X-ASR 串流模型是否適用於這個語言（它只針對中文最佳化）。
     * 不適用時辨識一律走 Qwen3-ASR，且不能開雙引擎。
     */
    val supportsXAsr: Boolean get() = false

    /**
     * 熱詞是否適用。Qwen3-ASR 的熱詞提示詞寫在原生函式庫裡，是中文的，
     * 詞庫也以中文為主，用在其他語言可能把語音拉向中文，所以只有中文啟用。
     */
    val supportsHotwords: Boolean get() = false

    /** 這個語言是否有口語數字規則（決定設定頁要不要顯示「口語數字轉阿拉伯數字」） */
    val supportsSpokenNumbers: Boolean get() = false

    fun convertScript(raw: String, mode: ScriptMode): String = raw

    /** 使用者詞彙置換之後的詞彙與異體字修正 */
    fun applyVariantRules(text: String): String = text

    fun convertSpokenNumbers(text: String): String = text

    fun filterPunctuation(text: String): String = CjkPunctuation.filter(text)
}

/** 全形與中日韓常用標點；各語言目前共用這一組。 */
object CjkPunctuation {
    private val REGEX = Regex("[，。！？、…：；「」『』—～（）《》〈〉【】〔〕]")

    fun filter(text: String): String = text.replace(REGEX, "")
}

/** 繁體中文（台灣）：簡轉繁、台灣用詞、口語數字。既有行為原樣保留。 */
object ZhTwProfile : LanguageProfile {
    override val id = "zh-TW"
    override val supportsXAsr = true
    override val supportsHotwords = true
    override val supportsScriptConversion = true
    override val supportsSpokenNumbers = true

    override fun convertScript(raw: String, mode: ScriptMode): String = when (mode) {
        ScriptMode.OFF -> raw
        ScriptMode.VARIANTS_ONLY -> ZhTwRules.normalizeTaiwanVariants(raw)
        ScriptMode.FULL -> ZhTwRules.toTaiwanTraditional(raw)
    }

    override fun applyVariantRules(text: String): String = ZhTwRules.applyPostRegexReplacements(text)

    override fun convertSpokenNumbers(text: String): String = SpokenNumberConverter.convert(text)
}

/** 簡體中文：不做簡轉繁，口語數字規則同中文。 */
object ZhCnProfile : LanguageProfile {
    override val id = "zh-CN"
    override val supportsXAsr = true
    override val supportsHotwords = true
    override val supportsSpokenNumbers = true

    override fun convertSpokenNumbers(text: String): String = SpokenNumberConverter.convert(text)
}

/** 英文、日文、韓文：辨識結果不做簡繁轉換，也還沒有口語數字規則。 */
class PlainProfile(override val id: String) : LanguageProfile

object LanguageProfiles {
    const val DEFAULT_ID = "zh-TW"

    /** 中文的輸入語言；「中文限定設定」區塊只對這些語言預設展開 */
    val CHINESE_IDS: Set<String> = setOf("zh-TW", "zh-CN")

    /** 設定頁顯示的順序 */
    val ALL: List<LanguageProfile> = listOf(
        ZhTwProfile,
        ZhCnProfile,
        PlainProfile("en"),
        PlainProfile("ja"),
        PlainProfile("ko"),
    )

    fun byId(id: String?): LanguageProfile = ALL.firstOrNull { it.id == id } ?: ZhTwProfile
}
