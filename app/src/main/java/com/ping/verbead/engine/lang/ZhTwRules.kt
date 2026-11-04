package com.ping.verbead.engine.lang

import com.github.houbb.opencc4j.util.ZhConverterUtil
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder

/**
 * 繁體中文（台灣）的辨識後處理規則：簡轉繁、字元證據判斷、台灣用詞與異體字。
 * 這些規則屬於輸入語言軸，只由 [ZhTwProfile] 使用；內容原樣從 ModelConfig 搬來，行為不變。
 */
object ZhTwRules {
    private val KANA_HANGUL_BLOCKS = setOf(
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
    )

    /** 文字是否含有日文假名或韓文諺文。ASR 輸出中文時不會夾帶零星假名或諺文，出現了就是日韓文。 */
    fun isJapaneseOrKorean(text: String): Boolean =
        text.any { ch ->
            ch in '\uFF66'..'\uFF9F' || // 半形片假名
                Character.UnicodeBlock.of(ch) in KANA_HANGUL_BLOCKS
        }

    /** 日本 JIS 字集（Shift_JIS）；不支援時為 null，這時退回只看假名／諺文。 */
    private val JIS_CHARSET = runCatching { Charset.forName("Shift_JIS") }.getOrNull()

    private fun isHan(ch: Char): Boolean = when (Character.UnicodeBlock.of(ch)) {
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> true
        else -> false
    }

    /**
     * 是漢字、且不在日本 JIS 字集內：日文不會用這個字，所以只要出現就是中文的證據
     * （簡體專用字如 这 专 线 们 说 请，或繁體專用字）。中日共用的字（国 学 会 当…）都在 JIS 內，不算證據。
     */
    private fun isChineseOnlyHan(ch: Char, jis: CharsetEncoder?): Boolean =
        jis != null && isHan(ch) && !jis.canEncode(ch)

    private val CLAUSE_BOUNDARY = Regex("(?<=[，。！？、；：,.!?;:\n])")

    /**
     * 以標點與換行切成小句，對每個小句呼叫 [transform]，並告訴它這句「是不是中文」：
     *  - 含假名／諺文 → 不是（日韓文）。
     *  - 含只有中文才用的字 → 是（有證據）。
     *  - 兩者都沒有（只有漢字或英數）→ 看整段：整段完全沒有假名／諺文就當中文，否則不碰，
     *    因為「学校」這類中日共用字單看無從分辨。
     * 這樣混用時假名只影響它所在的小句，不會拖累其他中文句；
     * 而日文不會被誤轉，最壞情況只是少轉了純共用字組成的中文短句。
     */
    private fun mapByEvidence(text: String, transform: (String, Boolean, CharsetEncoder?) -> String): String {
        if (text.isEmpty()) return text
        val jis = JIS_CHARSET?.newEncoder()
        val textHasKana = isJapaneseOrKorean(text)
        return text.split(CLAUSE_BOUNDARY).joinToString("") { clause ->
            if (clause.isEmpty()) return@joinToString clause
            val chinese = !isJapaneseOrKorean(clause) &&
                (!textHasKana || clause.any { isChineseOnlyHan(it, jis) })
            transform(clause, chinese, jis)
        }
    }

    fun filterChinesePunctuation(text: String): String =
        CjkPunctuation.filter(text)

    // ── Traditional Chinese (Taiwan MOE Standard & Pure Character Conversion) ──
    /**
     * Converts text to Traditional Chinese using pure character-level conversion (s2t)
     * without vocabulary substitution, preserving the speaker's exact original phrasing.
     * Then applies point-to-point correction for archaic, rare, and non-standard variants.
     */
    fun toTaiwanTraditional(raw: String): String = mapByEvidence(raw) { clause, chinese, jis ->
        val converted = try {
            ZhConverterUtil.toTraditional(clause)
        } catch (_: Exception) {
            clause
        }
        val selected = when {
            chinese -> converted
            // 非中文小句：只轉「只有中文才用」的字，日韓文與共用字原樣保留
            converted.length != clause.length -> clause
            else -> buildString(clause.length) {
                for (i in clause.indices) {
                    append(if (isChineseOnlyHan(clause[i], jis)) converted[i] else clause[i])
                }
            }
        }
        if (chinese) normalizeVariants(selected) else selected
    }

    /**
     * Point-to-point correction table for archaic / rare variant characters output by s2t,
     * converting them to modern standard Traditional Chinese characters.
     */
    private val VARIANT_REPLACEMENTS = mapOf(
        '纔' to '才',
        '裏' to '裡',
        '着' to '著',
        '麪' to '麵',
        '靣' to '麵',
        '衹' to '只',
        '喫' to '吃',
        '綫' to '線',
        '銹' to '鏽',
        '擡' to '抬',
        '粧' to '妝',
        '鑒' to '鑑',
        '讃' to '讚',
        '盃' to '杯',
        '牀' to '床',
        '佔' to '占',
        '剋' to '克',
        '樑' to '梁',
        '羣' to '群',
        '峯' to '峰',
        '綉' to '繡',
        '祕' to '秘',
        '獃' to '呆',
        '艷' to '豔',
        '踊' to '踴',
        '昇' to '升',
        '衆' to '眾',
        '啓' to '啟',
        '爲' to '為',
        '鉢' to '缽',
        '糉' to '粽',
        '賬' to '帳',
        '脣' to '唇'
    )

    // ── Word & Character Normalization ────────────────────────────────────────
    /**
     * 正則表達式字詞替換規則架構：
     * 實作要點：詞彙規則先執行，單字異體字後執行，全部在 OpenCC 轉換之後執行。
     * 1. 詞彙替換：
     *    - 拼命 -> 拚命、打拼 -> 打拚、拼搏 -> 拚搏（拼圖、拼貼等其他詞彙不變）
     *    - 迴應 -> 回應：除「巡迴/輪迴/迂迴/徘迴」等複合詞外，替換為「回應」
     * 2. 單字正則替換：
     *    - 蘸 -> 沾：除「蘸甲」、「蘸火」外，替換為常用字「沾」
     *    - 泄 -> 洩：除「排泄」維持原字外，替換為常用字「洩」（(?<!排)泄）
     */
    data class WordReplacementRule(
        val name: String,
        val fastCheck: (String) -> Boolean,
        val regex: Regex,
        val replacement: String
    )

    private val VOCABULARY_RULES = listOf(
        WordReplacementRule(
            name = "拼命 -> 拚命",
            fastCheck = { it.contains("拼命") },
            regex = Regex("拼命"),
            replacement = "拚命"
        ),
        WordReplacementRule(
            name = "打拼 -> 打拚",
            fastCheck = { it.contains("打拼") },
            regex = Regex("打拼"),
            replacement = "打拚"
        ),
        WordReplacementRule(
            name = "拼搏 -> 拚搏",
            fastCheck = { it.contains("拼搏") },
            regex = Regex("拼搏"),
            replacement = "拚搏"
        ),
        WordReplacementRule(
            name = "迴應 -> 回應",
            fastCheck = { it.contains("迴應") },
            regex = Regex("""(?<![巡輪迂徘])迴應"""),
            replacement = "回應"
        )
    )

    private val CHARACTER_RULES = listOf(
        WordReplacementRule(
            name = "蘸 -> 沾",
            fastCheck = { it.contains('蘸') },
            regex = Regex("""(?!(?:蘸甲|蘸火))蘸"""),
            replacement = "沾"
        ),
        WordReplacementRule(
            name = "泄 -> 洩",
            fastCheck = { it.contains('泄') },
            regex = Regex("""(?<!排)泄"""),
            replacement = "洩"
        )
    )

    fun applyVocabularyRules(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        for (rule in VOCABULARY_RULES) {
            if (rule.fastCheck(result)) {
                result = result.replace(rule.regex, rule.replacement)
            }
        }
        return result
    }

    fun applyCharacterRules(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        for (rule in CHARACTER_RULES) {
            if (rule.fastCheck(result)) {
                result = result.replace(rule.regex, rule.replacement)
            }
        }
        return result
    }

    fun applyPostRegexReplacements(text: String): String =
        mapByEvidence(text) { clause, chinese, _ ->
            // 詞彙規則先執行，再單字正則替換
            if (chinese) applyCharacterRules(applyVocabularyRules(clause)) else clause
        }

    fun normalizeTaiwanVariants(text: String): String =
        mapByEvidence(text) { clause, chinese, _ -> if (chinese) normalizeVariants(clause) else clause }

    /** 對「已確定是中文」的一小句做詞彙、異體字與單字規則修正。 */
    private fun normalizeVariants(text: String): String {
        if (text.isEmpty()) return text
        // 1. 詞彙規則先執行
        val vocabApplied = applyVocabularyRules(text)
        // 2. 單字異體字替換
        val sb = java.lang.StringBuilder(vocabApplied.length)
        for (ch in vocabApplied) {
            sb.append(VARIANT_REPLACEMENTS[ch] ?: ch)
        }
        // 3. 單字正則規則
        return applyCharacterRules(sb.toString())
    }
}
