package com.ping.verbead.watch

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 以原始 socket 當客戶端，驗證握手、授權、收發與連線取代。 */
class LocalWebSocketServerTest {

    private val texts = CopyOnWriteArrayList<String>()
    private val binaries = CopyOnWriteArrayList<ByteArray>()
    private val closed = CountDownLatch(1)
    @Volatile private var opened: LocalWebSocketServer.Connection? = null
    private val openLatch = CountDownLatch(1)

    private lateinit var server: LocalWebSocketServer
    private var port = 0

    private val listener = object : LocalWebSocketServer.Listener {
        override fun authorize(request: LocalWebSocketServer.HttpRequest) =
            request.query["token"] == "secret"

        override fun httpResponse(request: LocalWebSocketServer.HttpRequest) =
            if (request.path == "/pair") LocalWebSocketServer.HttpResponse(200, "text/html", "<b>pair</b>") else null

        override fun onOpen(connection: LocalWebSocketServer.Connection) {
            opened = connection; openLatch.countDown()
        }
        override fun onText(connection: LocalWebSocketServer.Connection, text: String) { texts += text }
        override fun onBinary(connection: LocalWebSocketServer.Connection, data: ByteArray) { binaries += data }
        override fun onClose(connection: LocalWebSocketServer.Connection) { closed.countDown() }
    }

    @Before
    fun setUp() {
        // 找一個空閒埠避免與正在執行的 App 衝突
        port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        server = LocalWebSocketServer(port, listener)
        assertTrue(server.start())
    }

    @After
    fun tearDown() = server.stop()

    private fun connect(): Socket =
        Socket(InetAddress.getByName("127.0.0.1"), port).apply { soTimeout = 3000 }

    private fun readHttpHead(input: InputStream): String {
        val sb = ByteArrayOutputStream()
        while (!sb.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
            val b = input.read(); if (b < 0) break; sb.write(b)
        }
        return sb.toString(Charsets.ISO_8859_1.name())
    }

    private fun upgrade(socket: Socket, path: String): String {
        val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        socket.getOutputStream().write(
            ("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray()
        )
        return readHttpHead(socket.getInputStream())
    }

    private fun send(socket: Socket, opcode: Int, payload: ByteArray, fin: Boolean = true) {
        val mask = byteArrayOf(5, 6, 7, 8)
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        if (payload.size < 126) out.write(0x80 or payload.size)
        else { out.write(0x80 or 126); out.write(payload.size ushr 8); out.write(payload.size and 0xFF) }
        out.write(mask)
        payload.forEachIndexed { i, b -> out.write(b.toInt() xor mask[i and 3].toInt()) }
        socket.getOutputStream().write(out.toByteArray()); socket.getOutputStream().flush()
    }

    private fun readServerFrame(socket: Socket): Pair<Int, ByteArray> {
        val i = socket.getInputStream()
        val b0 = i.read(); val b1 = i.read()
        var len = b1 and 0x7F
        if (len == 126) len = (i.read() shl 8) or i.read()
        val buf = ByteArray(len); var off = 0
        while (off < len) off += i.read(buf, off, len - off)
        return (b0 and 0x0F) to buf
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 3000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("condition not met in time", cond())
    }

    @Test
    fun upgrade_withValidToken_succeeds() {
        connect().use { s ->
            val head = upgrade(s, "/?token=secret")
            assertTrue(head, head.startsWith("HTTP/1.1 101"))
            val clientKey = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
            assertTrue(head, head.contains("Sec-WebSocket-Accept: ${WsProtocol.acceptKey(clientKey)}"))
        }
    }

    @Test
    fun upgrade_withoutOrWithWrongToken_isForbidden() {
        connect().use { assertTrue(upgrade(it, "/").startsWith("HTTP/1.1 403")) }
        connect().use { assertTrue(upgrade(it, "/?token=nope").startsWith("HTTP/1.1 403")) }
        assertNull(opened)
    }

    @Test
    fun plainGet_servesPairPageOrNotFound() {
        connect().use { s ->
            s.getOutputStream().write("GET /pair HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val body = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(body, body.startsWith("HTTP/1.1 200")); assertTrue(body.endsWith("<b>pair</b>"))
        }
        connect().use { s ->
            s.getOutputStream().write("GET /other HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            assertTrue(s.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 404"))
        }
    }

    @Test
    fun textAndBinary_roundTrip() {
        connect().use { s ->
            assertTrue(upgrade(s, "/?token=secret").startsWith("HTTP/1.1 101"))
            assertTrue(openLatch.await(3, TimeUnit.SECONDS))

            send(s, WsProtocol.OP_TEXT, """{"type":"start"}""".toByteArray())
            val pcm = ByteArray(3200) { (it * 3).toByte() }
            send(s, WsProtocol.OP_BINARY, pcm)
            waitFor { texts.size == 1 && binaries.size == 1 }
            assertEquals("""{"type":"start"}""", texts[0])
            assertArrayEquals(pcm, binaries[0])

            opened!!.sendText("""{"type":"final","text":"你好"}""")
            val (op, payload) = readServerFrame(s)
            assertEquals(WsProtocol.OP_TEXT, op)
            assertEquals("""{"type":"final","text":"你好"}""", String(payload, Charsets.UTF_8))
        }
    }

    @Test
    fun fragmentedBinaryMessage_isReassembled() {
        connect().use { s ->
            upgrade(s, "/?token=secret"); openLatch.await(3, TimeUnit.SECONDS)
            send(s, WsProtocol.OP_BINARY, byteArrayOf(1, 2), fin = false)
            send(s, WsProtocol.OP_CONTINUATION, byteArrayOf(3, 4), fin = true)
            waitFor { binaries.size == 1 }
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), binaries[0])
        }
    }

    @Test
    fun ping_isAnsweredWithPong() {
        connect().use { s ->
            upgrade(s, "/?token=secret"); openLatch.await(3, TimeUnit.SECONDS)
            send(s, WsProtocol.OP_PING, byteArrayOf(9, 9))
            val (op, payload) = readServerFrame(s)
            assertEquals(WsProtocol.OP_PONG, op)
            assertArrayEquals(byteArrayOf(9, 9), payload)
        }
    }

    @Test
    fun newConnection_replacesOldOne() {
        val first = connect()
        assertTrue(upgrade(first, "/?token=secret").startsWith("HTTP/1.1 101"))
        assertTrue(openLatch.await(3, TimeUnit.SECONDS))
        val firstConn = opened!!

        connect().use { second ->
            assertTrue(upgrade(second, "/?token=secret").startsWith("HTTP/1.1 101"))
            waitFor { !firstConn.isOpen }
            val (op, payload) = readServerFrame(first)
            assertEquals(WsProtocol.OP_CLOSE, op)
            assertEquals(1008, ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF))
        }
        first.close()
    }

    @Test
    fun disconnect_invokesOnClose() {
        connect().use { s ->
            upgrade(s, "/?token=secret"); openLatch.await(3, TimeUnit.SECONDS)
        }
        assertTrue(closed.await(3, TimeUnit.SECONDS))
        waitFor { !server.hasClient }
    }

    @Test
    fun onlyBindsLoopback() {
        // 以非 loopback 位址連線必須失敗（在僅有 lo 的沙箱可能無其他位址，故容許略過）
        val other = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?: return
        val result = runCatching { Socket(other, port).use { } }
        assertTrue("server must not accept on ${other.hostAddress}", result.isFailure)
    }

    @Test
    fun constantTimeEquals_behaves() {
        assertTrue(LocalWebSocketServer.constantTimeEquals("abc", "abc"))
        assertFalse(LocalWebSocketServer.constantTimeEquals("abc", "abd"))
        assertFalse(LocalWebSocketServer.constantTimeEquals("abc", "abcd"))
        assertNotNull(server)
    }
}
