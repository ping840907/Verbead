package com.ping.verbead.watch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class WsProtocolTest {

    /** 依 RFC 6455 產生「客戶端」遮罩 frame，用來餵給伺服器端的 readFrame。 */
    private fun clientFrame(
        opcode: Int,
        payload: ByteArray,
        fin: Boolean = true,
        mask: ByteArray = byteArrayOf(0x11, 0x22, 0x33, 0x44),
        masked: Boolean = true,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        val maskBit = if (masked) 0x80 else 0
        when {
            payload.size < 126 -> out.write(maskBit or payload.size)
            payload.size <= 0xFFFF -> {
                out.write(maskBit or 126)
                out.write(payload.size ushr 8); out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() ushr shift) and 0xFF).toInt())
            }
        }
        if (masked) {
            out.write(mask)
            payload.forEachIndexed { i, b -> out.write(b.toInt() xor mask[i and 3].toInt()) }
        } else {
            out.write(payload)
        }
        return out.toByteArray()
    }

    @Test
    fun acceptKey_matchesRfc6455Example() {
        // RFC 6455 §1.3 的範例
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            WsProtocol.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")
        )
    }

    @Test
    fun readFrame_unmasksSmallPayload() {
        val payload = "你好 Verbead".toByteArray()
        val f = WsProtocol.readFrame(ByteArrayInputStream(clientFrame(WsProtocol.OP_TEXT, payload)), 1024)
        assertTrue(f.fin)
        assertEquals(WsProtocol.OP_TEXT, f.opcode)
        assertArrayEquals(payload, f.payload)
    }

    @Test
    fun readFrame_handles16BitLength() {
        val payload = ByteArray(3200) { (it % 251).toByte() } // 100ms 的 16kHz PCM16
        val f = WsProtocol.readFrame(ByteArrayInputStream(clientFrame(WsProtocol.OP_BINARY, payload)), 1 shl 20)
        assertEquals(WsProtocol.OP_BINARY, f.opcode)
        assertArrayEquals(payload, f.payload)
    }

    @Test
    fun readFrame_handles64BitLength() {
        val payload = ByteArray(70_000) { (it % 7).toByte() }
        val f = WsProtocol.readFrame(ByteArrayInputStream(clientFrame(WsProtocol.OP_BINARY, payload)), 1 shl 20)
        assertArrayEquals(payload, f.payload)
    }

    @Test
    fun readFrame_rejectsUnmaskedClientFrame() {
        val bytes = clientFrame(WsProtocol.OP_TEXT, "x".toByteArray(), masked = false)
        try {
            WsProtocol.readFrame(ByteArrayInputStream(bytes), 1024)
            fail("expected ProtocolException")
        } catch (e: WsProtocol.ProtocolException) {
            assertEquals(WsProtocol.CLOSE_PROTOCOL_ERROR, e.closeCode)
        }
    }

    @Test
    fun readFrame_rejectsOversizedPayloadBeforeAllocating() {
        // 宣告 2GB 但實際沒有資料：必須在配置記憶體前就拒絕
        val header = byteArrayOf(0x82.toByte(), (0x80 or 127).toByte(), 0, 0, 0, 0, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        try {
            WsProtocol.readFrame(ByteArrayInputStream(header), 1 shl 20)
            fail("expected ProtocolException")
        } catch (e: WsProtocol.ProtocolException) {
            assertEquals(WsProtocol.CLOSE_TOO_BIG, e.closeCode)
        }
    }

    @Test
    fun readFrame_rejectsFragmentedControlFrame() {
        val bytes = clientFrame(WsProtocol.OP_PING, ByteArray(0), fin = false)
        try {
            WsProtocol.readFrame(ByteArrayInputStream(bytes), 1024)
            fail("expected ProtocolException")
        } catch (e: WsProtocol.ProtocolException) {
            assertEquals(WsProtocol.CLOSE_PROTOCOL_ERROR, e.closeCode)
        }
    }

    @Test
    fun readFrame_rejectsReservedBits() {
        val bytes = clientFrame(WsProtocol.OP_TEXT, ByteArray(1)).also { it[0] = (it[0].toInt() or 0x40).toByte() }
        try {
            WsProtocol.readFrame(ByteArrayInputStream(bytes), 1024)
            fail("expected ProtocolException")
        } catch (e: WsProtocol.ProtocolException) {
            assertEquals(WsProtocol.CLOSE_PROTOCOL_ERROR, e.closeCode)
        }
    }

    @Test
    fun readFrame_truncatedStreamThrowsEof() {
        val bytes = clientFrame(WsProtocol.OP_BINARY, ByteArray(100)).copyOf(20)
        try {
            WsProtocol.readFrame(ByteArrayInputStream(bytes), 1024)
            fail("expected EOFException")
        } catch (e: EOFException) {
            // ok
        }
    }

    @Test
    fun encodeFrame_usesCorrectLengthEncodings() {
        assertEquals(2 + 5, WsProtocol.encodeFrame(WsProtocol.OP_TEXT, ByteArray(5)).size)
        val mid = WsProtocol.encodeFrame(WsProtocol.OP_TEXT, ByteArray(300))
        assertEquals(126, mid[1].toInt() and 0x7F)
        assertEquals(4 + 300, mid.size)
        val big = WsProtocol.encodeFrame(WsProtocol.OP_BINARY, ByteArray(70_000))
        assertEquals(127, big[1].toInt() and 0x7F)
        assertEquals(10 + 70_000, big.size)
        // 伺服器 frame 不得遮罩
        assertFalse((big[1].toInt() and 0x80) != 0)
    }

    @Test
    fun closePayload_carriesCodeAndReason() {
        val p = WsProtocol.closePayload(1008, "no")
        assertEquals(0x03, p[0].toInt())
        assertEquals(0xF0, p[1].toInt() and 0xFF)
        assertEquals("no", String(p, 2, p.size - 2))
    }
}
