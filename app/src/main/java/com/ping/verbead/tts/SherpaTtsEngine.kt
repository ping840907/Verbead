package com.ping.verbead.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.ping.verbead.engine.TtsKind
import com.ping.verbead.engine.TtsModel
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A downloaded read-aloud model (Melo or Kokoro) run by sherpa-onnx.
 *
 * Two threads: one synthesizes, one plays. The sentence after the current one is synthesized while the current
 * one is still playing (see [prefetch]), so the gap between sentences is mostly gone. Callbacks reach the
 * [TtsEngine.Callback] on the main thread, like every engine.
 *
 * [sidFor] says which voice (speaker id) to use for a language. [threads] defaults to 2: measured on the
 * target phone, two threads were faster than four.
 */
class SherpaTtsEngine(
    private val model: TtsModel,
    private val root: File,
    private val sidFor: (SpeechLang) -> Int,
    private val threads: Int = 2,
) : TtsEngine {

    private val main = Handler(Looper.getMainLooper())
    private val synthPool = Executors.newSingleThreadExecutor(named("sherpa-synth"))
    private val playPool = Executors.newSingleThreadExecutor(named("sherpa-play"))

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var closed = false
    @Volatile private var track: AudioTrack? = null
    private var callback: TtsEngine.Callback? = null

    /** Bumped whenever playback must stop (a new sentence, a stop): the play thread checks it between writes. */
    private val playGen = AtomicInteger()

    /** Bumped when synthesis that is already running is no longer wanted: the synth callback returns 0. */
    private val epoch = AtomicInteger()

    private data class Key(val text: String, val sid: Int, val rate: Float)
    private class Pending(val key: Key, val future: Future<FloatArray?>)

    private var prefetched: Pending? = null // main thread only

    // ── TtsEngine ───────────────────────────────────────────────────────

    override fun init(callback: TtsEngine.Callback, onReady: (Boolean) -> Unit) {
        this.callback = callback
        synthPool.execute {
            val loaded = try {
                load()
            } catch (t: Throwable) {
                Log.e(TAG, "failed to load ${model.id}", t)
                null
            }
            if (closed) {
                loaded?.release()
                return@execute
            }
            tts = loaded
            main.post { if (!closed) onReady(loaded != null) }
        }
    }

    override fun speak(utteranceId: Int, text: String, lang: SpeechLang, rate: Float) {
        if (closed || tts == null) return
        val key = Key(text, sidFor(lang), rate)
        val gen = playGen.incrementAndGet()
        val cached = prefetched?.takeIf { it.key == key }
        prefetched = null
        val future = cached?.future ?: submit(key, epoch.incrementAndGet()) // a different sentence: drop stale work
        playPool.execute { play(utteranceId, gen, future) }
    }

    override fun prefetch(text: String, lang: SpeechLang, rate: Float) {
        if (closed || tts == null) return
        val key = Key(text, sidFor(lang), rate)
        if (prefetched?.key == key) return
        prefetched = Pending(key, submit(key, epoch.get()))
    }

    override fun stop() {
        playGen.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    override fun shutdown() {
        if (closed) return
        closed = true
        playGen.incrementAndGet()
        epoch.incrementAndGet()
        prefetched = null
        callback = null
        playPool.shutdownNow()
        // release on the synth thread, after any synthesis in progress has noticed `closed` and returned
        synthPool.execute {
            tts?.release()
            tts = null
        }
        synthPool.shutdown()
    }

    // ── Loading ─────────────────────────────────────────────────────────

    private fun load(): OfflineTts {
        val base = root.path
        val modelConfig = when (model.kind) {
            TtsKind.KOKORO -> OfflineTtsModelConfig(
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
            TtsKind.VITS_MELO -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$base/model.onnx",
                    lexicon = "$base/lexicon.txt",
                    tokens = "$base/tokens.txt",
                    dictDir = "$base/dict",
                ),
                numThreads = threads,
            )
        }
        val ruleFsts = when (model.kind) {
            TtsKind.KOKORO -> listOf("date-zh.fst", "number-zh.fst", "phone-zh.fst")
            TtsKind.VITS_MELO -> listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst")
        }.map { "$base/$it" }.filter { File(it).exists() }.joinToString(",")
        return OfflineTts(config = OfflineTtsConfig(model = modelConfig, ruleFsts = ruleFsts))
    }

    // ── Synthesis ───────────────────────────────────────────────────────

    private fun submit(key: Key, myEpoch: Int): Future<FloatArray?> = try {
        synthPool.submit(Callable { synthesize(key, myEpoch) })
    } catch (e: Exception) {
        // the pool is shut down: hand back a finished, empty result
        java.util.concurrent.CompletableFuture.completedFuture(null)
    }

    private fun synthesize(key: Key, myEpoch: Int): FloatArray? {
        val engine = tts ?: return null
        // sherpa calls this by name through JNI: it must be a real class with invoke([F)Ljava/lang/Integer;, not a lambda
        val keepGoing = object : (FloatArray) -> Int {
            override fun invoke(samples: FloatArray): Int = if (closed || epoch.get() != myEpoch) 0 else 1
        }
        return try {
            val audio = engine.generateWithCallback(key.text, sid = key.sid, speed = key.rate, callback = keepGoing)
            if (closed || epoch.get() != myEpoch) null else audio.samples
        } catch (t: Throwable) {
            Log.e(TAG, "synthesis failed", t)
            null
        }
    }

    // ── Playback (play thread) ──────────────────────────────────────────

    private fun play(id: Int, gen: Int, future: Future<FloatArray?>) {
        val samples = awaitSamples(id, gen, future) ?: return
        if (gen != playGen.get() || closed) return
        val rate = tts?.sampleRate() ?: return
        val at = createTrack(rate, samples.size)
        track = at
        try {
            at.play()
            var off = 0
            while (off < samples.size) {
                if (gen != playGen.get() || closed) return
                val n = at.write(samples, off, minOf(CHUNK, samples.size - off), AudioTrack.WRITE_BLOCKING)
                if (n < 0) { fail(id, gen); return }
                off += n
            }
            at.stop() // a stream track keeps playing what it has buffered, then stops
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(samples.size * 1000L / rate + 1500)
            while (at.playbackHeadPosition < samples.size && System.nanoTime() < deadline) {
                if (gen != playGen.get() || closed) return
                Thread.sleep(15)
            }
            if (gen == playGen.get() && !closed) main.post { if (gen == playGen.get()) callback?.onDone(id) }
        } catch (e: InterruptedException) {
            // shutdown
        } finally {
            if (track === at) track = null
            runCatching { at.release() }
        }
    }

    /** Waits for the synthesized audio without ever blocking a stop for long. Null means nothing to play (already reported). */
    private fun awaitSamples(id: Int, gen: Int, future: Future<FloatArray?>): FloatArray? {
        while (true) {
            if (gen != playGen.get() || closed) return null
            try {
                val samples = future.get(40, TimeUnit.MILLISECONDS)
                if (samples == null || samples.isEmpty()) { fail(id, gen); return null }
                return samples
            } catch (e: TimeoutException) {
                continue
            } catch (e: ExecutionException) {
                fail(id, gen)
                return null
            } catch (e: InterruptedException) {
                return null
            }
        }
    }

    private fun fail(id: Int, gen: Int) {
        if (gen == playGen.get() && !closed) main.post { if (gen == playGen.get()) callback?.onError(id) }
    }

    private fun createTrack(sampleRate: Int, frames: Int): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // about half a second of buffer, never less than the minimum, never more than the whole sentence needs
        val bytes = maxOf(minBytes, minOf(sampleRate * 2, frames * 4))
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun named(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

    private companion object {
        const val TAG = "SherpaTtsEngine"
        const val CHUNK = 4096
    }
}
