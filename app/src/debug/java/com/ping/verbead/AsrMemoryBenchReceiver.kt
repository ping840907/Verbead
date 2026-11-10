package com.ping.verbead

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.Process
import android.util.Log
import com.ping.verbead.engine.AsrCatalog
import com.ping.verbead.engine.AsrMode
import com.ping.verbead.engine.FinalEngines
import com.ping.verbead.engine.StreamingAsrEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Debug-only: measures how much memory one speech model takes. It runs in its own process (see the debug manifest)
 * and kills that process when it is done, so every model starts from the same clean baseline.
 *
 *   adb shell am broadcast -n <pkg>/com.ping.verbead.AsrMemoryBenchReceiver --es model <id> [--es wav en1.wav]
 *
 * Logs one line "MEM <id> ..." with the PSS (kB) before loading, after loading, and the peak while transcribing
 * the wav file (16 kHz mono 16-bit, in <external files>/litert/).
 */
class AsrMemoryBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val id = intent.getStringExtra("model") ?: ""
        val wav = intent.getStringExtra("wav") ?: "en1.wav"
        thread(name = "asr-mem") {
            try {
                val model = AsrCatalog.byId(id)
                if (model == null) {
                    Log.e(TAG, "MEM $id unknown")
                    return@thread
                }
                val pcm = readWav(File(File(app.getExternalFilesDir(null), "litert"), wav))
                Runtime.getRuntime().gc()
                Thread.sleep(400)
                val base = Debug.getPss()
                val peak = AtomicLong(base)
                val sampling = AtomicBoolean(true)
                val sampler = thread(name = "asr-mem-sampler") {
                    while (sampling.get()) {
                        peak.set(maxOf(peak.get(), Debug.getPss()))
                        Thread.sleep(40)
                    }
                }

                val t0 = System.nanoTime()
                var ok: Boolean
                var loaded = base
                var loadMs = 0.0
                var decodeMs = 0.0
                var text = ""
                if (model.mode == AsrMode.LIVE) {
                    val e = StreamingAsrEngine(app, model)
                    ok = runBlocking { e.load() }.success
                    loadMs = (System.nanoTime() - t0) / 1e6
                    loaded = Debug.getPss()
                    if (ok) {
                        val stream = e.createStream()
                        if (stream != null) {
                            val s = System.nanoTime()
                            var i = 0
                            while (i < pcm.size) {
                                e.acceptWaveform(stream, pcm.copyOfRange(i, minOf(pcm.size, i + 1600)))
                                while (e.isReady(stream)) e.decode(stream)
                                i += 1600
                            }
                            e.inputFinished(stream)
                            while (e.isReady(stream)) e.decode(stream)
                            decodeMs = (System.nanoTime() - s) / 1e6
                            text = e.getResult(stream)
                        }
                    }
                } else {
                    val e = FinalEngines.create(app, model)
                    ok = runBlocking { e.load("") }.success
                    loadMs = (System.nanoTime() - t0) / 1e6
                    loaded = Debug.getPss()
                    if (ok) {
                        val s = System.nanoTime()
                        text = runBlocking { e.transcribe(pcm) }
                        decodeMs = (System.nanoTime() - s) / 1e6
                    }
                }
                peak.set(maxOf(peak.get(), Debug.getPss()))
                sampling.set(false)
                sampler.join()
                Log.i(
                    TAG,
                    "MEM $id ok=$ok baseKb=$base loadedKb=$loaded peakKb=${peak.get()} loadMs=%.0f decodeMs=%.0f audioS=%.1f text=${text.take(40)}"
                        .format(loadMs, decodeMs, pcm.size / 16000f)
                )
            } catch (t: Throwable) {
                Log.e(TAG, "MEM $id failed: $t", t)
            } finally {
                pending.finish()
                // this process exists only for the measurement
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun readWav(f: File): FloatArray {
        val b = f.readBytes()
        var p = 12
        while (p + 8 <= b.size) {
            val chunk = String(b, p, 4, Charsets.US_ASCII)
            val sz = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunk == "data") {
                val n = minOf(sz, b.size - p - 8) / 2
                val sb = ByteBuffer.wrap(b, p + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(n) { sb.get(it) / 32768f }
            }
            p += 8 + sz + (sz and 1)
        }
        error("no data chunk in ${f.name}")
    }

    private companion object {
        const val TAG = "AsrMem"
    }
}
