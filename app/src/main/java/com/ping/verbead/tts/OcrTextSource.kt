package com.ping.verbead.tts

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ping.verbead.ocr.PpOcrEngine
import kotlinx.coroutines.ensureActive
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
     * Recognizes [shot]. Positions are scaled to [screenWidth] x [screenHeight] (a screenshot is normally the
     * same size as the screen, but not always). Text whose centre lies in the top [ignoreTop] or the bottom
     * [ignoreBottom] pixels (the status and navigation bars: clock, battery) is left out. Cancelling the
     * calling coroutine stops between boxes.
     */
    suspend fun read(shot: Bitmap, screenWidth: Int, screenHeight: Int, ignoreTop: Int = 0, ignoreBottom: Int = 0): Outcome {
        val engine = PpOcrEngine(context)
        try {
            if (!engine.load()) {
                Log.w(TAG, "OCR model not loaded: ${engine.lastLoadError}")
                return Outcome.NoModel
            }
            val sx = if (shot.width > 0) screenWidth.toFloat() / shot.width else 1f
            val sy = if (shot.height > 0) screenHeight.toFloat() / shot.height else 1f
            val items = ArrayList<ScreenTextItem>()
            for (box in engine.detectText(shot)) {
                coroutineContext.ensureActive()
                val centerY = (box.top + box.bottom) / 2 * sy
                if (centerY < ignoreTop || centerY > screenHeight - ignoreBottom) continue
                val text = engine.recognizeBox(shot, box).trim()
                if (text.isEmpty()) continue
                items += ScreenTextItem(
                    (box.left * sx).toInt(), (box.top * sy).toInt(), (box.right * sx).toInt(), (box.bottom * sy).toInt(), text
                )
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

    private companion object { const val TAG = "OcrTextSource" }
}
