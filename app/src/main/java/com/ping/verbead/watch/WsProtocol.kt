package com.ping.verbead.watch

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * RFC 6455 的最小子集：只實作本機 Pebble 橋接需要的伺服器端。
 * 不依賴 Android，可直接於 JVM 單元測試。
 */
object WsProtocol {

    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    const val CLOSE_NORMAL = 1000
    const val CLOSE_PROTOCOL_ERROR = 1002
    const val CLOSE_POLICY = 1008
    const val CLOSE_TOO_BIG = 1009
    const val CLOSE_GOING_AWAY = 1001

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    class ProtocolException(val closeCode: Int, message: String) : IOException(message)

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    fun acceptKey(clientKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((clientKey + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** 伺服器送出的 frame（不遮罩）。 */
    fun encodeFrame(opcode: Int, payload: ByteArray, fin: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 10)
        out.write((if (fin) 0x80 else 0) or (opcode and 0x0F))
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size <= 0xFFFF -> {
                out.write(126)
                out.write(payload.size ushr 8)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(127)
                val len = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) out.write(((len ushr shift) and 0xFF).toInt())
            }
        }
        out.write(payload)
        return out.toByteArray()
    }

    fun closePayload(code: Int, reason: String = ""): ByteArray {
        val r = reason.toByteArray(Charsets.UTF_8).let { if (it.size > 120) it.copyOf(120) else it }
        return byteArrayOf((code ushr 8).toByte(), (code and 0xFF).toByte()) + r
    }

    /**
     * 讀取一個 client frame。客戶端 frame 必須遮罩（RFC 6455 §5.1），否則視為協定錯誤。
     * @throws EOFException 連線已關閉
     * @throws ProtocolException 協定違規或超過 [maxPayload]
     */
    fun readFrame(input: InputStream, maxPayload: Int): Frame {
        val b0 = readByte(input)
        val b1 = readByte(input)
        val fin = (b0 and 0x80) != 0
        if ((b0 and 0x70) != 0) throw ProtocolException(CLOSE_PROTOCOL_ERROR, "RSV bits set")
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        if (!masked) throw ProtocolException(CLOSE_PROTOCOL_ERROR, "client frame not masked")
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((readByte(input) shl 8) or readByte(input)).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or readByte(input).toLong() }
            if (len < 0) throw ProtocolException(CLOSE_TOO_BIG, "frame too large")
        }
        val isControl = opcode >= 0x8
        if (isControl && (!fin || len > 125)) {
            throw ProtocolException(CLOSE_PROTOCOL_ERROR, "bad control frame")
        }
        if (len > maxPayload) throw ProtocolException(CLOSE_TOO_BIG, "frame too large")
        val mask = ByteArray(4)
        readFully(input, mask, 4)
        val payload = ByteArray(len.toInt())
        readFully(input, payload, payload.size)
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(fin, opcode, payload)
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }
}
