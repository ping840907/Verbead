/*
 * Verbead Voice — PebbleKit JS 橋接
 *
 * 流程：
 *   手錶麥克風 → Core Devices 手機 App 解碼 → 這裡的 `audiostream` 事件（16kHz PCM16 LE，約 100ms 一包）
 *   → ws://127.0.0.1:47831（珠璣 App 內的本機伺服器）→ 離線辨識 → 填入手機焦點文字框
 *   辨識進度與結果再經 AppMessage 回到手錶顯示。
 *
 * 本檔案刻意使用 ES5 語法，並且不依賴 Pebble 以外的全域物件以外的東西，
 * 方便在 Node 內以假的 Pebble/WebSocket 做整合測試（見 pebble/test）。
 */

var PORT = 47831;
var HOST = '127.0.0.1';

// 與手錶端 src/c/main.c 的 enum 必須一致
var STATUS = {
  IDLE: 0,
  CONNECTING: 1,
  LISTENING: 2,
  PROCESSING: 3,
  DONE: 4,
  ERROR: 5,
  NEEDS_PAIRING: 6,
  NEEDS_PERMISSION: 7,
  DONE_CLIPBOARD: 8
};
var CMD_CANCEL = 1;

// 手錶按下停止後稍等一下再通知珠璣結束，讓「取消」指令有機會先到
var END_GRACE_MS = 300;
// 排隊中的音訊上限：100ms 一包 → 約 20 秒
var MAX_QUEUED_CHUNKS = 200;
// 手錶畫面很小；只顯示最後這麼多個字
var MAX_SHOWN_CHARS = 36;

// 手錶字型不一定有中文字，且 AppMessage 收件匣很小：錯誤一律以代碼對應成英文短句
var ERROR_TEXT = {
  models_missing: 'Install a speech model in Verbead',
  load_failed: 'Speech model failed to load',
  no_speech: 'No speech heard',
  no_text: 'Nothing was recognised',
  bad_format: 'Unsupported audio format',
  busy: 'Phone is busy listening'
};

var ws = null;
var wsOpen = false;
var connecting = false;

var streaming = false;     // 手錶正在串流麥克風
var startSent = false;     // 已對珠璣送出 start
var pendingEnd = false;    // 手錶已停止，待音訊送完後送 end
var serverDone = false;    // 珠璣已回報 final/error
var cancelled = false;
var queue = [];
var endTimer = null;

function log() {
  var args = ['[verbead]'];
  for (var i = 0; i < arguments.length; i++) args.push(arguments[i]);
  console.log(args.join(' '));
}

// ── AppMessage 傳給手錶（一次只能有一則在途）──────────────────────────

var outbox = [];
var sending = false;

function lastChars(text) {
  var chars = Array.from(text || '');
  return chars.length > MAX_SHOWN_CHARS ? '…' + chars.slice(-MAX_SHOWN_CHARS).join('') : chars.join('');
}

function toWatch(msg, coalesce) {
  // 即時預覽只需要最新一則，避免辨識很快時 outbox 越積越多
  if (coalesce && outbox.length && outbox[outbox.length - 1].__coalesce) {
    outbox[outbox.length - 1] = msg;
  } else {
    outbox.push(msg);
  }
  msg.__coalesce = !!coalesce;
  pump();
}

function pump() {
  if (sending || !outbox.length) return;
  var msg = outbox.shift();
  var payload = {};
  for (var k in msg) if (k !== '__coalesce' && msg.hasOwnProperty(k)) payload[k] = msg[k];
  sending = true;
  var attempts = (msg.__attempts || 0) + 1;
  Pebble.sendAppMessage(payload, function () {
    sending = false;
    pump();
  }, function (e) {
    sending = false;
    log('appmessage failed', JSON.stringify(e && e.error ? e.error : e));
    if (attempts < 3) {
      msg.__attempts = attempts;
      outbox.unshift(msg);
    }
    setTimeout(pump, 150);
  });
}

function status(code, text, coalesce) {
  var msg = { STATUS: code };
  if (text !== undefined && text !== null) msg.TEXT = text;
  toWatch(msg, coalesce);
}

function stopWatchMic() {
  toWatch({ STOP: 1 });
}

// ── 與珠璣的連線 ──────────────────────────────────────────────────────

function getToken() {
  try { return localStorage.getItem('token'); } catch (e) { return null; }
}

function setToken(token) {
  try { localStorage.setItem('token', token); } catch (e) { log('cannot store token', e); }
}

function resetSession() {
  startSent = false;
  pendingEnd = false;
  serverDone = false;
  cancelled = false;
  queue = [];
  if (endTimer) { clearTimeout(endTimer); endTimer = null; }
}

function closeSocket() {
  var s = ws;
  ws = null;
  wsOpen = false;
  connecting = false;
  if (s) { try { s.close(); } catch (e) { /* ignore */ } }
}

/** @return true 表示已建立（或建立中）連線 */
function connect() {
  if (wsOpen || connecting) return true;
  var token = getToken();
  if (!token) {
    status(STATUS.NEEDS_PAIRING, 'Pair in settings');
    return false;
  }
  var socket;
  try {
    socket = new WebSocket('ws://' + HOST + ':' + PORT + '/?token=' + encodeURIComponent(token));
  } catch (e) {
    // Core Devices App 在使用者尚未允許「區域網路」時會拒絕建立連線並跳出詢問
    log('WebSocket blocked:', e && e.name ? e.name : e);
    status(STATUS.NEEDS_PERMISSION, 'Allow Local Network');
    return false;
  }
  socket.binaryType = 'arraybuffer';
  ws = socket;
  connecting = true;

  socket.onopen = function () {
    if (ws !== socket) return;
    connecting = false;
    wsOpen = true;
    log('connected');
    sendJson({ type: 'hello' });
    flush();
  };
  socket.onmessage = function (ev) {
    if (ws !== socket || typeof ev.data !== 'string') return;
    var msg;
    try { msg = JSON.parse(ev.data); } catch (e) { return; }
    onServerMessage(msg);
  };
  // 連線失敗（被拒絕、珠璣未啟動、token 錯誤）與中途斷線共用同一個處理。
  // 注意：握手被伺服器以 403 拒絕時，有些 WebSocket 實作只會觸發 error 而不會觸發 close。
  function onDisconnect() {
    if (ws !== socket) return;
    var wasBusy = streaming || (startSent && !serverDone);
    ws = null; wsOpen = false; connecting = false;
    try { socket.close(); } catch (e) { /* ignore */ }
    if (wasBusy) {
      serverDone = true;
      queue = [];
      status(STATUS.ERROR, 'Verbead unreachable');
      stopWatchMic();
    }
  }
  socket.onclose = function () {
    log('closed');
    onDisconnect();
  };
  socket.onerror = function () {
    log('socket error');
    onDisconnect();
  };
  return true;
}

function sendJson(obj) {
  if (ws && wsOpen) ws.send(JSON.stringify(obj));
}

/** 把排隊中的訊息依序送出：start → 音訊 → end */
function flush() {
  if (!wsOpen || cancelled) return;
  if (streaming || queue.length || pendingEnd) {
    if (!startSent) {
      sendJson({ type: 'start', sampleRate: 16000, format: 'pcm16le' });
      startSent = true;
    }
  }
  while (queue.length) ws.send(queue.shift());
  if (pendingEnd && startSent && !serverDone) {
    pendingEnd = false;
    sendJson({ type: 'end' });
  }
}

function onServerMessage(msg) {
  switch (msg.type) {
    case 'loading':
      status(STATUS.CONNECTING, 'Loading model…');
      break;
    case 'ready':
      status(STATUS.LISTENING, '');
      break;
    case 'partial':
      status(STATUS.LISTENING, lastChars(msg.text), true);
      break;
    case 'autostop':
      // 珠璣聽到說完了：請手錶停止收音（手錶端停止後仍會等 final）
      stopWatchMic();
      break;
    case 'processing':
      status(STATUS.PROCESSING, '');
      break;
    case 'final':
      serverDone = true;
      queue = [];
      status(msg.injected ? STATUS.DONE : STATUS.DONE_CLIPBOARD, lastChars(msg.text));
      stopWatchMic();
      break;
    case 'error':
      serverDone = true;
      queue = [];
      status(STATUS.ERROR, ERROR_TEXT[msg.code] || lastChars(msg.message) || 'Error');
      stopWatchMic();
      break;
    default:
      break;
  }
}

// ── 手錶麥克風事件（Core Devices App 提供）────────────────────────────

Pebble.addEventListener('audiostreamstart', function (e) {
  log('audiostreamstart', e.sampleRate, e.format);
  resetSession();
  streaming = true;
  status(STATUS.CONNECTING, '');
  if (!connect()) {
    // 未配對或被擋下：connect() 已把原因顯示在手錶上，這裡只需讓手錶別再收音
    serverDone = true;
    stopWatchMic();
  } else if (wsOpen) {
    flush();
  }
});

// e.data 是 ArrayBuffer：16-bit little-endian 單聲道 PCM，約 100ms
Pebble.addEventListener('audiostream', function (e) {
  if (!streaming || serverDone || cancelled) return;
  if (!(e.data instanceof ArrayBuffer)) return;
  if (queue.length >= MAX_QUEUED_CHUNKS) queue.shift();
  queue.push(e.data);
  if (wsOpen) flush();
});

Pebble.addEventListener('audiostreamstop', function () {
  log('audiostreamstop');
  streaming = false;
  if (cancelled || serverDone) return;
  if (!startSent && !queue.length) return; // 什麼都沒收到
  if (endTimer) clearTimeout(endTimer);
  endTimer = setTimeout(function () {
    endTimer = null;
    if (cancelled || serverDone) return;
    pendingEnd = true;
    flush();
    if (!wsOpen && !connecting) {
      // 連線早已中斷
      status(STATUS.ERROR, 'Verbead unreachable');
      stopWatchMic();
    }
  }, END_GRACE_MS);
});

// ── 手錶 → 手機的指令 ─────────────────────────────────────────────────

Pebble.addEventListener('appmessage', function (e) {
  var cmd = e.payload && e.payload.CMD;
  if (cmd === CMD_CANCEL) {
    log('cancel from watch');
    cancelled = true;
    queue = [];
    pendingEnd = false;
    if (endTimer) { clearTimeout(endTimer); endTimer = null; }
    if (startSent && !serverDone) sendJson({ type: 'cancel' });
    serverDone = true;
  }
});

// ── 啟動 / 配對 ───────────────────────────────────────────────────────

Pebble.addEventListener('ready', function () {
  log('ready');
  if (!getToken()) {
    status(STATUS.NEEDS_PAIRING, 'Pair in settings');
  } else {
    // 先連好，按下說話時就不必等；已連線時再打一次招呼，讓珠璣在此刻預先載入模型
    if (wsOpen) sendJson({ type: 'hello' });
    else connect();
  }
});

// 設定頁由珠璣 App 在本機提供（只有在珠璣「手錶輸入」按下配對後的 2 分鐘內有效）
Pebble.addEventListener('showConfiguration', function () {
  Pebble.openURL('http://' + HOST + ':' + PORT + '/pair');
});

Pebble.addEventListener('webviewclosed', function (e) {
  if (!e || !e.response) return;
  try {
    var data = JSON.parse(decodeURIComponent(e.response));
    if (data && typeof data.token === 'string' && data.token.length >= 16) {
      setToken(data.token);
      closeSocket();
      log('paired');
      status(STATUS.IDLE, 'Paired');
      connect();
    }
  } catch (err) {
    log('bad config response', err);
  }
});
