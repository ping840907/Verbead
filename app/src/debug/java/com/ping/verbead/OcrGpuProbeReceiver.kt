package com.ping.verbead

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.ping.verbead.engine.ModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Debug-only: tries to build every OCR .tflite on the GPU (default precision and FP32) and logs which ones the GPU takes.
 *   adb shell am broadcast -n <pkg>/com.ping.verbead.OcrGpuProbeReceiver [--es filter korean] [--ez check true]
 * With check=true it also runs the model on the inputs in <external files>/kcheck (W_n.f32 input [1,48,W,3], W_n.exp the
 * expected collapsed CTC ids) on the GPU and counts the strips whose decoded ids are identical.
 */
class OcrGpuProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val filter = intent.getStringExtra("filter") ?: ""
        val check = intent.getBooleanExtra("check", false)
        thread(name = "ocr-gpu-probe") {
            try {
                val env = Environment.create(app)
                val files = File(ModelConfig.ocrDir(app).toString()).listFiles { f -> f.extension == "tflite" && f.name.contains(filter) }
                    ?.sortedBy { it.name } ?: emptyList()
                for (f in files) {
                    for (mode in listOf("gpu16", "gpu32", "cpu")) {
                        val fp32 = mode == "gpu32"
                        val t0 = SystemClock.elapsedRealtime()
                        val result = try {
                            val opts = CompiledModel.Options(if (mode == "cpu") Accelerator.CPU else Accelerator.GPU)
                            if (mode != "cpu") opts.gpuOptions = CompiledModel.GpuOptions(
                                precision = if (fp32) CompiledModel.GpuOptions.Precision.FP32 else null
                            )
                            val model = CompiledModel.create(f.path, opts, env)
                            val built = SystemClock.elapsedRealtime() - t0
                            val checked = if (check) checkStrips(app, f.name, model) else ""
                            model.close()
                            "OK$checked"
                        } catch (t: Throwable) {
                            "FAIL ${t.message}"
                        }
                        Log.i(TAG, "${f.name} $mode -> $result (${SystemClock.elapsedRealtime() - t0} ms)")
                    }
                }
                Log.i(TAG, "DONE ${files.size} models")
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun checkStrips(app: Context, modelName: String, model: CompiledModel): String {
        val width = Regex("_([0-9]+)[.]tflite").find(modelName)?.groupValues?.get(1) ?: return ""
        val dir = File(app.getExternalFilesDir(null), "kcheck")
        val inputs = dir.listFiles { f -> f.name.startsWith("${width}_") && f.extension == "f32" }?.sortedBy { it.name } ?: return ""
        val input = model.createInputBuffers().first()
        val output = model.createOutputBuffers().first()
        var same = 0
        var ms = 0L
        for (f in inputs) {
            val bytes = f.readBytes()
            val data = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data)
            input.writeFloat(data)
            val t0 = SystemClock.elapsedRealtime()
            model.run(listOf(input), listOf(output))
            val out = output.readFloat()
            ms += SystemClock.elapsedRealtime() - t0
            val steps = out.size / VOCAB
            val ids = ArrayList<Int>()
            var last = -1
            for (t in 0 until steps) {
                var best = 0
                var bv = out[t * VOCAB]
                for (v in 1 until VOCAB) if (out[t * VOCAB + v] > bv) { bv = out[t * VOCAB + v]; best = v }
                if (best != 0 && best != last) ids.add(best)
                last = best
            }
            val expected = File(dir, f.nameWithoutExtension + ".exp").readText().trim()
            if (ids.joinToString(",") == expected) same++
        }
        return " identical $same/${inputs.size}, avg ${ms / inputs.size.coerceAtLeast(1)} ms/run"
    }

    private companion object {
        const val TAG = "OcrGpuProbe"
        const val VOCAB = 11947
    }
}
