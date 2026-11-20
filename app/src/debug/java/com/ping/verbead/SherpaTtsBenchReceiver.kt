package com.ping.verbead

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only TTS benchmark (phase 0 of the read-aloud feature): loads a sherpa-onnx TTS model from
 * <external files>/tts/<dir>/ and logs load time, memory, real-time factor (RTF) and time to first audio
 * for a few Chinese / English sentences. The audio is saved as wav next to the model so it can be listened to.
 *
 *   adb shell am broadcast -n <pkg>/com.ping.verbead.SherpaTtsBenchReceiver \
 *       --es model melo|kokoro --es dir vits-melo-tts-zh_en [--ei threads 4] [--es text "..."]
 *   adb logcat -s TtsBench
 *
 * Model folders are the extracted tts-models releases of sherpa-onnx (vits-melo-tts-zh_en,
 * kokoro-multi-lang-v1_1). RTF < 1 means faster than playback; for sentence-by-sentence streaming
 * it should stay below about 0.7 on the target phone.
 */
class SherpaTtsBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "tts-bench") {
            try {
                run(app, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun run(app: Context, intent: Intent) {
        val kind = intent.getStringExtra("model") ?: "melo"
        val root = File(File(app.getExternalFilesDir(null), "tts"), intent.getStringExtra("dir") ?: "")
        val threads = intent.getIntExtra("threads", 4)
        if (!root.isDirectory) {
            Log.e(TAG, "model folder not found: $root")
            return
        }
        val base = root.path
        val modelConfig = when (kind) {
            "kokoro" -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$base/model.onnx",
                    voices = "$base/voices.bin",
                    tokens = "$base/tokens.txt",
                    dataDir = "$base/espeak-ng-data",
                    dictDir = "$base/dict",
                    lexicon = "$base/lexicon-us-en.txt,$base/lexicon-zh.txt",
                ),
                numThreads = threads,
            )
            else -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$base/model.onnx",
                    lexicon = "$base/lexicon.txt",
                    tokens = "$base/tokens.txt",
                    dictDir = "$base/dict",
                ),
                numThreads = threads,
            )
        }
        val ruleFsts = if (kind == "kokoro") {
            listOf("date-zh.fst", "number-zh.fst", "phone-zh.fst")
        } else {
            listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")
        }.map { "$base/$it" }.filter { File(it).exists() }.joinToString(",")

        val pssBefore = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        var t = System.nanoTime()
        val tts = OfflineTts(config = OfflineTtsConfig(model = modelConfig, ruleFsts = ruleFsts))
        val loadMs = (System.nanoTime() - t) / 1e6
        val pssAfter = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        Log.i(TAG, "model=$kind load=%.0fms sampleRate=${tts.sampleRate()} speakers=${tts.numSpeakers()} pss=+%dMB cores=${Runtime.getRuntime().availableProcessors()} threads=$threads"
            .format(loadMs, (pssAfter - pssBefore) / 1024))

        val custom = intent.getStringExtra("text")
        val texts = if (custom != null) listOf("custom" to custom) else listOf(
            "zh_simp" to "今天天气很好，我们一起去公园散步吧。",
            "zh_trad" to "今天天氣很好，我們一起去公園散步吧。",
            "zh_tw_words" to "請問軟體更新後，網路連線品質有沒有改善？",
            "mixed" to "請把 GPU 驅動程式更新到 4090 專用版本。",
            "en" to "The quick brown fox jumps over the lazy dog.",
            "long" to "珠璣是一款本機離線輸入助手，它以桌面懸浮球的形式存在，不必更換你慣用的鍵盤，就能在任何應用程式裡用語音快速填入文字。",
        )
        // The first run is a warm-up (graph allocation, caches) and is reported separately.
        tts.generate(texts.first().second)
        for ((name, text) in texts) {
            var firstMs = -1.0
            t = System.nanoTime()
            val audio = tts.generateWithCallback(text) { _ ->
                if (firstMs < 0) firstMs = (System.nanoTime() - t) / 1e6
                1 // keep going
            }
            val synthMs = (System.nanoTime() - t) / 1e6
            val sec = audio.samples.size.toDouble() / audio.sampleRate
            Log.i(TAG, "%-12s chars=%3d audio=%.2fs synth=%.0fms RTF=%.3f ttfa=%.0fms".format(
                name, text.length, sec, synthMs, if (sec > 0) synthMs / 1000 / sec else -1.0, firstMs))
            audio.save(File(root, "bench_${kind}_$name.wav").path)
        }
        Log.i(TAG, "DONE (wav files are in $root)")
    }

    private companion object { const val TAG = "TtsBench" }
}
