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

    // 2. 兩岸多音字 / 破音字語境發音校正映射表 (詞組級替換)
    // 利用同音字替換技術，引導 Qwen3-TTS / 聲學模型自然發出台灣教育部國語標準音
    private val PHONETIC_WORD_MAPPINGS = listOf(
        // 垃圾: 台灣讀 lè sè (勒色)，大陸讀 lā jī
        "垃圾" to "勒色",

        // 給予: 台灣讀 jǐ yǔ (幾與)，大陸讀 gěi yǔ
        "給予" to "幾與",

        // 血跡 / 流血 / 吐血: 口語讀三聲 xiě
        "血跡" to "寫跡",
        "流血" to "流寫",
        "吐血" to "吐寫",

        // 微: 台灣在多數詞彙中讀二聲 wéi (維/唯)，大陸讀一聲 wēi
        "微笑" to "維笑",
        "微風" to "維風",
        "微小" to "維小",
        "微弱" to "維弱",
        "微量" to "維量",
        "微調" to "維調",
        "微妙" to "唯妙",
        "微軟" to "維軟",
        "稍微" to "稍維",
        "些微" to "些維",

        // 穴: 台灣讀四聲 xiè (謝)，大陸讀二聲 xué
        "洞穴" to "洞謝",
        "穴位" to "謝位",
        "穴道" to "謝道",
        "巢穴" to "巢謝",
        "空穴來風" to "空謝來風",
        "點穴" to "點謝",

        // 堤: 台灣讀二聲 tí (提)，大陸讀一聲 dī
        "堤防" to "提防",
        "海堤" to "海提",
        "堤岸" to "提岸",
        "河堤" to "河提",
        "防波堤" to "防波提",

        // 框: 台灣讀四聲 kuàng (況)，大陸讀一聲 kuāng
        "框架" to "況架",
        "框框" to "況況",
        "門框" to "門況",
        "相框" to "相況",
        "眼眶" to "眼況",

        // 偽: 台灣讀四聲 wèi (胃)，大陸讀三聲 wěi
        "虛偽" to "虛胃",
        "偽造" to "胃造",
        "偽裝" to "胃裝",
        "真偽" to "真胃",
        "偽鈔" to "胃鈔",

        // 綜: 台灣讀四聲 zòng (縱)，大陸讀一聲 zōng
        "綜合" to "縱合",
        "綜藝" to "縱藝",
        "綜述" to "縱述",
        "錯綜複雜" to "錯縱複雜",

        // 軸: 台灣讀四聲 zhòu (咒)，大陸讀二聲 zhóu
        "軸心" to "咒心",
        "主軸" to "主咒",
        "車軸" to "車咒",
        "輪軸" to "輪咒",
        "卷軸" to "卷咒",

        // 法: 台灣在國名法文讀四聲 fà (髮)，大陸讀三聲 fǎ
        "法國" to "髮國",
        "法文" to "髮文",
        "法語" to "髮語",
        "法蘭西" to "髮蘭西",
        "法式" to "髮式",

        // 期: 台灣在期待/期盼讀二聲 qí (奇)，大陸讀一聲 qī
        "期待" to "奇待",
        "期盼" to "奇盼",
        "期許" to "奇許",
        "期望" to "奇望",
        "限期" to "限奇",
        "到期" to "到奇",

        // 署: 台灣在部署讀四聲 shù (術)，大陸讀三聲 shǔ
        "部署" to "部術",

        // 淆: 台灣讀二聲 xiáo (肴)
        "混淆" to "混肴",

        // 剖: 台灣讀三聲 pǒu (叵)，大陸讀一聲 pōu
        "解剖" to "解叵",
        "剖析" to "叵析"
    )

    // 3. 全局單字多音字/聲調校正映射表 (無歧義、台灣統一唯一讀音)
    // 依據台灣教育部國語字典審定音，全面覆蓋所有字形變異
    private val PHONETIC_CHAR_MAPPINGS = listOf(
        // 企: 台灣一律讀四聲 qì (氣)；大陸普通話一律讀三聲 qǐ
        // (涵蓋: 企業、企圖、企鵝、企劃、企管、企盼、企及、企求等)
        "企" to "氣",

        // 質: 台灣一律讀二聲 zhí (直)；大陸普通話一律讀四聲 zhì
        // (涵蓋: 品質、物質、特質、性質、質疑、實質、體質、氣質、音質、畫質、材質、本質、變質、質量等)
        "質" to "直",

        // 檔: 台灣一律讀三聲 dǎng (黨)；大陸普通話讀四聲 dàng
        // (涵蓋: 檔案、存檔、建檔、文檔、歸檔、檔期、空檔、圖檔、讀檔、備份檔等)
        "檔" to "黨",

        // 酵: 台灣一律讀四聲 xiào (效)；大陸普通話讀四聲 jiào
        // (涵蓋: 發酵、酵母、酵素、酵母菌等)
        "酵" to "效",

        // 液: 台灣一律讀四聲 yì (意)；大陸普通話讀四聲 yè
        // (涵蓋: 液體、血液、溶液、體液、乳液、唾液、輸液、汁液、原液等)
        "液" to "意",

        // 暫: 台灣一律讀四聲 zhàn (佔)；大陸普通話讀四聲 zàn
        // (涵蓋: 暫時、暫停、短暫、暫且、暫緩、暫定等)
        "暫" to "佔",

        // 蝸: 台灣一律讀一聲 guā (瓜)；大陸普通話讀一聲 wō
        // (涵蓋: 蝸牛、蝸居等)
        "蝸" to "瓜",

        // 拙: 台灣一律讀二聲 zhuó (濁)；大陸普通話讀一聲 zhuō
        // (涵蓋: 笨拙、拙劣、弄巧成拙、守拙等)
        "拙" to "濁",

        // 攜: 台灣一律讀一聲 xī (西)；大陸普通話讀二聲 xié
        // (涵蓋: 攜帶、攜手、便攜、攜眷等)
        "攜" to "西"
    )

    // 保留非兒化的專有名詞或詞彙 (如兒童、兒子、女兒等)
    private val PRESERVED_ER_WORDS = setOf(
        "兒童", "兒子", "女兒", "孤兒", "嬰兒", "幼兒", "育兒", "健兒",
        "男兒", "少兒", "兒媳", "兒歌", "兒戲", "兒科", "寵兒", "混血兒"
    )

    private val ER_REGEX = Regex("([\\u4e00-\\u9fa5])兒(?=[，。！？、~～\\s]|$)")

    /**
     * 將輸入文字調節為貼向台灣腔的 TTS Prompting 文字。
     *
     * @param rawText 輸入原文
     * @param stylePrompt 自訂音色風格提示詞 (如：「親切溫柔」、「沉穩專業」、「活潑元氣」或使用者任意自訂 Prompt)
     * @param profile 聲線模型識別碼 (ModelConfig.TTS_PROFILE_*)
     */
    fun adapt(
        rawText: String,
        stylePrompt: String = "",
        profile: String = ModelConfig.TTS_PROFILE_TAIWAN_SWEET
    ): String {
        if (rawText.isBlank()) return ""

        var text = rawText

        // 1. 應用在地化用語替換
        for ((from, to) in VOCAB_MAPPINGS) {
            text = text.replace(from, to)
        }

        // 2. 應用兩岸多音字 / 破音字語境發音校正 (詞組級)
        for ((from, to) in PHONETIC_WORD_MAPPINGS) {
            text = text.replace(from, to)
        }

        // 3. 應用通用字級多音字校正 (如：企一律讀四聲氣、質一律讀二聲直、檔一律讀三聲黨等)
        for ((from, to) in PHONETIC_CHAR_MAPPINGS) {
            text = text.replace(from, to)
        }

        // 4. 濾除剩餘無實義之兒化音尾綴
        text = ER_REGEX.replace(text) { matchResult ->
            val precedingChar = matchResult.groupValues[1]
            val fullWord = precedingChar + "兒"
            if (fullWord in PRESERVED_ER_WORDS) {
                fullWord
            } else {
                precedingChar
            }
        }

        // 5. 語氣詞與句末柔化 (依據音色與自訂風格 Prompt 動態適配)
        val isCalm = profile == ModelConfig.TTS_PROFILE_TAIWAN_CALM ||
                stylePrompt.contains("沉穩") || stylePrompt.contains("知性") || stylePrompt.contains("專業")
        val isLively = profile == ModelConfig.TTS_PROFILE_TAIWAN_LIVELY ||
                stylePrompt.contains("活潑") || stylePrompt.contains("元氣") || stylePrompt.contains("熱情")
        val isMale = profile == ModelConfig.TTS_PROFILE_TAIWAN_MALE ||
                stylePrompt.contains("男") || stylePrompt.contains("陽光")

        when {
            isCalm -> {
                text = text.replace(Regex("什麼呢[？?]"), "什麼呢？")
                text = text.replace(Regex("好的[。！!~～]?"), "好的。")
                text = text.replace(Regex("沒問題[。！!~～]?"), "沒問題。")
                text = text.replace(Regex("謝謝[。！!~～]?"), "謝謝您。")
                text = text.replace(Regex("不用客氣[。！!~～]?"), "不用客氣。")
                text = text.replace(Regex("是的[。！!~～]?"), "是的。")
                text = text.replace(Regex("再見[。！!~～]?"), "再見。")
            }
            isLively -> {
                text = text.replace(Regex("什麼呢[？?]"), "什麼呀？")
                text = text.replace(Regex("好的[。！!~～]?"), "好呀！")
                text = text.replace(Regex("沒問題[。！!~～]?"), "沒問題唷！")
                text = text.replace(Regex("謝謝[。！!~～]?"), "太感謝啦！")
                text = text.replace(Regex("不用客氣[。！!~～]?"), "別客氣啦！")
                text = text.replace(Regex("是的[。！!~～]?"), "對呀！")
                text = text.replace(Regex("再見[。！!~～]?"), "下次見囉！")
            }
            isMale -> {
                text = text.replace(Regex("什麼呢[？?]"), "什麼呢？")
                text = text.replace(Regex("好的[。！!~～]?"), "好的！")
                text = text.replace(Regex("沒問題[。！!~～]?"), "沒問題～")
                text = text.replace(Regex("謝謝[。！!~～]?"), "謝謝你！")
                text = text.replace(Regex("不用客氣[。！!~～]?"), "不會，不客氣！")
                text = text.replace(Regex("是的[。！!~～]?"), "沒錯！")
                text = text.replace(Regex("再見[。！!~～]?"), "再見囉！")
            }
            else -> {
                // 預設甜美親切
                text = text.replace(Regex("什麼呢[？?]"), "什麼啊？")
                text = text.replace(Regex("好的[。！!~～]?"), "好喔～")
                text = text.replace(Regex("沒問題[。！!~～]?"), "沒問題啦～")
                text = text.replace(Regex("謝謝[。！!~～]?"), "謝謝喔！")
                text = text.replace(Regex("不用客氣[。！!~～]?"), "不會啦，不用客氣喔！")
                text = text.replace(Regex("是的[。！!~～]?"), "對啊～")
                text = text.replace(Regex("再見[。！!~～]?"), "掰掰囉！")
            }
        }

        // 6. 連接詞後的微停頓節奏調節 (加入逗號舒緩語速)
        text = text.replace(Regex("(?<![，,、])然後(?![，,])"), "，然後")
        text = text.replace(Regex("(?<![，,、])所以(?![，,])"), "，所以")

        return text
    }

    /**
     * 產出送入 Qwen3-TTS 的最終 Prompt：
     * 先進行台灣腔詞彙、破音字與標點適配，再轉換為 Qwen3 最佳詞表簡體字形表示。
     */
    fun buildQwen3Prompt(
        rawText: String,
        stylePrompt: String = "",
        profile: String = ModelConfig.TTS_PROFILE_TAIWAN_SWEET
    ): String {
        val adaptedTaiwanese = adapt(rawText, stylePrompt = stylePrompt, profile = profile)
        return try {
            ZhConverterUtil.toSimple(adaptedTaiwanese)
        } catch (_: Throwable) {
            adaptedTaiwanese
        }
    }
}
