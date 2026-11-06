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
 * Debug-only: recognizes every .png in the strips folder of <external files>/litert/ with the real PpOcrEngine and logs
 * "name<TAB>text", so the result can be compared with the variable-width ONNX reference text on the PC.
 *   adb shell am broadcast -n <pkg>/com.ping.verbead.OcrRecSmokeReceiver --es model small|tiny --ez fp32 true|false
 */
class OcrRecSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "ocr-rec-smoke") {
            try {
                val tiny = intent.getStringExtra("model") == "tiny"
                PpOcrEngine.forceGpuFp32 = intent.getBooleanExtra("fp32", false)
                ModelConfig.setSelectedOcrModel(app, if (tiny) ModelConfig.OCR_MODEL_TINY else ModelConfig.OCR_MODEL_SMALL)
                val engine = PpOcrEngine(app)
                Log.i(TAG, "mode model=${if (tiny) "tiny" else "small"} fp32=${PpOcrEngine.forceGpuFp32} load=${runBlocking { engine.load() }}")
                val dir = File(File(app.getExternalFilesDir(null), "litert"), "strips")
                val files = dir.listFiles { f -> f.extension == "png" }?.sortedBy { it.name } ?: emptyList()
                val t0 = System.nanoTime()
                for (f in files) {
                    val bmp = BitmapFactory.decodeFile(f.path)
                    Log.i(TAG, "R\t${f.name}\t${runBlocking { engine.recognizeText(bmp) }}")
                }
                Log.i(TAG, "DONE ${files.size} strips in %.0f ms".format((System.nanoTime() - t0) / 1e6))
                engine.release()
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object { const val TAG = "OcrRecSmoke" }
}
