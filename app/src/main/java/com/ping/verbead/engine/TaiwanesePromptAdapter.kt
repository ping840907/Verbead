package com.ping.verbead.engine

import com.github.houbb.opencc4j.util.ZhConverterUtil

/**
 * 台灣腔文字 Prompting 聲學與詞彙調節適配器。
 *
 * 針對 Qwen3-TTS / 端側神經語音合成，在文字 Prompting 層面進行在地化轉換：
 * 1. 詞彙與在地語境對齊 (如：早上好 -> 早安、信息 -> 訊息、網絡 -> 網路、軟件 -> 軟體等)
 * 2. 兒化音精準濾除 (移除「哪兒、這兒、玩兒、聊會兒」等捲舌兒化尾音，避免觸發硬式北方腔)
 * 3. 台灣在地多音字/破音字發音映射 (如：垃圾 -> 勒色，確保唸作「lè sè」)
 * 4. 語氣詞與句末節奏舒緩 (如：「好喔～」、「沒問題啦！」、「謝謝你喔！」，注入台灣自然對話尾韻)
 * 5. 標點呼吸間隔調節 (插入適當頓號與停頓，防止語速過度壓縮)
 * 6. 轉化為 Qwen3-TTS 詞表專用高命中簡體字形表示
 */
object TaiwanesePromptAdapter {

    // 1. 常用在地化對等詞對照表
    private val VOCAB_MAPPINGS = listOf(
        // 問候與日常
        "早上好" to "早安",
        "下午好" to "午安",
        "晚上好" to "晚安",
        "沒事兒" to "沒事啦",
        "這兒" to "這裡",
        "哪兒" to "哪裡",
        "那兒" to "那裡",
        "一會兒" to "等一下",
        "一會" to "等一下",
        "聊會兒" to "聊一下",
        "聊天兒" to "聊天",
        "玩兒" to "玩",
        "倍兒" to "超",
        "一點兒" to "一點點",
        "請稍候" to "請稍等一下喔",
        "稍候" to "稍等一下喔",

        // 台灣破音字/特有音發音校正
        "垃圾" to "勒色", // 確保讀成 lè sè 而非 lā jī
        "攜帶" to "西帶", // 台灣習慣唸 xī dài 而非 xié dài
        "血跡" to "寫跡", // 台灣習慣唸 xiě jì
        "給予" to "幾與", // 台灣標準音 jǐ yǔ

        // 科技與資訊習慣用語
        "軟件" to "軟體",
        "硬件" to "硬體",
        "網絡" to "網路",
        "信息" to "訊息",
        "視頻" to "影片",
        "音頻" to "音訊",
        "屏幕" to "螢幕",
        "默認" to "預設",
        "打印" to "列印",
        "項目" to "專案",
        "質素" to "品質",
        "內存" to "記憶體",
        "優化" to "最佳化",
        "搞定" to "弄好",
        "支持" to "支援",
        "鏈接" to "連結",
        "界面" to "介面",
        "用戶" to "使用者",
        "登錄" to "登入",
        "服務器" to "伺服器",
        "移動端" to "行動端",
        "數據" to "資料"
    )

    // 保留非兒化的專有名詞或詞彙 (如兒童、兒子、女兒等)
    private val PRESERVED_ER_WORDS = setOf(
        "兒童", "兒子", "女兒", "孤兒", "嬰兒", "幼兒", "育兒", "健兒",
        "男兒", "少兒", "兒媳", "兒歌", "兒戲", "兒科", "寵兒", "混血兒"
    )

    private val ER_REGEX = Regex("([\\u4e00-\\u9fa5])兒(?=[，。！？、~～\\s]|$)")

    /**
     * 將輸入文字調節為貼向台灣腔的 TTS Prompting 文字。
     */
    fun adapt(rawText: String): String {
        if (rawText.isBlank()) return ""

        var text = rawText

        // 1. 應用在地化用語替換
        for ((from, to) in VOCAB_MAPPINGS) {
            text = text.replace(from, to)
        }

        // 2. 濾除剩餘無實義之兒化音尾綴
        text = ER_REGEX.replace(text) { matchResult ->
            val precedingChar = matchResult.groupValues[1]
            val fullWord = precedingChar + "兒"
            if (fullWord in PRESERVED_ER_WORDS) {
                fullWord
            } else {
                precedingChar
            }
        }

        // 3. 語氣詞與句末柔化 (增加台灣自然語調親和感)
        text = text.replace(Regex("什麼呢[？?]"), "什麼啊？")
        text = text.replace(Regex("好的[。！!]"), "好喔～")
        text = text.replace(Regex("沒問題[。！!]"), "沒問題啦～")
        text = text.replace(Regex("謝謝[。！!]?"), "謝謝喔！")
        text = text.replace(Regex("不用客氣[。！!]?"), "不會啦，不用客氣喔！")
        text = text.replace(Regex("是的[。！!]"), "對啊～")
        text = text.replace(Regex("再見[。！!]?"), "掰掰囉！")

        // 4. 連接詞後的微停頓節奏調節 (加入逗號舒緩語速)
        text = text.replace(Regex("(?<![，,、])然後(?![，,])"), "，然後")
        text = text.replace(Regex("(?<![，,、])所以(?![，,])"), "，所以")

        return text
    }

    /**
     * 產出送入 Qwen3-TTS 的最終 Prompt：
     * 先進行台灣腔詞彙與標點適配，再轉換為 Qwen3 最佳詞表簡體字形表示。
     */
    fun buildQwen3Prompt(rawText: String): String {
        val adaptedTaiwanese = adapt(rawText)
        return try {
            ZhConverterUtil.toSimple(adaptedTaiwanese)
        } catch (_: Throwable) {
            adaptedTaiwanese
        }
    }
}
