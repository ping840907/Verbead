package com.ping.verbead

import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.ModelZipInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircuitLogicTest {

    @Test
    fun testChinesePunctuationFilter() {
        val input = "你好，世界！這是一個測試：成功了嗎？「當然」～"
        val expected = "你好世界這是一個測試成功了嗎當然"
        val actual = ModelConfig.filterChinesePunctuation(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testTaiwanTraditionalConversion() {
        val input = "看着刚才在家里里面"
        val expected = "看著剛才在家裡裡面"
        val actual = ModelConfig.toTaiwanTraditional(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testTaiwanVariantsNormalization() {
        val input = "剛纔心裏着火了"
        val expected = "剛才心裡著火了"
        val actual = ModelConfig.normalizeTaiwanVariants(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testUserDictionaryApply() {
        val dict = mapOf(
            "語音辨識" to "Verbead",
            "着" to "著",
            "通用詞" to "通用詞"
        )
        val input = "這是語音辨識測試，看着螢幕"
        val expected = "這是Verbead測試，看著螢幕"
        val actual = UserDictionary.apply(input, dict)
        assertEquals(expected, actual)
    }

    @Test
    fun testZhanRegexReplacement() {
        val input = "吃火鍋蘸醬、蘸料、蘸醋，蘸著吃，蘸一下，不蘸鍋。古人行蘸甲禮，傳統打鐵有蘸火工藝。"
        val expected = "吃火鍋沾醬、沾料、沾醋，沾著吃，沾一下，不沾鍋。古人行蘸甲禮，傳統打鐵有蘸火工藝。"
        val actual = ModelConfig.replaceZhan(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testHuiYingRegexReplacement() {
        // 一般語境下「迴應」應校正為「回應」
        val input1 = "對於這項質疑，官方正面迴應，他沒有任何迴應，積極迴應大眾。"
        val expected1 = "對於這項質疑，官方正面回應，他沒有任何回應，積極回應大眾。"
        val actual1 = ModelConfig.replaceHuiYing(input1)
        assertEquals(expected1, actual1)

        // 包含「巡迴/輪迴/迂迴/徘迴」之後綴應予以保留不被誤換
        val input2 = "巡迴應邀演出，歷經宿命輪迴應驗，迂迴應對得宜，徘迴應接不暇。"
        val actual2 = ModelConfig.replaceHuiYing(input2)
        assertEquals(input2, actual2)
    }

    @Test
    fun testPipelineWithZhanAndHuiYingReplacement() {
        // 透過完整 toTaiwanTraditional 管線測試簡繁轉換、蘸->沾 與 迴應->回應
        val input = "吃火锅蘸酱蘸着吃，请积极回应大众，巡回应邀出席"
        val expected = "吃火鍋沾醬沾著吃，請積極回應大眾，巡迴應邀出席"
        val actual = ModelConfig.toTaiwanTraditional(input)
        assertEquals(expected, actual)
    }

    @Test
    fun testCircuitLogicScenarioA() {
        // 情境 A:
        // mic=true, Bubble ok, X_ASR ok, QWEN3 ok, selected=X_ASR
        val micGranted = true
        val overlayGranted = true
        val accessibilityEnabled = true
        val xAsrDownloaded = true
        val qwen3Downloaded = true
        val selectedEngine = ModelConfig.ENGINE_X_ASR
        var dualEngineToggle = false

        val bubbleModuleLine = micGranted && overlayGranted && accessibilityEnabled
        val inputPathReady = bubbleModuleLine

        val xAsrReadyLine = xAsrDownloaded
        val qwen3ReadyLine = qwen3Downloaded

        val dualEngineSelectable = xAsrDownloaded && qwen3Downloaded && (selectedEngine == ModelConfig.ENGINE_QWEN3)
        if (!dualEngineSelectable) {
            dualEngineToggle = false
        }
        val dualEngineLine = dualEngineSelectable && dualEngineToggle

        val currentEngineReady = (selectedEngine == ModelConfig.ENGINE_X_ASR && xAsrDownloaded) ||
                (selectedEngine == ModelConfig.ENGINE_QWEN3 && qwen3Downloaded)
        val vocabularyLine = inputPathReady && currentEngineReady

        assertTrue(bubbleModuleLine)
        assertTrue(xAsrReadyLine)
        assertTrue(qwen3ReadyLine)
        assertFalse(dualEngineSelectable)
        assertFalse(dualEngineToggle)
        assertFalse(dualEngineLine)
        assertTrue(vocabularyLine)
    }

    @Test
    fun testCircuitLogicScenarioB() {
        // 情境 B:
        // 同情境 A，但 selected_engine=QWEN3_ASR，且使用者開啟 dual_engine_toggle=true
        val micGranted = true
        val overlayGranted = true
        val accessibilityEnabled = true
        val xAsrDownloaded = true
        val qwen3Downloaded = true
        val selectedEngine = ModelConfig.ENGINE_QWEN3
        var dualEngineToggle = true

        val bubbleModuleLine = micGranted && overlayGranted && accessibilityEnabled
        val inputPathReady = bubbleModuleLine

        val dualEngineSelectable = xAsrDownloaded && qwen3Downloaded && (selectedEngine == ModelConfig.ENGINE_QWEN3)
        val dualEngineLine = dualEngineSelectable && dualEngineToggle

        val currentEngineReady = (selectedEngine == ModelConfig.ENGINE_X_ASR && xAsrDownloaded) ||
                (selectedEngine == ModelConfig.ENGINE_QWEN3 && qwen3Downloaded)
        val vocabularyLine = inputPathReady && currentEngineReady

        assertTrue(dualEngineSelectable)
        assertTrue(dualEngineToggle)
        assertTrue(dualEngineLine)
        assertTrue(vocabularyLine)
    }

    @Test
    fun testCircuitLogicScenarioC() {
        // 情境 C:
        // 使用者僅下載 Qwen3，但 selected_engine=X_ASR
        val micGranted = true
        val overlayGranted = true
        val accessibilityEnabled = true
        val xAsrDownloaded = false
        val qwen3Downloaded = true
        val selectedEngine = ModelConfig.ENGINE_X_ASR

        val bubbleModuleLine = micGranted && overlayGranted && accessibilityEnabled
        val inputPathReady = bubbleModuleLine

        val xAsrReadyLine = xAsrDownloaded
        val qwen3ReadyLine = qwen3Downloaded

        val currentEngineReady = (selectedEngine == ModelConfig.ENGINE_X_ASR && xAsrDownloaded) ||
                (selectedEngine == ModelConfig.ENGINE_QWEN3 && qwen3Downloaded)
        val vocabularyLine = inputPathReady && currentEngineReady

        assertFalse(xAsrReadyLine)
        assertTrue(qwen3ReadyLine)
        assertFalse(currentEngineReady)
        assertFalse(vocabularyLine)
    }

    @Test
    fun testCircuitLogicScenarioD() {
        // 情境 D:
        // mic_permission_granted=false
        val micGranted = false
        val overlayGranted = true
        val accessibilityEnabled = true
        val xAsrDownloaded = true
        val qwen3Downloaded = true
        val selectedEngine = ModelConfig.ENGINE_X_ASR

        val bubbleModuleLine = micGranted && overlayGranted && accessibilityEnabled
        val inputPathReady = bubbleModuleLine

        val currentEngineReady = (selectedEngine == ModelConfig.ENGINE_X_ASR && xAsrDownloaded) ||
                (selectedEngine == ModelConfig.ENGINE_QWEN3 && qwen3Downloaded)
        val vocabularyLine = inputPathReady && currentEngineReady

        assertFalse(bubbleModuleLine)
        assertFalse(inputPathReady)
        assertFalse(vocabularyLine)
    }

    @Test
    fun testPureCharConversionPreservesOriginalVocabulary() {
        // 純字符通用繁體轉換：確認不會擅自將使用者原詞做兩岸用語置換
        val input = "开发软件和计算机网络系统"
        val converted = ModelConfig.toTaiwanTraditional(input)
        // 應保留「軟件」、「計算機」、「網絡」，而非被強制置換為「軟體」、「電腦」、「網路」
        assertEquals("開發軟件和計算機網絡系統", converted)
    }

    @Test
    fun testRareVariantReplacements() {
        // 定向修正常見古體字與生僻字
        val input = "剛纔心裏吃麪，衹有喫茶，拉開毛綫和生銹的門，擡頭看大樑上的羣山峯"
        val result = ModelConfig.normalizeTaiwanVariants(input)
        assertEquals("剛才心裡吃麵，只有吃茶，拉開毛線和生鏽的門，抬頭看大梁上的群山峰", result)
    }

    @Test
    fun testCombinedPipelineConversionAndVariantFix() {
        val input = "刚纔看着心裏吃靣"
        val converted = ModelConfig.toTaiwanTraditional(input)
        assertEquals("剛才看著心裡吃麵", converted)
    }

    @Test
    fun testModelZipEntryNormalization() {
        // 1. models/ 前綴應被去除
        assertEquals("ocr/pp_ocrv6_small_det.onnx", ModelZipInstaller.normalizeEntryName("models/ocr/pp_ocrv6_small_det.onnx"))
        assertEquals("x_asr/encoder.int8.onnx", ModelZipInstaller.normalizeEntryName("/models/x_asr/encoder.int8.onnx"))

        // 2. verbead_models/ 前綴應被去除
        assertEquals("ocr/pp_ocrv6_small_det.onnx", ModelZipInstaller.normalizeEntryName("verbead_models/ocr/pp_ocrv6_small_det.onnx"))
        assertEquals("qwen3_asr/encoder.int8.onnx", ModelZipInstaller.normalizeEntryName("/verbead_models/qwen3_asr/encoder.int8.onnx"))

        // 3. 根目錄本身應為空字串（供安裝器略過）
        assertEquals("", ModelZipInstaller.normalizeEntryName("models/"))
        assertEquals("", ModelZipInstaller.normalizeEntryName("verbead_models/"))

        // 4. 舊的 legacy 前綴不再被特別處理（保留原前綴，不予去除）
        val legacy = "voice" + "ime_models/ocr/model.onnx"
        assertEquals(legacy, ModelZipInstaller.normalizeEntryName(legacy))
    }
}
