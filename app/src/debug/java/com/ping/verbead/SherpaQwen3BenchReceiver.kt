package com.ping.verbead

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ping.verbead.engine.Qwen3AsrEngine
import com.ping.verbead.engine.XAsrEngine
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

                val qwen = Qwen3AsrEngine(app)
                val q = runBlocking { qwen.load() }
                Log.i(TAG, "Qwen3 loaded ok=${q.success} provider=${q.provider} error=${q.error}")
                if (q.success) for (w in wavs) {
                    val pcm = readWav(File(dir, w)); val win = pcm.copyOf(minOf(pcm.size, 80000))
                    val s = System.nanoTime()
                    Log.i(TAG, "Qwen3 $w %.0fms -> ${runBlocking { qwen.transcribe(win) }}".format((System.nanoTime() - s) / 1e6))
                }
                qwen.release()

                val x = XAsrEngine(app)
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
