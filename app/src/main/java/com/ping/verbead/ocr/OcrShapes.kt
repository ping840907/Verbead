package com.ping.verbead.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fixed-shape choices for the LiteRT PP-OCR models. GPUs/NPUs want static shapes, so each model is exported at a
 * few sizes and the input is letterboxed / padded into the nearest one.
 */
object OcrShapes {
    /** Detector input sizes (width x height); see tools/litert/convert_ocr.py. */
    val DET_SIZES = listOf(640 to 640, 480 to 960, 960 to 480)

    /**
     * Recognizer widths per model (height is always [REC_HEIGHT]). The recognizers contain attention, so the padding
     * that fills a strip up to its bucket is seen by the model: Small is robust to a 2x gap between buckets, Tiny is
     * not (measured: only 20/24 test strips decode identically with 2x buckets, 24/24 with 1.25x), so Tiny gets a
     * finer ladder.
     */
    val REC_WIDTHS_SMALL = listOf(160, 320, 640, 1280)
    val REC_WIDTHS_TINY = listOf(64, 96, 128, 160, 192, 224, 256, 320, 384, 448, 512, 640, 768, 896, 1024, 1280)

    fun recWidths(tiny: Boolean): List<Int> = if (tiny) REC_WIDTHS_TINY else REC_WIDTHS_SMALL
    const val REC_HEIGHT = 48
    const val REC_MIN_WIDTH = 32
    const val REC_MAX_WIDTH = 1280

    /** Never enlarge a detector input by more than this: tiny crops are not worth blowing up. */
    private const val MAX_UPSCALE = 2.0f

    data class DetPlan(val width: Int, val height: Int, val scale: Float, val contentW: Int, val contentH: Int)

    /** Picks the detector size that keeps the image largest, then letterboxes (top-left) at [DetPlan.scale]. */
    fun planDet(imageW: Int, imageH: Int): DetPlan {
        var best = DET_SIZES.first()
        var bestScale = -1f
        for (s in DET_SIZES) {
            val sc = min(s.first.toFloat() / imageW, s.second.toFloat() / imageH)
            if (sc > bestScale + 1e-6f) { bestScale = sc; best = s }
        }
        val scale = min(bestScale, MAX_UPSCALE)
        val cw = max(1, min(best.first, (imageW * scale).roundToInt()))
        val ch = max(1, min(best.second, (imageH * scale).roundToInt()))
        return DetPlan(best.first, best.second, scale, cw, ch)
    }

    /** Width of a recognizer strip after scaling to height 48 (multiple of 4, 32..1280), as the old engine did. */
    fun recStripWidth(stripW: Int, stripH: Int): Int {
        val w = (stripW * (REC_HEIGHT.toFloat() / stripH)).toInt().coerceIn(REC_MIN_WIDTH, REC_MAX_WIDTH)
        return max(REC_MIN_WIDTH, ((w + 3) / 4) * 4)
    }

    /** Smallest exported width of the model's ladder that fits [stripWidth]. */
    fun recBucket(stripWidth: Int, tiny: Boolean = false): Int {
        val widths = recWidths(tiny)
        return widths.firstOrNull { it >= stripWidth } ?: widths.last()
    }
}
