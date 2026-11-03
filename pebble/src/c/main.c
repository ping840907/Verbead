/*
 * Verbead Voice — Pebble watchapp
 *
 * 按下 SELECT 說話：手錶麥克風以 mic_stream_to_phone_*() 串流到手機上的 PebbleKit JS，
 * 由 src/pkjs/index.js 轉送給珠璣 (Verbead) App 即時辨識並填入手機的焦點文字框。
 * 辨識進度與結果再經 AppMessage 回到這裡顯示。
 *
 * 需要 Pebble OS 4.43+ 與 SDK revision 110+（mic_stream_to_phone_*），
 * 目前支援的機型：emery (Pebble Time 2)、flint (Core 2 Duo)、gabbro (Core Time Round)。
 */

#include <pebble.h>

// 與 src/pkjs/index.js 的 STATUS 必須一致
typedef enum {
  StatusIdle = 0,
  StatusConnecting,
  StatusListening,
  StatusProcessing,
  StatusDone,
  StatusError,
  StatusNeedsPairing,
  StatusNeedsPermission,
  StatusDoneClipboard,
} Status;

// 手錶 → 手機 (MESSAGE_KEY_CMD)
#define CMD_CANCEL 1

#define PADDING PBL_IF_ROUND_ELSE(18, 6)
#define STATUS_HEIGHT 30
#define HINT_HEIGHT 20
// 手機沒有回應的保險時限
#define RESPONSE_TIMEOUT_MS 25000
// 啟動後稍等 JS 與視窗就緒再開麥克風
#define AUTO_START_DELAY_MS 400

static Window *s_window;
static TextLayer *s_status_layer;
static TextLayer *s_text_layer;
static TextLayer *s_hint_layer;

static char s_status_buf[24];
static char s_text_buf[160];
static char s_hint_buf[64];

static bool s_streaming;       // 麥克風串流中（含等待手機接受）
static bool s_auto_started;
static AppTimer *s_watchdog;

// ---------------------------------------------------------------------------
// 顯示
// ---------------------------------------------------------------------------

static const char *prv_status_name(Status status) {
  switch (status) {
    case StatusIdle: return "Ready";
    case StatusConnecting: return "Connecting...";
    case StatusListening: return "Listening";
    case StatusProcessing: return "Processing...";
    case StatusDone: return "Typed";
    case StatusDoneClipboard: return "Copied";
    case StatusError: return "Error";
    case StatusNeedsPairing: return "Pair first";
    case StatusNeedsPermission: return "Allow access";
  }
  return "";
}

static const char *prv_default_text(Status status) {
  switch (status) {
    case StatusIdle: return "";
    case StatusListening: return "Speak now. It stops by itself when you pause.";
    case StatusDone: return "";
    case StatusDoneClipboard: return "Not typed: no text field was focused. Copied to the clipboard.";
    case StatusNeedsPairing: return "Open this app's settings in the Core Devices app, after tapping Pair in Verbead.";
    case StatusNeedsPermission: return "Allow Local Network for this app in the Core Devices app, then try again.";
    default: return "";
  }
}

static const char *prv_hint_for(Status status) {
  if (s_streaming) {
    return "SELECT: done   BACK: cancel";
  }
  switch (status) {
    case StatusConnecting:
    case StatusProcessing:
      return "";
    default:
      return "SELECT: talk";
  }
}

static GColor prv_status_color(Status status) {
#if defined(PBL_COLOR)
  switch (status) {
    case StatusListening: return GColorRed;
    case StatusProcessing:
    case StatusConnecting: return GColorOrange;
    case StatusDone: return GColorIslamicGreen;
    case StatusDoneClipboard: return GColorChromeYellow;
    case StatusError:
    case StatusNeedsPairing:
    case StatusNeedsPermission: return GColorRed;
    default: return GColorBlack;
  }
#else
  return GColorBlack;
#endif
}

static void prv_layout(void) {
  if (!s_window) {
    return;
  }
  Layer *root = window_get_root_layer(s_window);
  // 系統的 "Listening" 橫幅會縮小可用區域，一律以未遮擋區域排版
  const GRect bounds = layer_get_unobstructed_bounds(root);
  const int width = bounds.size.w - 2 * PADDING;

  layer_set_frame(text_layer_get_layer(s_status_layer),
                  GRect(PADDING, bounds.origin.y + PADDING, width, STATUS_HEIGHT));

  const int hint_y = bounds.origin.y + bounds.size.h - HINT_HEIGHT - PBL_IF_ROUND_ELSE(14, 2);
  layer_set_frame(text_layer_get_layer(s_hint_layer), GRect(PADDING, hint_y, width, HINT_HEIGHT));

  const int text_y = bounds.origin.y + PADDING + STATUS_HEIGHT + 4;
  int text_h = hint_y - text_y - 2;
  if (text_h < 20) {
    text_h = 20;
  }
  layer_set_frame(text_layer_get_layer(s_text_layer), GRect(PADDING, text_y, width, text_h));
}

static void prv_show(Status status, const char *text) {
  snprintf(s_status_buf, sizeof(s_status_buf), "%s", prv_status_name(status));
  snprintf(s_text_buf, sizeof(s_text_buf), "%s", (text && text[0]) ? text : prv_default_text(status));
  snprintf(s_hint_buf, sizeof(s_hint_buf), "%s", prv_hint_for(status));

  text_layer_set_text_color(s_status_layer, prv_status_color(status));
  text_layer_set_text(s_status_layer, s_status_buf);
  text_layer_set_text(s_text_layer, s_text_buf);
  text_layer_set_text(s_hint_layer, s_hint_buf);
}

// ---------------------------------------------------------------------------
// 逾時保險：按下結束後，手機端一直沒回覆
// ---------------------------------------------------------------------------

static void prv_watchdog_cancel(void) {
  if (s_watchdog) {
    app_timer_cancel(s_watchdog);
    s_watchdog = NULL;
  }
}

static void prv_watchdog_fired(void *context) {
  s_watchdog = NULL;
  prv_show(StatusError, "No response from the phone. Is Verbead running?");
  vibes_double_pulse();
}

static void prv_watchdog_start(void) {
  prv_watchdog_cancel();
  s_watchdog = app_timer_register(RESPONSE_TIMEOUT_MS, prv_watchdog_fired, NULL);
}

// ---------------------------------------------------------------------------
// 麥克風串流
// ---------------------------------------------------------------------------

static const char *prv_start_result_text(MicStreamStartResult result) {
  switch (result) {
    case MicStreamStartErrBusy: return "The microphone is busy.";
    case MicStreamStartErrNotForeground: return "The app is not in the foreground.";
    case MicStreamStartErrNoMemory: return "Not enough memory.";
    case MicStreamStartErrUnavailable: return "This watch has no microphone.";
    case MicStreamStartErrWatchface:
    case MicStreamStartErrInvalidArgs:
    case MicStreamStartOk:
      break;
  }
  return "Could not start the microphone.";
}

// 手機已接受串流，音訊開始流動
static void prv_stream_started(void *context) {
  prv_show(StatusListening, NULL);
}

// 串流因「不是我們呼叫 stop」而結束（失去焦點、被系統或手機中斷…）。呼叫時串流已不存在。
// 已送出的音訊 PKJS 仍會定稿並填入，所以這裡只更新畫面，後續由手機的狀態覆蓋。
static void prv_stream_stopped(MicStreamStopReason reason, void *context) {
  s_streaming = false;
  switch (reason) {
    case MicStreamStopReasonFocusLost:
      prv_show(StatusProcessing, "Interrupted. Finishing what was heard.");
      prv_watchdog_start();
      break;
    case MicStreamStopReasonInterrupted:
      prv_show(StatusProcessing, "Microphone taken by the system. Finishing what was heard.");
      prv_watchdog_start();
      break;
    case MicStreamStopReasonPhone:
      prv_show(StatusError, "The phone refused or lost the audio stream. Check Microphone permission in the Core Devices app.");
      break;
    case MicStreamStopReasonError:
    case MicStreamStopReasonStopped:
      prv_show(StatusError, "Microphone error.");
      break;
  }
}

static void prv_start_listening(void) {
  if (s_streaming || mic_stream_to_phone_is_active()) {
    return;
  }
  prv_watchdog_cancel();
  const MicStreamStartResult result = mic_stream_to_phone_start((MicStreamHandlers) {
    .started = prv_stream_started,
    .stopped = prv_stream_stopped,
  }, NULL);
  if (result != MicStreamStartOk) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "mic_stream_to_phone_start failed: %d", (int)result);
    prv_show(StatusError, prv_start_result_text(result));
    return;
  }
  s_streaming = true;
  prv_show(StatusConnecting, NULL);
}

// 使用者按下完成，或手機（珠璣偵測到說完）要求停止：停止收音並等待定稿
static void prv_stop_listening(void) {
  if (!s_streaming) {
    return;
  }
  s_streaming = false;
  mic_stream_to_phone_stop();
  prv_show(StatusProcessing, NULL);
  prv_watchdog_start();
}

// 完成或失敗時確保麥克風已關閉（mic_stream_to_phone_stop 在未串流時呼叫是安全的）
static void prv_force_stop_stream(void) {
  s_streaming = false;
  mic_stream_to_phone_stop();
}

static void prv_send_cancel(void) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }
  dict_write_int32(iter, MESSAGE_KEY_CMD, CMD_CANCEL);
  app_message_outbox_send();
}

// 取消：通知手機丟棄這次錄音（不填入任何文字），再停止收音
static void prv_cancel_listening(void) {
  prv_send_cancel();
  s_streaming = false;
  prv_watchdog_cancel();
  mic_stream_to_phone_stop();
  prv_show(StatusIdle, "Cancelled");
}

// ---------------------------------------------------------------------------
// 按鍵
// ---------------------------------------------------------------------------

static void prv_select_click(ClickRecognizerRef recognizer, void *context) {
  if (s_streaming) {
    prv_stop_listening();
  } else if (!s_watchdog) {
    prv_start_listening();
  }
}

static void prv_back_click(ClickRecognizerRef recognizer, void *context) {
  if (s_streaming) {
    prv_cancel_listening();
  } else {
    window_stack_pop_all(true);
  }
}

static void prv_click_config(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click);
  window_single_click_subscribe(BUTTON_ID_BACK, prv_back_click);
}

// ---------------------------------------------------------------------------
// AppMessage（手機 → 手錶）
// ---------------------------------------------------------------------------

static void prv_inbox_received(DictionaryIterator *iter, void *context) {
  const Tuple *stop = dict_find(iter, MESSAGE_KEY_STOP);
  const Tuple *status_tuple = dict_find(iter, MESSAGE_KEY_STATUS);
  const Tuple *text_tuple = dict_find(iter, MESSAGE_KEY_TEXT);

  if (status_tuple) {
    const Status status = (Status)status_tuple->value->int32;
    const char *text = (text_tuple && text_tuple->type == TUPLE_CSTRING) ? text_tuple->value->cstring : NULL;

    switch (status) {
      case StatusDone:
      case StatusDoneClipboard:
        prv_watchdog_cancel();
        prv_force_stop_stream();
        prv_show(status, text);
        vibes_short_pulse();
        break;
      case StatusError:
      case StatusNeedsPairing:
      case StatusNeedsPermission:
        prv_watchdog_cancel();
        prv_force_stop_stream();
        prv_show(status, text);
        vibes_double_pulse();
        break;
      case StatusProcessing:
        prv_show(status, text);
        prv_watchdog_start();
        break;
      default:
        prv_show(status, text);
        break;
    }
  }

  // 手機要求停止收音（珠璣偵測到說完、已完成或出錯）。在顯示之後處理，
  // 以免把剛設定好的「完成」畫面又蓋回「處理中」。
  if (stop && s_streaming) {
    prv_stop_listening();
  } else if (stop) {
    // 已不在串流中：確保系統麥克風確實關閉
    mic_stream_to_phone_stop();
  }
}

static void prv_inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "inbox dropped: %d", (int)reason);
}

// ---------------------------------------------------------------------------
// 視窗
// ---------------------------------------------------------------------------

static void prv_unobstructed_did_change(void *context) {
  prv_layout();
}

static void prv_auto_start(void *context) {
  if (!s_streaming) {
    prv_start_listening();
  }
}

static void prv_window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  window_set_background_color(window, GColorWhite);

  s_status_layer = text_layer_create(GRectZero);
  text_layer_set_background_color(s_status_layer, GColorClear);
  text_layer_set_font(s_status_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));
  text_layer_set_text_alignment(s_status_layer, GTextAlignmentCenter);
  layer_add_child(root, text_layer_get_layer(s_status_layer));

  s_text_layer = text_layer_create(GRectZero);
  text_layer_set_background_color(s_text_layer, GColorClear);
  text_layer_set_text_color(s_text_layer, GColorBlack);
  text_layer_set_font(s_text_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24));
  text_layer_set_text_alignment(s_text_layer, GTextAlignmentCenter);
  text_layer_set_overflow_mode(s_text_layer, GTextOverflowModeTrailingEllipsis);
  layer_add_child(root, text_layer_get_layer(s_text_layer));

  s_hint_layer = text_layer_create(GRectZero);
  text_layer_set_background_color(s_hint_layer, GColorClear);
  text_layer_set_text_color(s_hint_layer, GColorDarkGray);
  text_layer_set_font(s_hint_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));
  text_layer_set_text_alignment(s_hint_layer, GTextAlignmentCenter);
  layer_add_child(root, text_layer_get_layer(s_hint_layer));

  prv_layout();
  prv_show(StatusIdle, NULL);

  unobstructed_area_service_subscribe((UnobstructedAreaHandlers) {
    .did_change = prv_unobstructed_did_change,
  }, NULL);
}

static void prv_window_appear(Window *window) {
  // 啟動即開始收音，像按下對講機：開啟 App → 直接說話
  if (!s_auto_started) {
    s_auto_started = true;
    app_timer_register(AUTO_START_DELAY_MS, prv_auto_start, NULL);
  }
}

static void prv_window_unload(Window *window) {
  unobstructed_area_service_unsubscribe();
  prv_watchdog_cancel();
  if (s_streaming) {
    s_streaming = false;
    mic_stream_to_phone_stop();
  }
  text_layer_destroy(s_status_layer);
  text_layer_destroy(s_text_layer);
  text_layer_destroy(s_hint_layer);
  s_status_layer = s_text_layer = s_hint_layer = NULL;
}

static void prv_init(void) {
  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_inbox_dropped(prv_inbox_dropped);
  app_message_open(256, 32);

  s_window = window_create();
  window_set_click_config_provider(s_window, prv_click_config);
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = prv_window_load,
    .appear = prv_window_appear,
    .unload = prv_window_unload,
  });
  window_stack_push(s_window, true);
}

static void prv_deinit(void) {
  app_message_deregister_callbacks();
  window_destroy(s_window);
  s_window = NULL;
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
