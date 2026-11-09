# 珠璣 (Verbead)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)

**珠璣**是一款 Android 本機離線輸入助手。落辭成玉，字字珠璣。

它以桌面懸浮球的形式存在，不必更換你慣用的鍵盤，就能在任何應用程式裡用語音、拍照或螢幕文字辨識、條碼掃描與常用語快速填入文字。語音與文字辨識都在手機本機完成，日常使用不會把你的聲音或畫面送到雲端。

---

## 特色

- **完全本機、重視隱私**：辨識過程不連網，只有第一次下載模型時需要網路。
- **語音輸入**：可選擇即時串流（邊說邊出字）、高精準度辨識，或兩者搭配（說話時預覽、說完後精準定稿），其中即時串流模型針對台灣中文語句微調。
- **文字辨識（OCR）**：可辨識相機畫面或螢幕上的文字，支援直排文字、多區塊依序選取與自動接合斷行，並可選擇安裝韓文辨識模型。
- **條碼掃描**：掃到的內容直接填入。
- **常用語與輸入紀錄**：點一下就填入，常用語可自行排序與編輯。
- **自訂詞庫**：修正專有名詞與常見誤辨，可匯入、匯出 CSV。
- **一鍵復原**：填入後的短時間內，點懸浮球上方的 ✕ 即可還原。
- **懸浮球**：長按展開模式選單，可貼邊、拖曳，並可同時顯示多顆各自負責不同功能的懸浮球。
- **多語介面**：繁體中文、簡體中文、English、日本語、한국어。

---

## 使用方式

1. 安裝後開啟 App，依畫面提示授予麥克風、相機（用到時）、「顯示在其他應用程式上層」權限，並啟用無障礙服務。
2. 到設定頁下載需要的模型（也可從本機的模型壓縮檔匯入）。
3. 在任何應用程式點選輸入框，再點懸浮球開始輸入；長按懸浮球切換語音、條碼、文字辨識與常用語。

> Android 13 以上若系統把「無障礙」或「顯示在其他應用程式上層」反灰，請依 App 內的引導允許受限制的設定。

---

## 開發與建置

需求：Android Studio、JDK 17、Android SDK。

```bash
./gradlew testDebugUnitTest     # 單元測試
./gradlew assembleDebug         # 編譯
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

更完整的功能說明請見 [APP_SPECIFICATION.md](APP_SPECIFICATION.md)。

---

## 授權與致謝

本專案採用 [MIT License](LICENSE)。感謝以下開源專案與模型的作者：

| 專案 / 模型 | 用途 | 授權 |
|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Next-gen Kaldi） | 本機語音辨識執行環境 | Apache 2.0 |
| [X-ASR](https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m)（Luigi） | 即時串流語音模型 | Apache 2.0 |
| [Qwen3-ASR](https://github.com/QwenLM/Qwen3-ASR)（Alibaba Qwen Team） | 高精準度語音模型 | Apache 2.0 |
| [PP-OCRv6](https://huggingface.co/collections/PaddlePaddle/pp-ocrv6)（PaddlePaddle） | 文字偵測與辨識模型 | Apache 2.0 |
| [Korean PP-OCRv5](https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec)（PaddlePaddle） | 選用的韓文文字辨識模型 | Apache 2.0 |
| [LiteRT](https://github.com/google-ai-edge/LiteRT)（Google AI Edge） | 文字辨識的推論執行層 | Apache 2.0 |
| [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) | 條碼與 QR Code 辨識 | Apache 2.0 |
| [ONNX Runtime](https://github.com/microsoft/onnxruntime)（Microsoft） | 語音模型的推論引擎（內含於 sherpa-onnx） | MIT |
| [opencc4j](https://github.com/houbb/opencc4j)（houbb） | 繁簡轉換 | Apache 2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android)（Google） | 介面元件 | Apache 2.0 |
| [Commons Compress](https://commons.apache.org/proper/commons-compress/)（Apache Software Foundation） | 模型壓縮檔解壓縮 | Apache 2.0 |
