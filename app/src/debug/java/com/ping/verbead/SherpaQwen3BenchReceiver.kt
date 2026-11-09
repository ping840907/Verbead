package com.ping.verbead

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ping.verbead.engine.Qwen3AsrEngine
import com.ping.verbead.engine.AsrCatalog
import com.ping.verbead.engine.AsrMode
import com.ping.verbead.engine.FinalEngine
import com.ping.verbead.engine.FinalEngines
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.engine.SherpaOfflineEngine
import com.ping.verbead.engine.StreamingAsrEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Debug-only smoke test for the speech engines (sherpa-onnx): loads Qwen3-ASR and X-ASR and transcribes wav files
 * (16 kHz mono 16-bit) from <external files>/litert/. Used to confirm the native libraries still link after the
 * Microsoft ONNX Runtime dependency was removed.
 *   adb shell am broadcast -n <pkg>/com.ping.verbead.SherpaQwen3BenchReceiver --es wavs a.wav,b.wav
 */
class SherpaQwen3BenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "sherpa-smoke") {
            try {
                val dir = File(app.getExternalFilesDir(null), "litert")
                val wavs = (intent.getStringExtra("wavs") ?: "").split(",").filter { it.isNotBlank() }
                intent.getStringExtra("download")?.let { id ->
                    Log.i(TAG, "start download $id")
                    ModelDownloadService.start(app, id)
                    return@thread
                }
                val modelIds = (intent.getStringExtra("models") ?: "").split(",").filter { it.isNotBlank() }
                if (modelIds.isNotEmpty()) {
                    catalogSmoke(app, dir, modelIds, wavs, intent.getBooleanExtra("asfinal", false))
                    Log.i(TAG, "DONE")
                    return@thread
                }

                val qwen = Qwen3AsrEngine(app)
                val q = runBlocking { qwen.load() }
                Log.i(TAG, "Qwen3 loaded ok=${q.success} provider=${q.provider} error=${q.error}")
                if (q.success) for (w in wavs) {
                    val pcm = readWav(File(dir, w)); val win = pcm.copyOf(minOf(pcm.size, 80000))
                    val s = System.nanoTime()
                    Log.i(TAG, "Qwen3 $w %.0fms -> ${runBlocking { qwen.transcribe(win) }}".format((System.nanoTime() - s) / 1e6))
                }
                qwen.release()

                val x = StreamingAsrEngine(app, AsrCatalog.byId(AsrCatalog.X_ASR)!!)
                val xr = runBlocking { x.load() }
                Log.i(TAG, "X-ASR loaded ok=${xr.success} provider=${xr.provider} error=${xr.error}")
                if (xr.success) for (w in wavs) {
                    val pcm = readWav(File(dir, w))
                    val stream = x.createStream() ?: continue
                    val s = System.nanoTime()
                    var i = 0
                    while (i < pcm.size) {
                        x.acceptWaveform(stream, pcm.copyOfRange(i, minOf(pcm.size, i + 1600)))
                        while (true) { x.decode(stream); break }
                        i += 1600
                    }
                    x.inputFinished(stream); x.decode(stream)
                    Log.i(TAG, "X-ASR $w %.0fms -> ${x.getResult(stream)}".format((System.nanoTime() - s) / 1e6))
                }
                x.release()
                Log.i(TAG, "DONE")
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    /** Loads each catalog model (downloaded or pushed into models/<dir>) and transcribes the wav files with it. */
    private fun catalogSmoke(app: Context, dir: File, ids: List<String>, wavs: List<String>, asFinal: Boolean = false) {
        for (id in ids) {
            val model = AsrCatalog.byId(id)
            if (model == null) { Log.e(TAG, "unknown model $id"); continue }
            Log.i(TAG, "$id ready=${ModelConfig.isModelReady(app, model)}")
            val t0 = System.nanoTime()
            if (model.mode == AsrMode.LIVE && !asFinal) {
                val e = StreamingAsrEngine(app, model)
                val r = runBlocking { e.load() }
                Log.i(TAG, "$id load ok=${r.success} %.0fms error=${r.error}".format((System.nanoTime() - t0) / 1e6))
                if (!r.success) continue
                for (w in wavs) {
                    val pcm = readWav(if (w.startsWith("/")) File(w) else File(dir, w))
                    val stream = e.createStream() ?: continue
                    val s = System.nanoTime()
                    var i = 0
                    while (i < pcm.size) {
                        e.acceptWaveform(stream, pcm.copyOfRange(i, minOf(pcm.size, i + 1600)))
                        while (e.isReady(stream)) e.decode(stream)
                        i += 1600
                    }
                    e.inputFinished(stream)
                    while (e.isReady(stream)) e.decode(stream)
                    val ms = (System.nanoTime() - s) / 1e6
                    Log.i(TAG, "$id $w audio=%.1fs took=%.0fms -> ${e.getResult(stream)}".format(pcm.size / 16000f, ms))
                }
                e.release()
            } else {
                val e: FinalEngine = FinalEngines.create(app, model)
                val r = runBlocking { e.load("") }
                Log.i(TAG, "$id load ok=${r.success} %.0fms error=${r.error}".format((System.nanoTime() - t0) / 1e6))
                if (!r.success) continue
                for (w in wavs) {
                    val pcm = readWav(if (w.startsWith("/")) File(w) else File(dir, w))
                    val s = System.nanoTime()
                    val text = runBlocking { e.transcribe(pcm) }
                    Log.i(TAG, "$id $w audio=%.1fs took=%.0fms -> $text".format(pcm.size / 16000f, (System.nanoTime() - s) / 1e6))
                }
                e.release()
            }
        }
    }

    private fun readWav(f: File): FloatArray {
        val b = f.readBytes()
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val sz = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val n = minOf(sz, b.size - p - 8) / 2
                val sb = ByteBuffer.wrap(b, p + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(n) { sb.get(it) / 32768f }
            }
            p += 8 + sz + (sz and 1)
        }
        error("no data chunk in ${f.name}")
    }

    private companion object { const val TAG = "SpeechSmoke" }
}
