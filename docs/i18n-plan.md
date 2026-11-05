# 多語言化改造：工作計畫

這份計畫是寫給「換一台裝置、換一個對話也能直接接手」的人（或 AI）看的。設計背景與理由在 [i18n-design.md](i18n-design.md)，這份只談**怎麼做、照什麼順序、怎麼驗收**。

接手方式：先做第 1 節的環境準備，再回報第 4 節的決定，然後從第 5 節的工作包依序做，每包獨立 commit。

## 0. 起始狀態

- 基準：`main` 分支（改造從這裡開始）。
- 另有兩個不要動的分支：
  - `snapshot/before-slm-removal`：移除 Qwen3.5 改寫功能前的完整快照，只留作備份。
  - `ccr-66958801-6cx895`：Pebble 手錶語音輸入，還沒合進 `main`，不在這次改造範圍。
- 專案：Android，`minSdk 26`、`targetSdk 34`、`compileSdk 35`、Kotlin、AppCompat 1.6.1、Gradle wrapper 8.14.5。
- 目前沒有任何多語言基礎設施：沒有 `values-xx`、沒有 `locales_config.xml`、沒有語言切換。

## 1. 在新裝置上準備環境（必讀）

有幾個檔案不在 git 裡，沒備齊就編不過。

1. **JDK**：用 Android Studio 內建的 JBR（JDK 21 左右）。指令列建置前先設 `JAVA_HOME`，例如 Windows：`C:\Program Files\Android\Android Studio\jbr`。
2. **Android SDK**：需要 platform 35（`platforms/android-35`）與對應 build-tools。
3. **`local.properties`**（已在 `.gitignore`）：在專案根目錄建立，內容一行 `sdk.dir=<SDK 路徑>`，路徑用正斜線，例如 `sdk.dir=C:/Users/<你>/AppData/Local/Android/Sdk`。
4. **`app/libs/` 的兩個 AAR**（`.gitignore` 排除了 `app/libs/*.aar`，所以 clone 下來是空的）：
   - `sherpa-onnx-2.0.1.aar`：從 <https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1> 下載同名檔案（約 47MB）。
   - `zxingcpp-release.aar`：**不在上游 release 裡，我不知道它的出處**。請從原本的開發機複製 `app/libs/zxingcpp-release.aar`。
   - 第一次建置時，`build.gradle` 的 `alignSherpaAar` 任務會**就地修改** `sherpa-onnx-2.0.1.aar`（把符號版本改成 `VERS_1.28.0`，配合 Microsoft onnxruntime 1.28.0）。這是預期行為；不要把 AAR 加進 git。
5. **建置與測試**（在專案根目錄）：

   ```bash
   ./gradlew assembleDebug testDebugUnitTest --no-daemon
   ```

   APK 在 `app/build/outputs/apk/debug/app-debug.apk`，debug 版 package 是 `com.ping.verbead.debug`。
6. **安裝到手機**：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。設定頁沒有開放給外部啟動（`exported=false`），要用 adb 從主畫面點進去，不能直接 `am start` 子頁面。
7. **行尾**：很多檔案是 CRLF，部分檔案是混合行尾。用腳本改檔時要保留原行尾（先偵測 `\r\n`、改完再還原）；git 會顯示「LF 將被換成 CRLF」的警告，不影響內容，可以忽略。

## 2. 目標

分兩條獨立的軸（細節見 [i18n-design.md](i18n-design.md)）：

- **介面語言**：設定頁、提示、導覽頁等文字，用 Android 資源（`values-xx`）加每個 App 的語言設定。
- **輸入語言**：辨識模型與辨識後的文字處理（簡轉繁、標點、口語數字…）依語言切換，由 `LanguageProfile` 決定。

驗收（整件事做完）：
1. 在系統設定或 App 內切換介面語言，所有畫面、提示、通知、Toast 都跟著換，沒有殘留中文。
2. 輸入語言可獨立選擇，辨識後的處理依所選語言走，中日韓混用的現有行為不退步。
3. `node tools/i18n-inventory.js` 的「待處理合計」為 0（語言規則檔除外）。
4. 防倒退檢查（P0-C）會擋住新增的寫死文字。

## 3. 起始盤點

用 `node tools/i18n-inventory.js` 重現。**起始基準：待處理合計 452。**

| 類別 | 數量 | 檔案數 |
|---|---|---|
| 版面寫死的 `android:text/hint/contentDescription/title/label` | 201 | 28 |
| Kotlin 含中文的字串字面值行（不含語言規則檔） | 249 | 17 |
| Manifest 寫死的 `android:label` | 2 | — |
| 現有 `res/values/strings.xml` | 99 條 | 1 |

版面（由多到少）：`activity_settings_speech` 33、`activity_settings_bubble` 31、`activity_settings` 23、`activity_settings_ocr` 15、`activity_settings_about` 11、`activity_dict_settings` 8，其餘 22 個檔案各 1～7。

Kotlin（由多到少）：`ImeSettingsActivity` 44、`engine/ModelConfig` 34、`settings/BubbleSettingsActivity` 32、`settings/SpeechSettingsActivity` 27、`OnboardingActivity` 26、`bubble/OcrOverlayController` 14、`settings/OcrSettingsActivity` 14、`DictSettingsActivity` 11、`engine/ModelZipInstaller` 11、`FloatingBubbleService` 8、`engine/ModelDownloader` 7、`QuickPhrasesManager` 6、`ocr/PpOcrEngine` 5、`UserDictionary` 4，其餘各 2。

`engine/SpokenNumberConverter.kt` 的中文是語言規則，不是介面文字，已在盤點工具裡排除；`ocr/TextStitcher`、`util/TextInsertion` 各有 2 行，做到 P1-6 時逐行判斷是不是標點規則。

## 4. 要先拍板的決定

下面四項不決定，P0-B 以後的工作會卡住。每項附建議；**沒有回覆時照建議做**，並把結果記進這份文件的第 9 節。

| # | 決定 | 選項 | 建議 |
|---|---|---|---|
| D1 | 預設資源放哪 | A：`values/` 放英文，現有繁中搬到 `values-zh-rTW/`；B：`values/` 維持繁中，另加 `values-en` 等 | **A**。沒支援的語言會退回英文而不是中文。P1 一律把新字串加進 `values/strings.xml`，兩個選項都相容；選 A 的話，等 P2 開始前再把這份檔案整份搬到 `values-zh-rTW/` 並在 `values/` 放英文 |
| D2 | 第一批介面語言 | 繁中、英文；之後日、韓 | 繁中＋英文，日、韓列在 P2 後段 |
| D3 | 第一批輸入語言 | 繁中、簡中、英、日、韓 | 繁中、英、日、韓（簡中先沿用繁中的處理但不做簡轉繁）；是否為每個語言寫口語數字規則，看需求再決定 |
| D4 | 翻譯來源 | 機器翻譯＋人工校對，或他人協助 | 機器翻譯出初稿，由使用者校對；先做術語表（第 6 節）確保一致 |

## 5. 工作包（依序進行，每包獨立 commit）

分支建議命名 `i18n/<工作包>`，例如 `i18n/p1-1-small-layouts`，完成後合回 `main`。

### P0 基礎建設（不依賴 D1 以外的決定）

**P0-B 語言切換與資源骨架**
- 新增 `res/xml/locales_config.xml`，列出支援語言（先 `zh-TW`、`en`，日、韓之後加）；`AndroidManifest.xml` 的 `<application>` 加 `android:localeConfig="@xml/locales_config"`。
- 新增 `AppLocale` 小工具（`setApplicationLocales` 與讀取目前語言），在 `activity_settings_about.xml` 加一列「介面語言」（選單：跟隨系統、繁體中文、English…）。
- API 33 以上由系統負責；API 26～32 用 AppCompat 的 `AppLocalesMetadataHolderService`（在 Manifest 宣告 `autoStoreLocales`）保存語言。
- **服務與覆蓋視窗要另外處理**（見第 8 節陷阱 1）：`FloatingBubbleService`、`ModelDownloadService`、`VoiceAccessibilityService` 內用 `getString` 或自己建立的 `ContextThemeWrapper`，在 API 33 以下不會自動跟著 App 語言，要包一層帶語言的 Context。
- Manifest 的 2 個寫死 `android:label`（「新手設置」、「自定義詞彙」）改成 `@string`。
- 完成條件：英文與繁中各有一份 `strings.xml`（內容暫時相同也可），切換語言後設定頁會重建；`assembleDebug` 與測試通過。

**P0-C 防倒退**
- 在 `app/build.gradle` 的 `lint` 設定把 `HardcodedText` 設為 `error`，並產生 `lint-baseline.xml` 記錄現有的違規（之後每個工作包要讓 baseline 變小）。
- 新增單元測試 `NoHardcodedCjkTest`：掃描 `res/layout`、`res/menu` 與 Kotlin 原始碼，找出寫死的中日韓字；現有違規列在 `tools/i18n-allowlist.txt`（每行一個檔案與數量），**只許減少、不許增加**；語言規則檔（`SpokenNumberConverter`）列為永久例外。
- 完成條件：新增一個寫死的中文字串時測試會失敗；現有狀態測試通過。

### P1 字串抽出（機械性工作，量大）

共同做法：把寫死文字搬進 `res/values/strings.xml`，版面改 `@string/key`，Kotlin 改 `getString(R.string.key)`。細節規則在第 6 節。每個工作包完成後重跑盤點工具，數字要減少對應的量。

| 包 | 範圍 | 量 | 說明 |
|---|---|---|---|
| **P1-1** | 17 個小型版面：`activity_dict_settings` 8、`layout_floating_ocr` 7、`layout_floating_scanner` 6、`layout_ocr_snapshot` 6、`activity_settings_backup` 5、`item_bubble_mode_config` 5、`layout_capsule_menu` 4、`dialog_add_entry` 3、`item_quick_history` 3、`item_quick_phrase` 3、`layout_floating_phrases_drawer` 3、`dialog_phrase_entry` 2、`item_dict_entry`、`layout_bubble_delete_zone`、`layout_bubble_x`、`layout_floating_bubble`、`layout_floating_preview` 各 1 | 版面 60 | 最安全的起手式，用來驗證做法與工具 |
| **P1-2** | 5 個大設定版面：`activity_settings_speech` 33、`activity_settings_bubble` 31、`activity_settings` 23、`activity_settings_ocr` 15、`activity_settings_about` 11 | 版面 113 | 這些畫面實機走一遍確認 |
| **P1-3** | 設定頁 Kotlin：`ImeSettingsActivity` 44、`BubbleSettingsActivity` 32、`SpeechSettingsActivity` 27、`OcrSettingsActivity` 14、`DictSettingsActivity` 11 | Kotlin 128 | 大量是狀態文字與用字串樣板組出來的句子，要改成帶參數的格式字串 |
| **P1-4** | 導覽頁：`OnboardingActivity` 26，以及 6 個 `layout_onboarding_slide_*` 與 `activity_onboarding` | Kotlin 26＋版面 28 | 導覽頁文字長、動畫與寬度敏感，要實機看排版 |
| **P1-5** | 資料層解耦：`engine/ModelConfig` 34、`engine/ModelZipInstaller` 11、`engine/ModelDownloader` 7 | Kotlin 52 | **不是單純搬字串**：資料層不應該持有介面文字。`BubbleModeMeta` 改存資源 ID；匯入與下載的結果改回傳結果代碼，由介面層對應成文字 |
| **P1-6** | 其餘：`bubble/OcrOverlayController` 14、`FloatingBubbleService` 8、`QuickPhrasesManager` 6、`ocr/PpOcrEngine` 5、`UserDictionary` 4、`ModelDownloadService` 2、`ocr/TextStitcher` 2、`util/TextInsertion` 2，加上 Manifest 的 2 個 label（若 P0-B 尚未處理） | 45 | 服務裡的 Toast、通知、預覽氣泡文字；`TextStitcher`、`TextInsertion` 先判斷是不是規則 |

### P2 翻譯

- 先建術語表（第 6 節），再翻譯：英文為第一優先，之後日文、韓文（依 D2）。
- 若 D1 選 A：這時才做 `values/` ↔ `values-zh-rTW/` 的對調（現有繁中整檔搬到 `values-zh-rTW/strings.xml`，`values/` 改放英文），並檢查沒有任何字串只存在其中一份（Lint 的 `MissingTranslation` 設為 error）。
- 有數量的句子用 `<plurals>`；注意狹小空間（懸浮球、膠囊選單、通知）的譯文長度。
- 完成條件：每個支援語言的 `strings.xml` 與預設資源的 key 完全一致。

### P3 `LanguageProfile`（輸入語言處理）

- 新增 `LanguageProfile`（見設計文件第 3 節），把目前中文專用的後處理收進 `zh-TW` profile：`ModelConfig.toTaiwanTraditional` 與字元證據判斷、`normalizeTaiwanVariants`、`applyPostRegexReplacements`、`filterChinesePunctuation`、`SpokenNumberConverter`。
- `FloatingBubbleService.postProcess` 改成只依所選 profile 依序呼叫，不再直接寫死中文規則。
- 設定頁新增「輸入語言」選項（獨立於介面語言）。
- 原有行為（含中日韓混用）以現有測試鎖住，**重構前後測試結果必須一致**。

### P4 辨識整合

- 依輸入語言選擇辨識模型；非中文預設關閉熱詞（熱詞提示詞是中文）或換成對應語言。
- 評估是否加入其他模型（SenseVoice、Moonshine 等），依評測結果決定，見先前的討論。

### P5 品質與驗收

- Debug 版開啟偽在地化（`pseudoLocalesEnabled true`，在系統裡選 `en-XA` 或 `ar-XB`），找出被截斷或溢出的版面與漏掉的寫死文字。
- 每個支援的介面語言用手機走完主要畫面並截圖：主設定頁、各子設定頁、導覽頁、懸浮球與膠囊選單、OCR 與條碼掃描視窗、常用語抽屜、各種 Toast 與錯誤提示。
- 通知文字、無障礙描述（`contentDescription`）、桌面圖示名稱也要檢查。

## 6. 慣例

**Key 命名**：`<畫面或元件>_<元素>_<種類>`，全小寫加底線，例如 `settings_speech_hotwords_title`、`bubble_toast_duplicated`。已存在的 key 不改名。

**抽出時的規則**
- 不拼接句子：「已下載 3/4 個離線模型」要寫成一條格式字串 `已下載 %1$d/%2$d 個離線模型`，不要把數字和文字分開翻。
- 一律用位置參數（`%1$s`、`%2$d`）；Kotlin 的 `$變數` 或 `${...}` 都要換掉。
- XML 跳脫：`&` → `&amp;`、`<` → `&lt;`、`'` → `\'`、換行寫 `\n`；現有 `strings.xml` 有些條目用了 `formatted="false"`，新增時不要沿用，除非字串真的含 `%` 但不是格式。
- 不翻譯：產品與模型名稱（Qwen3-ASR、X-ASR、PP-OCRv6、sherpa-onnx）、副檔名、單位縮寫。可以放進一條 `translatable="false"` 的字串。
- 同一句話出現在多處時共用一條，不要重複抽。

**術語表（初稿，翻譯前請確認）**

| 繁體中文 | English |
|---|---|
| 懸浮球 | floating bubble |
| 多球並存 | multiple bubbles |
| 貼邊／收納 | dock to the edge / tuck away |
| 膠囊選單 | capsule menu |
| 語音輸入 | voice input |
| 條碼掃描 | barcode scanning |
| 文字辨識（OCR） | text recognition (OCR) |
| 常用語 | quick phrases |
| 自訂詞彙 / 熱詞 | custom vocabulary / hotwords |
| 離線模型 | offline model |
| 無障礙服務 | accessibility service |
| 轉換為繁體中文 | convert to Traditional Chinese |
| 口語數字轉阿拉伯數字 | convert spoken numbers to digits |
| 盤古之白 | spacing between CJK and Latin text |
| 復原（Undo） | undo |

## 7. 每個工作包的標準流程

1. 開分支，做完該包的範圍。
2. `./gradlew assembleDebug testDebugUnitTest --no-daemon` 通過。
3. `node tools/i18n-inventory.js`：待處理合計要比開始前少，且減少的量等於該包的量。
4. 版面類：安裝到手機，用 adb 截圖逐頁比對改前改後（文字、換行、截斷都不能變）。可以用 `adb -s <序號> exec-out screencap -p > shot.png`。
5. P0-C 完成後，同步更新 `lint-baseline.xml` 與 `tools/i18n-allowlist.txt`（只減不增）。
6. 一個工作包一個 commit（或一個分支），commit 訊息說明範圍與剩餘量。
7. 更新第 9 節的進度表。

## 8. 已知陷阱

1. **服務與覆蓋視窗的語言**：API 33 以下，`Service` 與 `WindowManager` 覆蓋視窗使用的 Context 不會自動跟著 App 內設定的語言。`FloatingBubbleService` 的 `themedCtx`（`ContextThemeWrapper`）、`LayoutInflater.from(themedCtx)` 載入的版面、Toast 都要確認走帶語言的 Context。這是最容易「設定頁是英文、懸浮球還是中文」的地方。
2. **資料層持有文字**：`ModelConfig` 的 `BubbleModeMeta` 等把介面文字寫在 `object` 裡，切換語言時不會更新；P1-5 要改成存資源 ID。
3. **在地化敏感的程式**：`isJapaneseOrKorean`、簡轉繁、台灣用詞規則、中文標點過濾、口語數字都是中文專用，**屬於輸入語言軸（P3），不要在 P1 當成介面文字去翻譯**。
4. **版面長度**：英文通常比中文長 2 到 3 倍；懸浮球的預覽氣泡、膠囊選單的 `contentDescription`、通知與狹窄的狀態列要特別驗收。
5. **字串被拿來比對**：搜尋看看有沒有程式用顯示文字做判斷（`==`、`contains`、`when` 的字串分支）；抽出後那個判斷會失效，要改成用代碼比對。
6. **資源目錄命名**：語言目錄是 `values-en`、`values-ja`、`values-ko`、`values-zh-rTW`；現有的 `values-night`、`values-v31`、`values-night-v31` 只放顏色與主題，不要放字串。
7. **Pebble 手錶端**：不在這次範圍；裝置字型沒有中日韓字元，錯誤訊息本來就是英文。

## 9. 進度與決定記錄

**決定記錄**（有回覆就填，沒回覆照第 4 節建議）

- D1 預設資源：A（照建議；`values/` ↔ `values-zh-rTW/` 的對調留到 P2 開始前）
- D2 第一批介面語言：繁中、英文（照建議）
- D3 第一批輸入語言：繁中、英、日、韓（照建議）
- D4 翻譯來源：機器翻譯初稿＋使用者校對（照建議）

**進度**

- [x] P0-B 語言切換與資源骨架（程式已寫好，但開發環境沒有 Android SDK／AAR，尚未編譯與實機驗證；`values-en` 留到 P2 一併建立）
- [x] P0-C 防倒退（lint `HardcodedText`=error 與 `NoHardcodedCjkTest`、`tools/i18n-allowlist.txt` 已加；已編譯、測試通過；`app/lint-baseline.xml` 已產生（HardcodedText 207）；allowlist 以 `node tools/i18n-allowlist-gen.js` 更新）
- [x] P1-1 小型版面（60；盤點 450→390，lint baseline HardcodedText 207→147；已安裝到手機，尚未逐頁截圖比對）
- [x] P1-2 大型設定版面（113；盤點 390→277，lint baseline HardcodedText 147→34；主設定頁已實機確認，其餘子頁尚未逐頁截圖）
- [x] P1-3 設定頁 Kotlin（128；盤點 277→149；主設定頁已實機確認，其餘四個子設定頁的對話框與 Toast 尚未逐一觸發）
- [x] P1-4 導覽頁（54；盤點 149→95，lint baseline HardcodedText 34→6；已編譯安裝，導覽頁排版尚未實機確認）
- [x] P1-5 資料層解耦（實際減少 30；盤點 95→65。`ModelConfig` 內 22 行台灣用詞規則屬輸入語言規則，留到 P3，不在此包處理；已編譯、單元測試與 lint 通過並安裝，匯入模型包與下載進度的畫面尚未實機確認）
- [x] P1-6 其餘與 Manifest（實際減少 36；盤點 65→29；Manifest 的 label 已在 P0-B 處理。剩下 29 行全是語言規則，留到 P3 判斷：`ModelConfig` 台灣用詞規則 22、`UserDictionary` 的簡繁關鍵字與匯入表頭相容 3、`TextStitcher` 清單序號標點 2、`TextInsertion` 復原/重做多語關鍵字 2；已編譯、測試與 lint 通過並安裝，懸浮球與 OCR 視窗的畫面尚未實機確認）
- [x] P2 英文翻譯（`values/` 改放英文、繁中整檔搬到 `values-zh-rTW/`；Lint `MissingTranslation`、`ExtraTranslation` 已設為 error，建置與 lint 通過；機器翻譯初稿，待使用者校對與實機檢視文字長度；日文、韓文尚未做）
- [x] P2 日文翻譯（新增 `values-ja/`，`AppLocale` 與 `locales_config.xml` 加入 `ja`，介面語言選單多「日本語」；機器翻譯初稿，待校對；繁中版已拿掉「珠璣 (Verbead)」的英文括號）
- [x] P2 韓文翻譯（新增 `values-ko/`，`AppLocale`、`locales_config.xml` 與介面語言選單加入 `ko`；機器翻譯初稿，待校對。P2 的英、日、韓三個介面語言到此完成）
- [x] P3 `LanguageProfile`（新增 `engine/lang/`：`LanguageProfile` 介面、`ZhTwProfile`、`ZhCnProfile`、`PlainProfile`（英/日/韓），台灣用詞與簡轉繁規則搬到 `ZhTwRules`；`FloatingBubbleService.postProcess` 改依所選 profile 呼叫；語音設定頁新增「輸入語言」；既有 35 個測試的斷言未改動且全數通過；OCR 的簡轉繁仍直接呼叫 `ZhTwRules`，留到 P4 評估；盤點剩 7 行，都是 `UserDictionary` 的匯入表頭相容、`TextStitcher` 清單序號、`TextInsertion` 復原關鍵字等多語規則，視為永久例外）
- [x] P4 辨識整合（輸入語言決定實際引擎：非中文一律走 Qwen3-ASR，不提供 X-ASR 與雙引擎，且不改動使用者原本的選擇；非中文不送熱詞，詞庫頁的熱詞開關鎖住並說明原因；Qwen3 的熱詞提示詞在 sherpa-onnx 原生函式庫內且沒有語言參數，無法換成對應語言，所以直接關閉。**新增辨識模型（SenseVoice、Moonshine 等）尚未評估**：需要實機與測試語料的評測結果，這次沒有做）
- [x] 後續調整：移除「輸入語言」選項，P3/P4 的按語言切換邏輯隨之拿掉（引擎與熱詞不再依語言限制，簡轉繁、口語數字等開關回到單一全域設定；`ZhTwProfile` 保留為辨識後處理，不再有語言註冊表）。X-ASR 在介面中標明針對繁體中文優化；其他語言要補哪些模型，待使用者後續決定
- [x] 語言標籤系統（`engine/lang/LanguageTag.kt`：`LanguageTag` 列舉加 `ModelLanguages` 登記表，X-ASR 標「繁體中文」、Qwen3-ASR 標「多語言」；語音設定頁的引擎卡片標題下顯示標籤小膠囊。新增模型時只要在 `ModelLanguages` 登記標籤；`supports()` 與 `enginesFor()` 可供之後依語言挑選或建議引擎）
- [ ] P5 品質與驗收（進行中；已完成：Debug 版開啟偽在地化 `pseudoLocalesEnabled`；用 en-XA 偽在地化與真正的英文走過主設定頁、語音、懸浮球、OCR 子頁與導覽頁，日文看過主設定頁、語音、懸浮球與 OCR 子頁，韓文看過主設定頁、語音與懸浮球子頁；已修：主設定頁備份卡片與狀態標籤擠壓（標籤加 `maxWidth`）、懸浮球模式清單說明被截斷（縮短三語說明並加 `ellipsize`）、導覽第 2 頁提示被膠囊遮住、英文相機權限標題與數值相撞、日文備份卡片過長、日文「切り替え」按鈕換行被裁。**尚未檢查**：日文與韓文的導覽頁、韓文 OCR 頁、關於頁、詞庫頁、懸浮球與膠囊選單、OCR 與條碼掃描視窗、常用語抽屜、各種 Toast 與通知、桌面圖示名稱）
- [ ] P4 辨識整合
- [ ] P5 品質與驗收

## 10. 範圍外，不要動

- 手錶（Pebble）相關程式與分支。
- `SpokenNumberConverter` 的規則內容（只在 P3 搬位置，不改行為）。
- 辨識模型與 sherpa-onnx 版本。
- 與多語言無關的功能修改，請另開分支。
