package com.ping.verbead.tts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.ping.verbead.ocr.PpOcrEngine
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext

/**
 * Reads a screenshot with the on-device text recognition model, for screens whose text the accessibility
 * tree does not expose. The model is loaded for the one reading and released afterwards; nothing leaves
 * the device.
 */
class OcrTextSource(private val context: Context) {

    sealed class Outcome {
        /** Text found: in reading order as [lines], and as the separate [items] they were made from, in screen pixels. */
        class Lines(val lines: List<ScreenLine>, val items: List<ScreenTextItem>) : Outcome()

        /** The recognizer's files are not downloaded (or would not load). */
        object NoModel : Outcome()

        /** The screen was recognized and held no text. */
        object Empty : Outcome()

        object Failed : Outcome()
    }

    /**
     * Recognizes [shot]. Positions come back in the pixels of the screenshot, which are the pixels the accessibility
     * tree reports (both cover the whole display, navigation bar included; do not scale them by the app's own
     * display size, which leaves that bar out). Text whose centre lies in the top [ignoreTop] or the bottom
     * [ignoreBottom] pixels (the status and navigation bars: clock, battery) is left out. Cancelling the calling
     * coroutine stops between boxes.
     */
    suspend fun read(shot: Bitmap, ignoreTop: Int = 0, ignoreBottom: Int = 0): Outcome {
        val engine = PpOcrEngine(context)
        try {
            if (!engine.load()) {
                Log.w(TAG, "OCR model not loaded: ${engine.lastLoadError}")
                return Outcome.NoModel
            }
            val items = ArrayList<ScreenTextItem>()
            for (box in engine.detectText(shot)) {
                coroutineContext.ensureActive()
                val centerY = (box.top + box.bottom) / 2
                if (centerY < ignoreTop || centerY > shot.height - ignoreBottom) continue
                val text = engine.recognizeBox(shot, box).trim()
                if (text.isEmpty()) continue
                items += glyphBox(box, shot.width, shot.height, text)
            }
            val lines = ReadingOrder.toLines(items)
            return if (lines.isEmpty()) Outcome.Empty else Outcome.Lines(lines, items)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "OCR failed", t)
            return Outcome.Failed
        } finally {
            engine.release()
        }
    }

    /**
     * The detector's boxes are tighter than the glyphs (measured: 18 px high for text about 30 px high), and the
     * recognizer already widens them before it reads (see PpOcrEngine.recognizeBox). The same widening makes the box
     * the size of the writing, which is what the line height and the highlight need.
     */
    private fun glyphBox(box: RectF, width: Int, height: Int, text: String): ScreenTextItem {
        val horizontal = box.height() <= box.width() * 1.15f
        val padX = if (horizontal) max(3, (box.width() * 0.08f).roundToInt()) else max(3, (box.width() * CROSS).roundToInt())
        val padY = if (horizontal) max(3, (box.height() * CROSS).roundToInt()) else max(3, (box.height() * 0.04f).roundToInt())
        return ScreenTextItem(
            (box.left - padX).toInt().coerceIn(0, width),
            (box.top - padY).toInt().coerceIn(0, height),
            (box.right + padX).toInt().coerceIn(0, width),
            (box.bottom + padY).toInt().coerceIn(0, height),
            text
        )
    }

    private companion object {
        const val TAG = "OcrTextSource"
        const val CROSS = 0.4f
    }
}
