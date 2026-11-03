package com.ping.verbead.watch

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * 只綁定 127.0.0.1 的極簡 WebSocket 伺服器。
 *
 * Pebble 的 PKJS 跑在 Core Devices 手機 App 的 WebView 內，與 Verbead 是不同的行程，
 * 兩者唯一可用的通道是 loopback。因此：
 *  - 只監聽 127.0.0.1，不對區網開放。
 *  - WebSocket 升級必須通過 [Listener.authorize]（共享 token）。
 *  - 同時只保留一個連線，新連線會取代舊連線。
 */
class LocalWebSocketServer(
    private val port: Int,
    private val listener: Listener,
    private val log: (String) -> Unit = {},
) {

    data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        /** header 名稱一律小寫 */
        val headers: Map<String, String>,
    )

    data class HttpResponse(
        val status: Int,
        val contentType: String,
        val body: String,
    )

    interface Listener {
        /** WebSocket 升級前的授權檢查。回傳 false 會得到 403。 */
        fun authorize(request: HttpRequest): Boolean

        /** 非 WebSocket 的 GET 請求（例如配對頁）。回傳 null 表示 404。 */
        fun httpResponse(request: HttpRequest): HttpResponse?

        fun onOpen(connection: Connection)
        fun onText(connection: Connection, text: String)
        fun onBinary(connection: Connection, data: ByteArray)
        fun onClose(connection: Connection)
    }

    inner class Connection internal constructor(private val socket: Socket) {
        private val out: OutputStream = socket.getOutputStream()
        @Volatile var isOpen = true
            private set

        fun sendText(text: String) = send(WsProtocol.OP_TEXT, text.toByteArray(Charsets.UTF_8))

        fun close(code: Int = WsProtocol.CLOSE_NORMAL, reason: String = "") {
            if (!isOpen) return
            runCatching { send(WsProtocol.OP_CLOSE, WsProtocol.closePayload(code, reason)) }
            shutdown()
        }

        internal fun sendRaw(opcode: Int, payload: ByteArray) = send(opcode, payload)

        private fun send(opcode: Int, payload: ByteArray) {
            if (!isOpen) return
            val frame = WsProtocol.encodeFrame(opcode, payload)
            try {
                synchronized(out) {
                    out.write(frame)
                    out.flush()
                }
            } catch (e: IOException) {
                shutdown()
            }
        }

        internal fun shutdown() {
            isOpen = false
            runCatching { socket.close() }
        }
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var current: Connection? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** @return 是否成功綁定。失敗（例如埠被佔用）時回傳 false。 */
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val server = try {
            ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        } catch (e: IOException) {
            log("bind 127.0.0.1:$port failed: ${e.message}")
            return false
        }
        serverSocket = server
        acceptThread = Thread({ acceptLoop(server) }, "verbead-ws-accept").apply {
            isDaemon = true
            start()
        }
        log("listening on 127.0.0.1:$port")
        return true
    }

    @Synchronized
    fun stop() {
        val server = serverSocket ?: return
        serverSocket = null
        runCatching { server.close() }
        current?.close(WsProtocol.CLOSE_GOING_AWAY)
        current = null
    }

    /** 目前是否有手錶端連線中。 */
    val hasClient: Boolean get() = current?.isOpen == true

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: SocketException) {
                break
            } catch (e: IOException) {
                log("accept failed: ${e.message}")
                break
            }
            Thread({ handle(socket) }, "verbead-ws-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        var connection: Connection? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val request = readRequest(input) ?: run {
                writeHttp(socket, HttpResponse(400, "text/plain", "bad request"))
                return
            }

            val isUpgrade = request.headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            if (!isUpgrade) {
                val resp = if (request.method == "GET") listener.httpResponse(request) else null
                writeHttp(socket, resp ?: HttpResponse(404, "text/plain", "not found"))
                return
            }

            val key = request.headers["sec-websocket-key"]
            if (request.method != "GET" || key.isNullOrBlank() ||
                request.headers["sec-websocket-version"] != "13"
            ) {
                writeHttp(socket, HttpResponse(400, "text/plain", "bad websocket request"))
                return
            }
            if (!listener.authorize(request)) {
                writeHttp(socket, HttpResponse(403, "text/plain", "forbidden"))
                return
            }

            socket.getOutputStream().apply {
                write(
                    ("HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${WsProtocol.acceptKey(key)}\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII)
                )
                flush()
            }
            socket.soTimeout = 0

            val conn = Connection(socket)
            connection = conn
            synchronized(this) {
                current?.close(WsProtocol.CLOSE_POLICY, "replaced by newer connection")
                current = conn
            }
            listener.onOpen(conn)
            readLoop(conn, input)
        } catch (e: EOFException) {
            // 對端關閉
        } catch (e: IOException) {
            log("connection error: ${e.message}")
        } finally {
            connection?.let {
                it.shutdown()
                synchronized(this) { if (current === it) current = null }
                runCatching { listener.onClose(it) }
            }
            runCatching { socket.close() }
        }
    }

    private fun readLoop(conn: Connection, input: BufferedInputStream) {
        var fragType = -1
        val fragments = ByteArrayOutputStream()
        while (conn.isOpen) {
            val frame = try {
                WsProtocol.readFrame(input, MAX_MESSAGE_BYTES)
            } catch (e: WsProtocol.ProtocolException) {
                conn.close(e.closeCode, e.message ?: "")
                return
            }
            when (frame.opcode) {
                WsProtocol.OP_PING -> conn.sendRaw(WsProtocol.OP_PONG, frame.payload)
                WsProtocol.OP_PONG -> Unit
                WsProtocol.OP_CLOSE -> {
                    conn.close(WsProtocol.CLOSE_NORMAL)
                    return
                }
                WsProtocol.OP_TEXT, WsProtocol.OP_BINARY, WsProtocol.OP_CONTINUATION -> {
                    if (frame.opcode != WsProtocol.OP_CONTINUATION) {
                        if (fragType != -1) {
                            conn.close(WsProtocol.CLOSE_PROTOCOL_ERROR, "unexpected data frame")
                            return
                        }
                        if (frame.fin) {
                            dispatch(conn, frame.opcode, frame.payload)
                            continue
                        }
                        fragType = frame.opcode
                    } else if (fragType == -1) {
                        conn.close(WsProtocol.CLOSE_PROTOCOL_ERROR, "unexpected continuation")
                        return
                    }
                    fragments.write(frame.payload)
                    if (fragments.size() > MAX_MESSAGE_BYTES) {
                        conn.close(WsProtocol.CLOSE_TOO_BIG, "message too large")
                        return
                    }
                    if (frame.fin) {
                        dispatch(conn, fragType, fragments.toByteArray())
                        fragType = -1
                        fragments.reset()
                    }
                }
                else -> {
                    conn.close(WsProtocol.CLOSE_PROTOCOL_ERROR, "unknown opcode")
                    return
                }
            }
        }
    }

    private fun dispatch(conn: Connection, opcode: Int, payload: ByteArray) {
        try {
            if (opcode == WsProtocol.OP_TEXT) {
                listener.onText(conn, String(payload, Charsets.UTF_8))
            } else {
                listener.onBinary(conn, payload)
            }
        } catch (e: Exception) {
            // listener 的錯誤不得讓連線執行緒崩潰
            log("listener error: ${e.message}")
        }
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val buf = ByteArrayOutputStream()
        var matched = 0 // 已匹配 \r\n\r\n 的長度
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            val expected = "\r\n\r\n"[matched].code
            matched = if (b == expected) matched + 1 else if (b == '\r'.code) 1 else 0
            if (matched == 4) break
        }
        if (matched != 4) return null
        val lines = buf.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines.firstOrNull()?.split(" ") ?: return null
        if (parts.size < 3) return null
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&')
            .filter { it.isNotEmpty() }
            .associate {
                val k = it.substringBefore('=')
                val v = it.substringAfter('=', "")
                decode(k) to decode(v)
            }
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return HttpRequest(parts[0], path, query, headers)
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun writeHttp(socket: Socket, resp: HttpResponse) {
        val body = resp.body.toByteArray(Charsets.UTF_8)
        val reason = when (resp.status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Status"
        }
        val head = "HTTP/1.1 ${resp.status} $reason\r\n" +
            "Content-Type: ${resp.contentType}; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    companion object {
        const val DEFAULT_PORT = 47831
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val MAX_HEADER_BYTES = 8 * 1024
        /** 單一訊息上限；PKJS 每則音訊約 3.2KB（100ms），1MB 綽綽有餘。 */
        private const val MAX_MESSAGE_BYTES = 1 * 1024 * 1024

        /** 常數時間比較，避免從回應時間推測 token。 */
        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
