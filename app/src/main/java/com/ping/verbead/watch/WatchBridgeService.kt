package com.ping.verbead.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ping.verbead.FloatingBubbleService
import com.ping.verbead.ImeSettingsActivity
import com.ping.verbead.QuickPhrasesManager
import com.ping.verbead.R
import com.ping.verbead.VoiceAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Pebble 手錶 → Verbead 的本機橋接。
 *
 * 手錶麥克風的 PCM 由 Core Devices 手機 App 解碼後交給 Pebble App 的 PKJS，
 * PKJS 再經 `ws://127.0.0.1:47831` 送到這裡。本服務不使用手機麥克風，
 * 只負責：接收 → 辨識 → 後處理 → 以無障礙服務填入焦點文字框。
 */
class WatchBridgeService : Service() {

    companion object {
        private const val TAG = "WatchBridgeService"
        private const val NOTIFICATION_ID = 2101
        private const val CHANNEL_ID = "watch_bridge_channel"
        private const val ACTION_STOP = "com.ping.verbead.ACTION_STOP_WATCH_BRIDGE"
        private const val PAIR_PATH = "/pair"

        @Volatile
        var isRunning = false
            private set

        /** 伺服器是否成功綁定埠（false 通常代表埠被其他程式佔用）。 */
        @Volatile
        var isListening = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WatchBridgeService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchBridgeService::class.java))
        }

        @Volatile
        private var instance: WatchBridgeService? = null

        /** 懸浮球被點擊：請手錶停止收音，處理已收到的音訊並填入。 */
        fun requestStopActiveSession() {
            instance?.let { service -> synchronized(service.lock) { service.session?.stopFromPhone() } }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var engines: WatchEngines
    private var server: LocalWebSocketServer? = null

    private val lock = Any()
    private var session: WatchDictationSession? = null
    private var sessionConnection: LocalWebSocketServer.Connection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        engines = WatchEngines(this, scope)
        createChannel()
        startForegroundWithNotification()

        val srv = LocalWebSocketServer(LocalWebSocketServer.DEFAULT_PORT, listener) { Log.i(TAG, it) }
        server = srv
        isListening = srv.start()
        if (!isListening) {
            Log.e(TAG, "Port ${LocalWebSocketServer.DEFAULT_PORT} unavailable; stopping")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WatchBridgePrefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        isListening = false
        if (instance === this) instance = null
        synchronized(lock) {
            session?.cancel()
            session = null
            sessionConnection = null
        }
        server?.stop()
        server = null
        // 釋放引擎要等 mutex（可能正在載入模型），不能卡住主執行緒，因此用獨立 scope 非同步完成
        val engines = engines
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { engines.shutdown() }
        scope.cancel()
        super.onDestroy()
    }

    // ── WebSocket 事件 ────────────────────────────────────────────────

    private val listener = object : LocalWebSocketServer.Listener {

        override fun authorize(request: LocalWebSocketServer.HttpRequest): Boolean {
            val given = request.query["token"] ?: return false
            return LocalWebSocketServer.constantTimeEquals(given, WatchBridgePrefs.token(this@WatchBridgeService))
        }

        override fun httpResponse(request: LocalWebSocketServer.HttpRequest): LocalWebSocketServer.HttpResponse? {
            if (request.path != PAIR_PATH) return null
            // 配對頁只在使用者於設定頁按下「配對」後的短暫時間內提供，且只提供一次，
            // 避免其他 App 趁機從 loopback 讀走 token。
            if (!WatchBridgePrefs.isPairingOpen(this@WatchBridgeService)) {
                return LocalWebSocketServer.HttpResponse(403, "text/plain", "配對未開啟。請在珠璣「手錶輸入」設定中按下「配對手錶」。")
            }
            WatchBridgePrefs.closePairing(this@WatchBridgeService)
            return LocalWebSocketServer.HttpResponse(
                200, "text/html",
                PairingPage.render(WatchBridgePrefs.token(this@WatchBridgeService))
            )
        }

        override fun onOpen(connection: LocalWebSocketServer.Connection) {
            Log.i(TAG, "watch connected")
            preloadEngines()
        }

        override fun onText(connection: LocalWebSocketServer.Connection, text: String) {
            val msg = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return
            }
            when (msg.optString("type")) {
                "hello" -> preloadEngines() // 手錶 App 開啟：先把模型載入記憶體
                "start" -> startSession(connection, msg)
                "end" -> synchronized(lock) { if (sessionConnection === connection) session?.end() }
                "cancel" -> synchronized(lock) {
                    if (sessionConnection === connection) {
                        session?.cancel()
                        session = null
                        sessionConnection = null
                    }
                }
                "ping" -> connection.sendText("""{"type":"pong"}""")
            }
        }

        override fun onBinary(connection: LocalWebSocketServer.Connection, data: ByteArray) {
            synchronized(lock) { if (sessionConnection === connection) session?.feed(data) }
        }

        override fun onClose(connection: LocalWebSocketServer.Connection) {
            Log.i(TAG, "watch disconnected")
            synchronized(lock) {
                if (sessionConnection === connection) {
                    session?.cancel()
                    session = null
                    sessionConnection = null
                }
            }
        }
    }

    private fun preloadEngines() {
        scope.launch {
            try {
                engines.preload()
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Log.w(TAG, "preload failed: ${e.message}")
            }
        }
    }

    private fun startSession(connection: LocalWebSocketServer.Connection, msg: JSONObject) {
        val rate = msg.optInt("sampleRate", 16_000)
        val format = msg.optString("format", "pcm16le")
        if (rate != 16_000 || format != "pcm16le") {
            connection.sendText(
                JSONObject().put("type", "error").put("code", "bad_format")
                    .put("message", "只支援 16kHz PCM16 單聲道").toString()
            )
            return
        }
        // 與懸浮球共用辨識器：手機自己正在錄音／辨識時，不能同時解碼
        if (FloatingBubbleService.instance?.isVoiceBusyForWatch == true) {
            connection.sendText(
                JSONObject().put("type", "error").put("code", "busy")
                    .put("message", "手機正在使用語音輸入").toString()
            )
            return
        }
        synchronized(lock) {
            session?.cancel()
            lateinit var created: WatchDictationSession
            created = WatchDictationSession(
                context = this,
                engines = engines,
                scope = scope,
                autoStop = WatchBridgePrefs.isAutoStop(this),
                emit = { json ->
                    connection.sendText(json.toString())
                    if (json.optString("type") == "processing") {
                        FloatingBubbleService.instance?.onWatchProcessing()
                    }
                },
                deliver = { text -> withContext(Dispatchers.Main) { injectText(text) } },
                onFinished = {
                    val noSessionLeft = synchronized(lock) {
                        if (session === created) {
                            session = null
                            sessionConnection = null
                        }
                        session == null
                    }
                    // 被新 session 取代時，舊 session 的結束不得把懸浮球重設
                    if (noSessionLeft) FloatingBubbleService.instance?.onWatchListeningEnded()
                },
                onAudioLevel = { rms -> FloatingBubbleService.instance?.onWatchAudioLevel(rms) },
            )
            session = created
            sessionConnection = connection
        }
        FloatingBubbleService.instance?.onWatchListeningStarted()
    }

    // ── 填入文字 ──────────────────────────────────────────────────────

    /** 於主執行緒呼叫。回傳是否已直接填入焦點欄位。 */
    private fun injectText(text: String): Boolean {
        QuickPhrasesManager.addHistory(this, text)
        val injected = VoiceAccessibilityService.instance?.inputText(text) ?: false
        if (injected) {
            // 與語音輸入一致：讓懸浮球顯示 ✕ 撤銷鍵（若懸浮球正在執行）
            FloatingBubbleService.instance?.enterPastedState()
        } else {
            copyToClipboardFallback(text)
        }
        return injected
    }

    private fun copyToClipboardFallback(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("transcription", text).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    // ── 通知 ──────────────────────────────────────────────────────────

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.watch_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun startForegroundWithNotification() {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, ImeSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WatchBridgeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.watch_notif_title))
            .setContentText(getString(R.string.watch_notif_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.watch_notif_action_stop), stopIntent)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }
    }
}
