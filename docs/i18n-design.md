# 多語言化設計（草案）

> 具體的工作順序、環境準備與驗收方式請看 [i18n-plan.md](i18n-plan.md)。這份只放設計理由。最新盤點數字也以該文件與 `node tools/i18n-inventory.js` 為準（版面 201 處、Kotlin 249 行）。

目標：讓介面與語音輸入都能抽換語言，不只服務繁體中文使用者。

這份文件只做設計與分階段計畫，尚未改動任何功能程式碼。數字來自 2026-11-04 對 `main` 的盤點。

## 1. 先分清楚兩條語言軸

多語言化常被混成一件事，其實是兩個獨立的設定：

| | 介面語言（UI language） | 輸入語言（Recognition language） |
|---|---|---|
| 決定什麼 | 設定頁、提示、按鈕、導覽頁的文字 | 用哪個辨識模型、辨識後怎麼處理文字 |
| 誰決定 | 使用者在系統或 App 內選 | 使用者在語音設定選（可多選） |
| 實作 | Android 資源（`values-xx`）加每個 App 的語言設定 | `LanguageProfile`（見第 3 節） |
| 例子 | 日本使用者用日文介面，卻口述中文 | 台灣使用者用中文介面，卻口述日文 |

兩者可以任意組合，所以必須分開設計，不能用「系統語言」同時決定兩件事。

## 2. 介面語言

### 現況（盤點）

- `strings.xml` 只有 99 條，且只有一份預設資源，沒有任何 `values-xx`。
- 版面檔有 **199 處寫死的中文屬性**（`android:text` 等），分布在 28 個檔案；最多的是語音辨識設定 33、懸浮球設定 31、主設定 21、OCR 設定 15。
- Kotlin 有約 **245 行** UI 用的中文字串字面值，分布在 17 個檔案（`ImeSettingsActivity` 41、`ModelConfig` 34、`BubbleSettingsActivity` 32、`SpeechSettingsActivity` 27、`OnboardingActivity` 26…）。`SpokenNumberConverter` 的 37 行是語言規則，不是介面文字，不在此列。
- `AndroidManifest.xml` 的 `android:label` 仍有寫死的中文（新手設置、自定義詞彙）。
- 沒有 `locales_config.xml`，也沒有使用 `AppCompatDelegate.setApplicationLocales`。`minSdk 26`，`appcompat 1.6.1`，兩者都可直接使用。

### 建議做法

1. **每個 App 的語言設定**：加 `res/xml/locales_config.xml`，Manifest 設 `android:localeConfig`。Android 13 以上會出現在系統設定的「App 語言」；Android 12 以下用 `AppCompatDelegate.setApplicationLocales()` 在設定頁自己提供選單。兩條路徑都由 AppCompat 處理，不需自己存語言偏好。
2. **預設資源怎麼放**（需要你決定，見第 6 節）：
   - 建議 A：`values/` 放英文（任何未支援的語言都退回英文），現有繁中搬到 `values-zh-rTW/`。
   - 建議 B：維持 `values/` 是繁中，新增 `values-en`、`values-ja`、`values-ko`。改動最小，但未支援語言的使用者會看到中文。
3. **字串規則**：
   - 版面與程式一律用 `@string`／`getString`，不拼接句子，需要變數就用位置參數（`%1$s`）。
   - 有數量的用 `plurals`（英文需要，中日韓不需要，但資源要寫對）。
   - 資料層不放介面文字：`ModelConfig.BubbleModeMeta` 的標題與說明、`ModelZipInstaller`／`ModelDownloader` 的錯誤訊息，改成回傳資源 ID 或錯誤代碼，由介面層對應文字。
4. **防止倒退**：打開 Lint 的 `HardcodedText` 設為 error，並加一個單元測試掃描版面與 Kotlin 是否又出現中文字面值（`SpokenNumberConverter` 等規則檔列入白名單）。

## 3. 輸入語言：`LanguageProfile`

目前辨識後的處理幾乎全是中文專用：簡轉繁、台灣用詞、中文標點過濾、口語數字、熱詞（模型提示詞是中文）。要支援多語言，這些要依輸入語言切換，而不是全部套用。

```kotlin
data class LanguageProfile(
    val id: String,                 // "zh-TW" "zh-CN" "en" "ja" "ko"
    val asr: Set<AsrEngineId>,      // 這個語言可用的辨識模型
    val postProcessors: List<PostProcessor>,   // 辨識後依序套用
    val punctuation: PunctuationSet,           // 標點過濾的字元集
    val spokenNumbers: SpokenNumberRules?,     // 該語言的口語數字規則；沒有就不轉
)
```

- **`zh-TW`**：現有行為原樣搬進來（簡轉繁、字元證據判斷、台灣用詞、口語數字）。
- **`zh-CN`**：不做簡轉繁，口語數字規則同中文。
- **`en`、`ja`、`ko`**：不做簡繁轉換；標點集各自定義；口語數字規則另寫（英文是 "twenty-five"，日文用「二十五」且常用漢數字，各自不同，要不要做看需求）。
- 後處理流程由 `LanguageProfile` 決定，`FloatingBubbleService.postProcess` 只負責依序呼叫，不再直接寫死中文規則。
- 混用語言（例如中文夾日文）目前靠「字元證據」處理；多語言後可保留這個機制作為 `zh-TW` 的一部分，不必改。

### 模型選擇

- 熱詞提示詞目前是中文（「热词列表」），非中文語言建議預設關閉熱詞，或之後換成對應語言的提示。這已經有開關可用。
- ASR 模型依語言列表：Qwen3-ASR 官方稱支援 52 種語言（含日、韓、英），現有 0.6B 可先涵蓋；是否另加 SenseVoice、Moonshine 或其他，等評測結果再決定（見之前的討論）。

## 4. 分階段計畫

| 階段 | 內容 | 風險 |
|---|---|---|
| P0 基礎建設 | `locales_config.xml`、語言選單、`HardcodedText` Lint、掃描測試、決定預設資源位置 | 低 |
| P1 字串抽出 | 28 個版面（199 處）與 17 個 Kotlin 檔（約 245 行）改用資源；資料層改回傳資源 ID／錯誤代碼 | 中：量大，但可用腳本輔助、逐檔驗證 |
| P2 翻譯 | 先英文，再日、韓；機器翻譯後人工校對，注意長度（懸浮球、膠囊選單空間小） | 中 |
| P3 `LanguageProfile` | 把中文專用後處理收進 `zh-TW` profile；設定頁新增輸入語言選擇 | 中 |
| P4 辨識整合 | 依輸入語言選模型、處理熱詞；視評測結果擴充模型 | 取決於模型選擇 |
| P5 QA | 偽在地化（把字串換成加長的假字）檢查版面溢出；各語言實機走一遍 | 低 |

P0 與 P1 不依賴任何模型決策，可以立刻開始；P3 與 P4 要等你決定要支援哪些輸入語言。

## 5. 不在這份設計內

- 手錶端（Pebble）的文字：裝置字型不含中日韓字元，錯誤訊息本來就是英文短句；多語言化時維持英文，不納入翻譯。這個手錶程式也還在另一個分支，沒合進 `main`。
- OCR 的辨識語言（PP-OCRv6 的字典目前針對繁中）：屬於輸入語言軸，等 P3 一併評估。

## 6. 需要你決定的事

1. **預設資源放哪**：建議 A（英文當預設，繁中搬到 `values-zh-rTW`）或 B（維持繁中為預設）。
2. **介面語言範圍**：第一批做哪些？建議繁中、英文，之後再加日、韓。
3. **輸入語言範圍**：第一批支援哪些？這決定 P3 要寫幾套 profile，以及口語數字要不要做非中文版本。
4. **翻譯來源**：機器翻譯加你自己校對，或有其他人可以協助？
