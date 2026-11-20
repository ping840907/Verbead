# 朗讀（TTS）第 0 階段技術驗證

目的：在寫正式功能之前，先確認「用什麼模型、能不能即時、畫面文字拿不拿得到」。
本文件分成「已在雲端環境驗證」與「必須在手機上驗證」兩部分。

## 一、已驗證的結論

### 1. 社群 tflite TTS 的現況（讀 `google-ai-edge/litert-samples` 原始碼與說明）

| 模型 | 語言 | 大小 | 速度（Pixel 8a，作者自述） | 評估 |
|---|---|---|---|---|
| KittenTTS nano（`text_to_speech_streaming`） | 英文 | 32 MB，8 種聲音，24 kHz | RTF 0.22–0.29，首音 ≈ 370 ms | 最適合即時朗讀，但只有英文 |
| Matcha-TTS（`text_to_speech`） | 英文 | 三個 fp16 tflite + G2P | RTF ≈ 0.8（GPU 聲碼器、CPU 解碼器） | 可用但偏吃力，只有英文 |
| Qwen3-TTS 0.6B（`text_to_speech_lm`） | 含中文等 10 種語言 | 1.9 GB | RTF ≈ 6.7（約 7 倍慢於即時） | **不能即時**，不適合朗讀畫面 |

- 結論：**目前沒有「可即時、支援中文」的 tflite 社群 TTS。**
- 專案內 LiteRT 2.3.2 已使用 `CompiledModel`（見 `PpOcrEngine`），範例用 2.3.0，API 相容，不需升級。
- KittenTTS 的 predictor 與 prosody 兩張圖仍需 `Interpreter` API（變數張量尚未被 `CompiledModel` 接受），聲碼器還要自訂 JNI 做 `ResizeInputTensor`。Matcha 則是固定形狀（256 音素 / 512 幀 ≈ 5.9 秒），要自己做長度規劃與 Euler 迴圈。兩者都需要自行移植 G2P 與主機端流程，並非載入即用。
- Gallery 內建的 TTS 為 `.litertlm`，與社群 `.tflite` 不通用。

### 2. 專案已內建中文 TTS 的執行環境：sherpa-onnx

- App 已使用 sherpa-onnx 2.0.1 AAR；其 Kotlin API（`Tts.kt`）已含 `OfflineTts`，支援 VITS / Matcha / Kokoro / Kitten / Pocket / ZipVoice / Supertonic，並提供 `generateWithCallback`（逐段回傳音訊，可做串流）。
- 官方 `tts-models` 發佈頁有可下載的中文模型（本次實際下載）。

### 3. 在雲端 4 核心 CPU 實測（sherpa-onnx 2.0.2 Python，4 執行緒）

| 模型 | 大小 | 取樣率 | 載入 | RTF | 首音 |
|---|---|---|---|---|---|
| `vits-melo-tts-zh_en` | 183 MB（fp32） | 44.1 kHz，單一聲音 | 3.3 s | 0.20–0.25 | 0.3–0.75 s |
| `kokoro-multi-lang-v1_1` | 409 MB | 24 kHz，103 種聲音 | 1.9 s | 0.26–0.34 | 0.5–0.9 s |

雲端 CPU 與手機不同，**這些數字只代表相對關係，不是手機的速度**，手機請照第二部分實測。

回頭檢驗（合成 → SenseVoice 辨識 → 與原文比對）：

- 簡體與繁體輸入的音長幾乎相同（2.82 s / 2.81 s），辨識結果一致，**繁體字輸入可以直接使用**。「軟體、網路連線、品質」等台灣用詞可正常合成。
- **Kokoro 會直接丟掉大寫英文縮寫**（日誌 `OOV word skipped: 'GPU'`）。Melo 對 `GPU`、`4090` 則可讀出。這代表朗讀前需要文字正規化（縮寫拆字母、數字）。
- 辨識出現的少數差異（「應用程式」→「應用城市」、「請問」→「請溫」）無法分辨是 TTS 發音問題還是辨識誤判，**發音品質必須靠人耳聽**。測試音檔可由手機 bench 產生。
- 注意：Android 的 Kotlin API（2.0.1）把 `dictDir` 標為「unused」，而 Python 的 Melo 範例有使用 `dict_dir`。**分詞行為是否一致要在手機上確認**。

## 二、必須在手機上驗證（已備好探針）

兩個 debug 專用接收器（`app/src/debug/`），只在 debug 版本存在：

> 這兩個檔案是在沒有 Android SDK 的環境撰寫，僅做過 Kotlin 語法檢查，**尚未實際編譯**。若 `assembleDebug` 報錯請回報。

### A. TTS 速度與記憶體：`SherpaTtsBenchReceiver`

1. 取得模型並解壓縮：
   `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2`
   `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2`
2. 放到手機 `Android/data/<套件名>/files/tts/<資料夾名>/`（例如 `adb push`）。
3. 執行並看日誌：

```bash
adb shell am broadcast -n <套件名>/com.ping.verbead.SherpaTtsBenchReceiver \
    --es model melo --es dir vits-melo-tts-zh_en --ei threads 4
adb shell am broadcast -n <套件名>/com.ping.verbead.SherpaTtsBenchReceiver \
    --es model kokoro --es dir kokoro-multi-lang-v1_1 --ei threads 4
adb logcat -s TtsBench
```

輸出：載入時間、記憶體增量（PSS）、每句 RTF 與首音延遲，並在模型資料夾存下 `bench_*.wav` 供試聽。
請同時用 `--ei threads 2`、`--ei threads 4` 比較，並在「語音辨識模型已載入」時再跑一次，觀察記憶體壓力。

### B. 畫面來源探針：`TtsSourceProbeReceiver`

切到要測的 App，選取一段文字，在倒數內執行：

```bash
adb shell am broadcast -n <套件名>/com.ping.verbead.TtsSourceProbeReceiver \
    --ei delay 6 --ez copy true --ez ocr true
adb logcat -s TtsProbe
```

輸出 `SELECTION`（有沒有讀到反白、範圍與內容）、`SUMMARY`（節點數、文字節點數、字數、**畫面覆蓋率**、無文字的大圖片節點數、是否含 WebView、自己的懸浮視窗節點數）、`COPY`（ACTION_COPY 後能否讀剪貼簿）、`OCR`（整頁 OCR 的時間、框數、字數，對照節點樹字數）。

建議測試矩陣：

| 類型 | 例子 |
|---|---|
| 原生 TextView | 設定、聯絡人、系統 App |
| WebView / Chrome | 新聞網頁（選取與不選取） |
| 通訊軟體 | LINE、Telegram 的對話 |
| 閱讀器 | Kindle、Google Play 圖書、PDF 檢視器 |
| 純圖片或截圖 | 相簿中的截圖 |
| 遊戲、Flutter / Canvas | 任一款 |
| 自己的 App | 確認懸浮球節點是否會被讀進去 |

## 三、判斷標準與決策

| 項目 | 通過 | 若不通過 |
|---|---|---|
| 中文 TTS 在目標手機的 RTF | ≤ 0.7（逐句串流不斷音） | 改用較輕模型，或以系統 TTS 為預設 |
| 載入記憶體增量 | 與 ASR 並存不被系統殺掉 | 閒置卸載、不與 ASR 同時常駐 |
| 發音品質（人耳） | 日常句子可接受 | 評估其他聲音或模型 |
| 反白可讀（一般 App） | 原生 TextView 可讀到範圍 | 備案走 ACTION_COPY（看 `COPY` 結果） |
| 反白可讀（Chrome / WebView） | 若讀不到則視為已知限制 | 提示使用者改用「畫面文字」或 OCR 區塊選取 |
| 畫面覆蓋率與 OCR 門檻 | 找出「節點樹文字不足 → 改用 OCR」的合理門檻 | 以實測的覆蓋率與字數訂定 |

## 四、對原規劃的調整（建議）

1. **中文走 sherpa-onnx**：新增 `SHERPA_TTS` 載入器，重用已內建的 native 函式庫；初版先支援 `melo`（輕、單聲）與 `kokoro`（多聲音、較大）。
2. **LiteRT tflite 載入器改為第二階段**，先做英文的 KittenTTS，作為「社群 tflite 模型」的示範與延伸點。因為它需要自行移植 G2P 與兩種 API，工程量不低。
3. 系統 `TextToSpeech` 仍保留為保底。
4. 文字正規化變成必要項：大寫縮寫與數字（Kokoro 會丟字）。
