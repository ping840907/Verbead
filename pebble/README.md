# Verbead Voice — Pebble 手錶語音輸入

用 **Pebble Time 2**（以及其他有麥克風的新 Pebble）的麥克風說話，聲音即時串流到手機上的
**珠璣 (Verbead)**，在手機本機離線辨識後，直接填入手機目前的焦點文字框。

```
Pebble 手錶                 手機
┌──────────────┐   BLE    ┌────────────────────────┐  ws://127.0.0.1:47831  ┌──────────────────────────┐
│ main.c       │ ───────▶ │ Core Devices App       │ ─────────────────────▶ │ 珠璣 WatchBridgeService  │
│ mic_stream_  │  Speex   │  解碼成 16kHz PCM16     │  PCM（約 100ms 一包）  │  X-ASR / Qwen3-ASR       │
│ to_phone_*() │          │  └ PKJS (src/pkjs)     │ ◀───────────────────── │  → 詞庫/繁體後處理        │
└──────────────┘ ◀─────── └────────────────────────┘  partial / final       │  → 無障礙填入焦點文字框   │
   顯示進度/結果    AppMessage                                               └──────────────────────────┘
```

- 整段路徑只在這支手機內：手錶 → 手機 → 本機辨識，**不經過任何雲端**。
- 辨識模式沿用珠璣設定：X-ASR（邊說邊出字）、Qwen3-ASR（說完一次辨識）、或雙引擎（X-ASR 預覽＋Qwen3 定稿）。
- 後處理（繁體轉換、自訂詞庫、標點過濾）與填入方式（無障礙直接填入，失敗退回剪貼簿）與懸浮球完全相同，
  填入後手機上的懸浮球也會出現 ✕ 撤銷鍵。

## 需求

| 項目 | 需求 |
|---|---|
| 手錶 | Pebble Time 2 (`emery`)；另支援 Core 2 Duo (`flint`)、Core Time Round (`gabbro`) |
| 手錶韌體 | 含麥克風串流 API 的版本（SDK revision ≥ 110；社群回報為 4.43 以上） |
| 手機 App | Core Devices 的 Pebble App，且需支援手錶麥克風串流給 PKJS（社群回報為 1.16.0.1 以上，並開啟 Plugins 預覽） |
| 珠璣 | 已下載 X-ASR 或 Qwen3-ASR 模型；已啟用無障礙服務才能直接填入 |
| 建置 | Core Devices 的 `pebble-tool`（`uv tool install pebble-tool`）與最新 SDK（`pebble sdk install latest`） |

> 韌體／App 版本號來自社群 issue 與 PebbleOS 原始碼，請以 Core Devices 的官方發行說明為準。

## 建置與安裝

```bash
cd pebble
pebble build
pebble install --phone <手機 IP>      # 經 Core Devices App 的開發者連線
# 其他選項：--adb（USB 偵錯）、--cloudpebble、--emulator emery（模擬器，見下）
```

`package.json` 已宣告：

- `capabilities: ["microphone"]`、`usesPermissions: ["WatchMicrophone", "LocalNetwork"]`、`manifestVersion: 2`
- `WatchMicrophone`：使用手錶麥克風。`LocalNetwork`：PKJS 連到手機本機（127.0.0.1）的珠璣。

### 在模擬器試用

SDK 的模擬器內建的 `pypkjs` 會代替 Core Devices App：對宣告了 `WatchMicrophone`（且 manifestVersion 2）的 App 自動授權，
並用同樣的 `audiostreamstart` / `audiostream` / `audiostreamstop` 事件把音訊交給 PKJS。
`pebble emu-mic` 可以送入音調、白噪音或 WAV 檔，`pebble emu-permission WatchMicrophone --deny` 可模擬使用者拒絕。
（見 PebbleOS `docs/reference/local-microphone.md`。）

## 第一次使用（配對）

1. 在手機的 **珠璣 → 手錶輸入 (Pebble)** 打開「啟用手錶輸入」。
2. 在 Core Devices App 對「Verbead」允許 **手錶麥克風** 與 **區域網路**。
3. 在珠璣按「**開始配對**」（2 分鐘內有效）。
4. 立刻到 Core Devices App 開啟「Verbead」的**設定**，在頁面按「**完成配對**」。

配對會把珠璣產生的隨機 token 存進 PKJS；之後每次連線都要帶 token。配對頁只在你按下「開始配對」後的
2 分鐘內提供，而且只會提供一次。想讓已配對的手錶失效：珠璣 →「重設配對碼」。

## 使用

- 開啟手錶上的 **Verbead**：自動開始收音（也可以按 `SELECT`）。
- 對著手錶說話。畫面顯示即時預覽；**停頓後珠璣自動結束**並把文字填入手機的焦點文字框，手錶震動一下。
  （可在珠璣關閉「說完自動結束」，改成手錶按 `SELECT` 結束。）
- `SELECT`：收音中 → 結束並填入；閒置 → 重新開始。
- `BACK`：收音中 → **取消**，丟棄這次錄音、不填入任何文字；閒置 → 離開 App。

畫面狀態：`Listening`（收音中）→ `Processing...`（定稿中）→ `Typed`（已填入）／`Copied`（沒有焦點文字框，已複製到剪貼簿）。

## 通訊協定（PKJS ⇄ 珠璣）

連線：`ws://127.0.0.1:47831/?token=<token>`，只綁定 loopback。

| 方向 | 訊息 | 說明 |
|---|---|---|
| → 珠璣 | `{"type":"start","sampleRate":16000,"format":"pcm16le"}` | 開始一次辨識 |
| → 珠璣 | 二進位 frame | 16 kHz、16-bit、小端、單聲道 PCM |
| → 珠璣 | `{"type":"end"}` | 手錶已停止收音，請定稿並填入 |
| → 珠璣 | `{"type":"cancel"}` | 丟棄，不填入 |
| ← 手錶 | `loading` / `ready` | 模型載入中／開始辨識（載入期間的音訊會先排隊） |
| ← 手錶 | `{"type":"partial","text":…}` | 即時預覽 |
| ← 手錶 | `{"type":"autostop","reason":"silence"\|"max_duration"\|"no_speech"}` | 請手錶停止收音 |
| ← 手錶 | `processing` | 定稿中 |
| ← 手錶 | `{"type":"final","text":…,"injected":bool}` | 完成；`injected=false` 表示改複製到剪貼簿 |
| ← 手錶 | `{"type":"error","code":…,"message":…}` | `models_missing` `load_failed` `no_speech` `no_text` `bad_format` |

單次最長 60 秒。

## 已知限制

- **手錶上的中文顯示**：手錶系統字型不一定包含中文字，預覽文字在手錶上可能顯示成方框；
  這只影響手錶螢幕，**填入手機的文字不受影響**。錯誤與狀態訊息一律用英文。
- 需要 Core Devices App 支援手錶麥克風串流；若手機拒絕，手錶會顯示 `The phone refused or lost the audio stream`。
- 手錶麥克風的音量與頻寬（Speex wideband ≈ 10 kbps）比手機麥克風差，辨識率可能略低。
- 珠璣與手錶橋接各自載入引擎（橋接在閒置 90 秒後釋放），同時使用懸浮球與手錶時記憶體用量較高。
