package com.ping.verbead.ocr

import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.ModelZipInstaller
import com.ping.verbead.engine.OcrModelManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanFallbackTest {

    private fun result(text: String, conf: Float, gap: Int = 3) =
        RecResult(text, conf, text.length, gap)

    @Test
    fun hangulDetection() {
        assertTrue(KoreanFallback.containsHangul("로그인"))
        assertTrue(KoreanFallback.containsHangul("KB국민은행"))
        assertFalse(KoreanFallback.containsHangul("設定とプライバシー"))
        assertFalse(KoreanFallback.containsHangul("Hello 12345"))
    }

    @Test
    fun confidentCompleteResultsNeverTriggerTheSecondModel() {
        assertFalse(KoreanFallback.primaryLooksUnsupported(result("今天天氣很好，我們去公園散步吧", 1.0f, gap = 4)))
        assertFalse(KoreanFallback.primaryLooksUnsupported(result("Hello World 12345", 0.98f, gap = 3)))
    }

    @Test
    fun unreadableResultsTriggerTheSecondModel() {
        // nothing decoded (Hangul silently dropped)
        assertTrue(KoreanFallback.primaryLooksUnsupported(RecResult("", 0f, 0, 20)))
        // only the digits were read, the Hangul before them left a long silent stretch
        assertTrue(KoreanFallback.primaryLooksUnsupported(result("12,000", 0.95f, gap = 27)))
        // look-alike ideograph, low confidence
        assertTrue(KoreanFallback.primaryLooksUnsupported(result("言", 0.4f, gap = 5)))
    }

    @Test
    fun koreanIsAdoptedOnlyWhenItReallyReadHangul() {
        val primary = result("12,000", 0.95f)
        assertTrue(KoreanFallback.shouldAdoptKorean(primary, result("삼겹살구이12,000원", 0.99f)))
        // same Latin text from both models: keep the first one
        assertFalse(KoreanFallback.shouldAdoptKorean(result("Settings", 0.7f), result("Settings", 0.95f)))
        // Hangul but not confident
        assertFalse(KoreanFallback.shouldAdoptKorean(primary, result("삼겹", 0.5f)))
        // Korean explains less of the strip than the first model
        assertFalse(KoreanFallback.shouldAdoptKorean(result("KB국민은행계좌번호ABC", 0.9f), result("가", 0.95f)))
    }

    @Test
    fun koreanFirstOnlyWhenConfident() {
        assertTrue(KoreanFallback.trustKoreanFirst(result("로그인", 0.99f)))
        assertFalse(KoreanFallback.trustKoreanFirst(result("로그인", 0.85f)))
        assertFalse(KoreanFallback.trustKoreanFirst(result("Login", 0.99f)))
    }

    @Test
    fun ctcDecoderReportsGapsBetweenCharacters() {
        // vocab: 0 blank, 1 'a', 2 'b'; 12 steps, 'a' at step 1, 'b' at step 10 (strip is 12 steps wide)
        val steps = 12
        val vocab = 3
        val probs = FloatArray(steps * vocab)
        for (t in 0 until steps) probs[t * vocab] = 1f
        probs[1 * vocab] = 0f; probs[1 * vocab + 1] = 0.9f
        probs[10 * vocab] = 0f; probs[10 * vocab + 2] = 0.8f
        val r = CtcDecoder.decode(probs, steps, vocab, listOf("a", "b"), usableSteps = 12)
        assertEquals("ab", r.text)
        assertEquals(2, r.emitted)
        assertEquals(0.85f, r.conf, 0.001f)
        assertEquals(9, r.maxGapSteps) // 1 -> 10
    }

    @Test
    fun ctcDecoderIgnoresPaddingWhenMeasuringGaps() {
        val steps = 20
        val vocab = 2
        val probs = FloatArray(steps * vocab)
        for (t in 0 until steps) probs[t * vocab] = 1f
        probs[2 * vocab] = 0f; probs[2 * vocab + 1] = 0.9f
        // only the first 5 steps are real strip, the rest is padding
        val r = CtcDecoder.decode(probs, steps, vocab, listOf("a"), usableSteps = 5)
        assertEquals(3, r.maxGapSteps) // 2 -> end of the real strip at 5
    }

    @Test
    fun koreanFilesAreDeclaredInTheManifestAndImportedFromFlatZips() {
        for (name in ModelConfig.requiredOcrKoreanFiles()) {
            assertTrue("no checksum for $name", OcrModelManifest.sha256.containsKey(name))
            assertEquals("ocr/$name", ModelZipInstaller.normalizeEntryName(name))
        }
    }

    @Test
    fun ctcDecoderDropsLowProbabilityStrayEdgeCharactersButKeepsHangul() {
        // dict: 'a' (1), ''' (2), '가' (3); steps: ' (0.3) | 가 (0.5) | a (0.4)
        val vocab = 4
        val steps = 6
        val probs = FloatArray(steps * vocab)
        for (t in 0 until steps) probs[t * vocab] = 1f
        fun emit(t: Int, idx: Int, p: Float) { probs[t * vocab] = 0f; probs[t * vocab + idx] = p }
        emit(0, 2, 0.3f); emit(2, 3, 0.5f); emit(4, 1, 0.4f)
        val dict = listOf("a", "'", "가")
        val plain = CtcDecoder.decode(probs, steps, vocab, dict)
        assertEquals("'가a", plain.text)
        val trimmed = CtcDecoder.decode(probs, steps, vocab, dict, edgeMinProb = 0.6f, isProtected = { c -> c.any { KoreanFallback.isHangul(it) } })
        assertEquals("가", trimmed.text) // stray quote and low-confidence latin edge dropped, Hangul kept
        assertEquals(1, trimmed.emitted)
        assertEquals(0.5f, trimmed.conf, 0.001f)
    }
}
