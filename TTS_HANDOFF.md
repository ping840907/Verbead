# 朗讀（TTS）功能交接文件

給接手的本地 Claude Code。這份文件自成一體，不需要看過先前的雲端對話。
配套文件：`TTS_PHASE0.md`（第 0 階段驗證數據與手機測試步驟）。

- 分支：`ccr-47e9f1e4-6t2t0m`（已推送，尚未開 PR）
- 本機開始前：`git fetch origin ccr-47e9f1e4-6t2t0m && git checkout ccr-47e9f1e4-6t2t0m`

---

## 1. 需求（使用者原話整理）

為「珠璣（Verbead）」新增朗讀功能：

1. 載入社群編譯的 tflite TTS 模型（參考 Google AI Edge Gallery / litert-community），
2. 比照 ASR 功能，提供使用者明確的**屬性標籤**（語言、聲音、大小、速度等），
3. 按下**懸浮球**後變成**播放介面**，手機用 TTS 唸出畫面上的內容；**若有反白就唸反白範圍**，
4. **OCR 要如何在這個功能中發揮用途**還在構思（見第 5 節的建議）。

主要使用者在台灣，**中文（繁體）是必要的**。

## 2. 目前狀態

| 項目 | 狀態 |
|---|---|
| 功能規劃（模型層、播放流程、UI、來源、OCR 角色、階段） | 已完成，濃縮於本文 |
| 第 0 階段：雲端可做的驗證 | 已完成（見 `TTS_PHASE0.md`） |
| 第 0 階段：手機實測（RTF、記憶體、反白可讀性、OCR 對照） | 已做（見 `TTS_PHASE0.md`）；LINE、閱讀器、遊戲等情境與 ASR 並存記憶體尚未測 |
| 兩支 debug 探針 | 已修正並在實機跑過（見 `TTS_PHASE0.md` 的實測結果） |
| 第 1 階段（系統 TTS 版） | **已完成並在實機驗證**：朗讀模式（膠囊第 5 項，模式 id 5）、播放器面板（句子高亮、播放/暫停、上一句/下一句、語速、重新讀取）、反白 → 畫面文字來源、系統 `TextToSpeech`、AudioFocus、5 種語系字串、單元測試 |
| 第 2 階段（模型） | **已完成並在實機驗證**：`TtsCatalog` + `tts_models.json`（Melo、Kokoro，SHA-256 已固定）、`SherpaTtsEngine`（合成與播放分兩條執行緒，下一句預先合成）、下載/刪除/選用/選聲音/試聽的朗讀設定頁、載入失敗自動退回系統語音。手機實測：Melo 讀畫面文字正常，讀取時 PSS 約 545 MB，關閉播放器後降到約 206 MB |
| 第 2 階段尚未驗證 | 從 App 內實際下載（只用 adb 放檔案驗證過偵測與播放）、Kokoro 在 App 內載入與選聲音、Kokoro 聲音編號對應的名稱（目前只顯示「第 N 號」，預設中文 3、英文 0 為推測，待試聽確認） |
| 第 1 階段尚未做 | 系統 TTS 沒有對應語言語音時的提示 |
| 第 3 階段（OCR 整合） | **已完成並在實機驗證**：節點樹文字太少時自動截圖辨識（`OcrFallbackPolicy`：少於 20 字，或覆蓋率低於 15% 且少於 120 字）、辨識結果比節點樹多才採用、略過狀態列與導覽列、朗讀時在畫面上框出目前這一行（`TtsHighlightOverlay`）、「選取區塊」按鈕：框出所有行，點一行就從那裡開始唸（`ReadingPlan` 把每句對應到畫面上的一行）。debug 版有 `OcrFallbackTestActivity` 可模擬「沒有文字節點」的畫面 |
| 句間停頓設定 | **已完成**：朗讀設定頁「句間停頓」短／標準／長（0.5×／1×／1.7× 的標準停頓），`SpeechAudio.pauseMs(text, scale)`，縮放值是 `SherpaTtsEngine` 快取鍵的一部分，改設定馬上生效。只適用於下載的聲音，系統語音有自己的節奏 |
| 連續閱讀 | **已完成**：朗讀設定頁開關（預設關）。唸完一頁後 `ScreenScroller` 對畫面上最大的可捲動區做 `ACTION_SCROLL_FORWARD`，等 0.9 秒，重新讀畫面，`PageContinuation.firstNew` 略過與上一頁重複的句子，再接著唸。停止條件：沒有可捲動的區域或捲不動、新頁面沒有新內容（顯示「已讀到最後」）、使用者暫停或關閉、已連讀 40 頁。只對「畫面」來源（含 OCR）生效，反白不會連續讀。重複判斷：句子至少 6 個字母數字、文字涵蓋率 >= 0.85 且數字必須一致（避免「第 15 段」「第 16 段」這類編號樣板被當成重複）。已知弱點：只差一個字的樣板句（第一段／第二段）仍可能被誤認，實測在手機上驗證了翻頁與略過重複，數字規則只有單元測試 |
| 球的 SPEAKING 狀態 | **已完成**：`BubbleState.SPEAKING`；朗讀時球保持原底色（紅色底是錄音用的），外圈改成藍色呼吸圈（`BubblePulseController.showSpeaking`）。只在球為 IDLE／PASTED 時進入，不影響錄音與掃描；連續閱讀換頁期間維持朗讀中的外觀 |
| 句間停頓（Melo/Kokoro） | **已改為無縫播放並在實機驗證**：實測句間額外耗時只有約 20 毫秒，真正的停頓來自模型輸出自帶的頭尾靜音（Melo 約 50–110 毫秒前、最多約 140 毫秒後；Kokoro 約 150 毫秒後）與每句重建播放軌。現在 `SpeechAudio` 修剪頭尾靜音並加上自訂停頓（句號類 180、分號冒號類 120、其他 90 毫秒），`SherpaTtsEngine` 用單一播放軌，`TtsEngine.queue` 讓引擎預先排隊接下來兩句（`TtsSession` 佇列深度 2），系統語音用 `QUEUE_ADD`（語言與速度相同時）。合成結果快取最近 6 句，暫停後繼續與上一句/下一句變成立即。實測每句開始時播放緩衝仍剩約 480–494 毫秒，沒有負值（負值代表有空隙） |
| 折行合併 | **已完成並在實機驗證**：同一段落折成多行的文字（OCR 與無障礙）會接成完整句子再依標點切句（`Paragraphs`、`ReadingPlan`）。判斷：上一行沒有句末標點、寫到右緣、下一行緊接在下方且字級與左緣相近、行內框之間空隙小；標題、列表列、表格欄位不合併。一句可跨多行，畫面標示框出整句涵蓋的所有行。實測設定頁 34 句降為 28 句 |
| OCR 座標修正 | 修正兩個錯誤：（1）原本用 `displayMetrics.heightPixels`（不含導覽列）縮放整張截圖，造成框約 7% 的垂直偏移；截圖與無障礙座標是同一像素空間，不需縮放。（2）偵測框比字形小（實測 18 對 30 像素），`OcrTextSource` 現在用與辨識裁切相同的 40% 擴張，讓框等於字形大小 |
| 混合來源（可開關） | **已完成並在實機驗證**：朗讀設定頁頂端的開關（預設關）。開啟後每次朗讀都同時讀無障礙樹與 OCR，`HybridMerger` 依位置與文字相似度（`TextSimilarity.containment`，字元雙連對的涵蓋率 >= 0.5，容許 OCR 錯字）對位：對得上的以無障礙原文為準（OCR 錯字被取代），無障礙沒有的圖片文字依位置補進去，少於 2 個字母或數字的雜訊不補。實測：一行無障礙文字加一張圖片，`tree=1 ocr=32 matched=2 inserted=27`。代價：OCR 要先跑完才開始朗讀（約 1–4 秒）。debug 測試頁 `OcrFallbackTestActivity --ez text true` 可重現 |
| 混合來源尚未做 | 只對沒有文字的區域做 OCR 以省時間（目前整頁偵測並逐框辨識）、對不確定的比對顯示信心、以 OCR 修正無障礙被截斷（ellipsize）的文字 |
| 第 4 階段尚未做 | 換頁時的空檔（目前唸完才捲動並重讀，約 1–2 秒；可在唸最後兩句時預先處理）、虛擬清單內容在捲動後才載入時的等待、連續閱讀時 OCR 的頁間重複判斷以外的版面去重（頁首頁尾固定列） |
| 第 3 階段尚未做 | 直排文字的閱讀順序（OCR 依橫排重組）、混合來源（只對沒文字的圖片節點 OCR）、連續閱讀（自動捲動）、相機朗讀、語言分流、LiteRT 載入器（KittenTTS） |

## 3. 已確定的關鍵結論

1. **社群 tflite 目前沒有「即時且支援中文」的 TTS。**
   KittenTTS（英文，RTF 約 0.22–0.29）、Matcha（英文，約 0.8）、Qwen3-TTS（含中文但 RTF 約 6.7，不能即時）。
   速度為範例作者在 Pixel 8a 的自述，未重測。來源：`github.com/google-ai-edge/litert-samples` 的
   `samples/litert/text_to_speech`、`text_to_speech_streaming`、`text_to_speech_lm`。
2. **App 已內建 sherpa-onnx 2.0.1，其 Kotlin API（`OfflineTts`）可做中文 TTS 與串流**
   （`generateWithCallback`）。雲端實測 `vits-melo-tts-zh_en`（183 MB，RTF 0.20–0.25）與
   `kokoro-multi-lang-v1_1`（409 MB，103 聲音，RTF 0.26–0.34）。
3. **繁體字輸入可直接使用**（與簡體音長一致，回頭辨識內容一致）。
4. **Kokoro 會丟掉大寫英文縮寫**（`OOV word skipped: 'GPU'`），需要文字正規化；Melo 沒有此問題。
5. 專案的 LiteRT 2.3.2 已使用 `CompiledModel`（見 `PpOcrEngine`），範例用 2.3.0，不需升級。
6. Gallery 內建 TTS 是 `.litertlm`，與社群 `.tflite` 不通用。
7. 兩個 Android 端待查：
   - Kotlin API 把 `dictDir` 標為「unused」，Melo 在手機上的分詞是否與 Python 測試一致；
   - 反白選取在 Chrome / WebView 是否讀得到（備案：`ACTION_COPY` 讀剪貼簿，且要還原剪貼簿）。

## 4. 建議的架構決策（使用者尚未逐項確認，請在動工前確認或沿用預設）

使用者尚未回答的三個問題，我的預設是：

1. 第一版語言重心：**中文**（英文是附帶）。
2. 系統 `TextToSpeech` 作為**保底引擎**：採用。沒有模型時也能朗讀。
3. OCR 優先順序：保底來源 → 點選區塊起唸 → 同步高亮。

據此的調整後規劃：

- **引擎**：`SYSTEM`（保底）、`SHERPA_TTS`（中文主力，Melo / Kokoro）、`LITERT`（第二階段，先做英文 KittenTTS，作為「社群 tflite」示範）。
- **模型清單**：比照 `AsrCatalog`＋`app/src/main/resources/asr_models.json`，新增 `TtsCatalog` 與 `tts_models.json`，支援自訂 manifest。
- **下載 / 匯入**：重用 `ModelDownloader`、`ModelDownloadSpec.forEngine`、`ModelZipInstaller`、`ModelDownloadService`（需擴充白名單與 `forEngine`）。

### 屬性標籤（比照 ASR 的 `AsrMode / AsrTier / languages / description`）

語言（BCP-47，規則同 `supportsLanguage`）、聲音（男/女/中性、說話者清單）、等級（輕巧/均衡/最佳）、
速度（實測 RTF 與 GPU/CPU）、是否可逐句串流、大小（MB）與取樣率、授權、來源（官方/社群/自訂，自訂顯示警示）。

### 合成與播放

```
來源文字 → 正規化 → 切句 → 語言判斷 → 文字前端 → 聲學模型 → 聲碼器 → 播放佇列
```

- 正規化：網址、emoji、數字與單位、**大寫縮寫拆字母**（Kokoro 必要）、中英混排停頓。
- 切句後「播放第 N 句、背景合成第 N+1 句」，用 `AudioTrack` 串接。
- 處理 AudioFocus（來電、其他媒體）、拔耳機暫停。
- 記憶體：ASR / OCR / TTS 不要同時常駐，TTS 閒置後卸載。可仿 `AsrMemoryBenchReceiver` 做量測。

### UI 與狀態

- 膠囊選單新增第 5 個模式「朗讀」（`BubbleModeConfigAdapter`、`CapsuleMenuController`）。
- 點球進入**播放器面板**：新增 `TtsPlayerController`，註冊進 `OverlayCoordinator`（新增 `Kind.TTS`，一次只開一個）。
- `BubbleState` 新增 `SPEAKING`，並更新 `BubbleStateMachine` 轉換規則。球外圈沿用錄音脈衝圈但換色。
- ✕ 鍵：朗讀中 = 停止並關閉面板（符合現有情境感知）。朗讀不改動輸入框，不需復原。
- 面板：來源標籤（選取文字 / 畫面文字 / OCR）、目前句子逐字稿高亮、播放暫停、上一句/下一句、語速、句子進度。
- 設定頁 `TtsSettingsActivity`：模型卡片（含上述標籤）、下載與匯入、各語言預設聲音、語速、來源策略、過濾規則（網址/emoji）、高亮與自動滾動開關。
- 新增字串需補齊 5 種語系（繁中、簡中、English、日本語、한국어），請檢查 `app/src/main/res/values*/`。

## 5. 文字來源與 OCR 的角色

來源優先順序：**① 反白 → ② 畫面文字（節點樹）→ ③ OCR**。

- **反白**：走訪各視窗節點，找 `textSelectionStart != textSelectionEnd` 的節點取子字串。
  限制：Chrome/WebView 與部分 App 不暴露。備案 `ACTION_COPY` + 讀剪貼簿，預設關閉，且必須備份並還原使用者剪貼簿。
- **畫面文字**：只取 `isVisibleToUser`；略過密碼欄、自家懸浮視窗、狀態列/導覽列；父子重複去重；
  依 bounds 重排閱讀順序（可借用 `TextStitcher` 的幾何規則）。
  無障礙設定已有 `flagRetrieveInteractiveWindows` 與 `canTakeScreenshot`（`res/xml/accessibility_service_config.xml`）。
- **OCR 的用途（按價值排序）**：
  1. **保底來源**：節點樹文字不足時自動改用 OCR（遊戲、圖片、截圖、掃描 PDF、無語意的 Flutter/Canvas）。
     判斷：文字節點覆蓋面積比例＋字數門檻（門檻用第 0 階段實測定）。擷取用 `VoiceAccessibilityService.captureScreen`，辨識用 `PpOcrEngine`。
  2. **點選要唸的區塊（最推薦）**：重用現有 OCR 的方框偵測、點選編號、框選、全選（`OcrOverlayController`）。
     補足「無法反白」的畫面；新增「從這裡開始唸」。
  3. **同步高亮**：朗讀到哪句，就在畫面對應位置畫框（OCR 方框與節點 bounds 都有座標）。
  4. 混合來源（第二階段）：節點樹讀文字，只對沒文字的大圖片節點裁切 OCR，再依座標插回順序。
  5. 相機朗讀：現有相機 OCR 視窗，拍照後唸（書、藥品說明、路牌）。
  6. 語言分流：OCR 依文字系統分段（參考 `KoreanFallback` 思路），各段選對應引擎。
  7. 品質過濾：丟棄低信心結果，避免唸出雜訊。
  - 自動連續閱讀（第二階段）：唸完執行 `ACTION_SCROLL_FORWARD` 再抓下一頁。
  - `TextStitcher` 的智慧折行拼接對 TTS 特別有用，可避免在自然折行處停頓。

## 6. 專案內重要位置（本次實際讀過）

| 位置 | 備註 |
|---|---|
| `app/src/main/java/com/ping/verbead/FloatingBubbleService.kt` | 3177 行，懸浮球本體與各模式協調 |
| `.../bubble/OverlayCoordinator.kt` | 條碼/OCR/常用語視窗的開關與定位（`Kind` 列舉） |
| `.../bubble/BubbleStateMachine.kt` | `BubbleState` 與轉換規則 |
| `.../bubble/BubbleModeConfigAdapter.kt`、`CapsuleMenuController.kt` | 模式清單與膠囊選單 |
| `.../bubble/OcrOverlayController.kt`、`.../ocr/PpOcrEngine.kt`、`TextStitcher.kt` | OCR UI 與引擎、折行拼接 |
| `.../VoiceAccessibilityService.kt` | `captureScreen`、焦點節點、文字填入；單例 `instance` |
| `.../engine/AsrCatalog.kt`、`resources/asr_models.json` | 資料驅動的模型清單（TTS 目錄照此做） |
| `.../engine/ModelDownloadSpec.kt`、`ModelDownloader.kt`、`ModelZipInstaller.kt` | 下載、驗證、匯入 |
| `.../engine/ModelConfig.kt` | 設定與偏好儲存（941 行） |
| `app/src/debug/` | debug 專用接收器（含本次新增兩支）與 `AndroidManifest.xml` |
| `APP_SPECIFICATION.md` | 產品規格（做完需新增朗讀章節；README 致謝表也要補） |

注意：

- `app/libs/*.aar`、`*.onnx`、`/ocr/` 等被 `.gitignore` 排除。sherpa-onnx AAR 需自行放入 `app/libs/sherpa-onnx-2.0.1.aar`（雲端沒有此檔）。
- `docs/` 與 `tools/` 也被忽略（本機專用），所以交接與報告放在根目錄。
- 建置指令：`./gradlew testDebugUnitTest`、`./gradlew assembleDebug`（見 `README.md`）。

## 7. 接手後的步驟

### 步驟 A：編譯並跑第 0 階段手機實測（先做）

1. 取得專案並放入 sherpa AAR，執行 `./gradlew assembleDebug`；若兩支新探針報錯，先修編譯問題。
2. 依 `TTS_PHASE0.md` 第二節，在實機跑 `SherpaTtsBenchReceiver`（Melo、Kokoro、threads 2/4，並在 ASR 模型已載入時再跑一次看記憶體）與 `TtsSourceProbeReceiver`（建議測試矩陣見該文件）。
3. 用耳朵聽 `bench_*.wav`，判斷中文發音品質。
4. 把數字回填到 `TTS_PHASE0.md`，並依其「判斷標準」決定模型與門檻：
   - 中文 TTS RTF ≤ 0.7 才適合逐句串流；
   - 若 Melo 分詞異常，評估改用 Kokoro 或預先分詞（`GenerationConfig.tokens`）。

### 步驟 B：第 1 階段（系統 TTS 版，無需模型即可用）

1. 新增朗讀模式、`SPEAKING` 狀態、`TtsPlayerController` 與 `OverlayCoordinator` 的 `Kind.TTS`。
2. 文字來源：反白 → 節點樹（含閱讀順序、過濾規則）。
3. 引擎介面（建議 `TtsEngine`）＋ 系統 `TextToSpeech` 實作；切句、播放佇列、AudioFocus。
4. 單元測試：正規化、切句、來源判斷、閱讀順序排序（照 `AsrCatalog` 既有測試風格）。

### 步驟 C：第 2 階段（模型）

`TtsCatalog`＋`tts_models.json`、`SHERPA_TTS` 載入器（`OfflineTts` + 串流）、下載/匯入、`TtsSettingsActivity`。

### 步驟 D：第 3 階段（OCR 整合）

保底自動切換、點選區塊起唸、同步高亮。

### 步驟 E：第 4 階段（進階）

混合來源、連續閱讀、相機朗讀、語言分流、`LITERT` 載入器（KittenTTS）。

## 8. 風險與注意

- **中文發音品質與手機 RTF 未驗證**，這是最大的未知；請先做步驟 A 再決定模型。
- 記憶體：ASR、OCR、TTS 並存可能被系統殺掉，需閒置卸載。
- 隱私：畫面文字可能敏感，不連網、略過密碼欄、尊重 `FLAG_SECURE`、OCR 擷取失敗要明確提示。
- 自訂 manifest 的社群模型要顯示來源與授權提醒，下載驗證 SHA-256。
- 授權：Melo、Kokoro、sherpa-onnx 的授權與致謝需補進 README 表格（依各模型頁面為準，本次未逐一核對授權條款）。
- 本次所有模型下載都來自 `github.com/k2-fsa/sherpa-onnx/releases`；Hugging Face 在雲端環境無法連線，所以 litert-community 的模型頁內容我沒有讀到，只讀了 `litert-samples` 原始碼。

## 9. 可直接貼給本地 Claude Code 的開場提示

```
請先讀 TTS_HANDOFF.md 與 TTS_PHASE0.md，我們在分支 ccr-47e9f1e4-6t2t0m 上接手「朗讀（TTS）」功能。
目前只完成規劃與雲端驗證，正式功能尚未開始。

請先做第 7 節的步驟 A：
1. 確認專案能編譯（app/libs 需要 sherpa-onnx-2.0.1.aar，我會提供或請你告訴我放哪裡），
   修好兩支 debug 探針 SherpaTtsBenchReceiver、TtsSourceProbeReceiver 的編譯問題；
2. 指引我在手機上執行它們並回收日誌，把數字寫回 TTS_PHASE0.md。

之後再進入第 1 階段。動工前請先確認第 4 節三個預設假設（中文優先、系統 TTS 保底、OCR 優先順序）。
不要自行開 PR。
```

## 附錄：雲端 bench 的重現方式

```bash
python3 -m venv v && ./v/bin/pip install sherpa-onnx numpy
# 下載並解壓：
#   https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2
#   https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2
# 回頭辨識用 SenseVoice：
#   https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
```

測試句（簡體、繁體、台灣用詞、中英混排、英文、長句）與模型設定寫在
`app/src/debug/java/com/ping/verbead/SherpaTtsBenchReceiver.kt`，Python 版的參數與其一一對應
（Melo：`vits` + `dict_dir` + 四個 fst；Kokoro：`lexicon-us-en.txt,lexicon-zh.txt` + 三個 fst）。
