package com.ping.verbead

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.ocr.PpOcrEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only OCR smoke test. Runs the real PpOcrEngine (LiteRT) on an image from <external files>/litert/:
 *   adb shell am broadcast -n <pkg>/com.ping.verbead.OcrSmokeReceiver --es image page_light.png --es model small|tiny
 */
class OcrSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "ocr-smoke") {
            try {
                val img = File(File(app.getExternalFilesDir(null), "litert"), intent.getStringExtra("image") ?: "page_light.png")
                val tiny = intent.getStringExtra("model") == "tiny"
                ModelConfig.setSelectedOcrModel(app, if (tiny) ModelConfig.OCR_MODEL_TINY else ModelConfig.OCR_MODEL_SMALL)
                val bmp = BitmapFactory.decodeFile(img.path)
                Log.i(TAG, "image=${img.name} ${bmp.width}x${bmp.height} model=${if (tiny) "tiny" else "small"}")
                val engine = PpOcrEngine(app)
                var t = System.nanoTime()
                val ok = runBlocking { engine.load() }
                Log.i(TAG, "load ok=$ok err=${engine.lastLoadError} %.0fms".format((System.nanoTime() - t) / 1e6))
                if (ok) repeat(2) { run ->
                    t = System.nanoTime()
                    val boxes = runBlocking { engine.detectText(bmp) }
                    val detMs = (System.nanoTime() - t) / 1e6
                    t = System.nanoTime()
                    val lines = boxes.sortedBy { it.top }.map { runBlocking { engine.recognizeBox(bmp, it) } }
                    val recMs = (System.nanoTime() - t) / 1e6
                    Log.i(TAG, "run$run boxes=${boxes.size} det=%.0fms rec(all)=%.0fms nonEmpty=${lines.count { it.isNotBlank() }}".format(detMs, recMs))
                    if (run == 1) lines.forEachIndexed { i, s -> Log.i(TAG, "  [$i] $s") }
                }
                engine.release()
                Log.i(TAG, "DONE")
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "OcrSmoke" }
}
