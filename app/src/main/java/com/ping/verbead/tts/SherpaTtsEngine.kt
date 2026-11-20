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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A downloaded read-aloud model (Melo or Kokoro) run by sherpa-onnx.
 *
 * Two threads: one synthesizes, one plays. Sentences the caller queues ([queue]) are synthesized while the current
 * one is still playing and written to one audio track right behind it, so the next sentence starts the moment
 * the last one ends. The silence the model puts around each sentence is cut off and replaced by a pause of a
 * chosen length ([SpeechAudio]), so the gap between sentences is the one wanted, not the model's.
 *
 * Synthesized sentences are kept (the last few), so pausing and continuing, or stepping back a sentence, is
 * instant. Callbacks reach the [TtsEngine.Callback] on the main thread, like every engine.
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

    /** Bumped by [speak] and [stop]: everything queued before is dropped and the audio track is started afresh. */
    private val playGen = AtomicInteger()

    /** Bumped when synthesis that is already running is no longer wanted: the synth callback returns 0. */
    private val epoch = AtomicInteger()

    private data class Key(val text: String, val sid: Int, val rate: Float)

    /** One sentence waiting to be played (or being played). */
    private class Item(val id: Int, val gen: Int, val text: String, val future: Future<FloatArray?>)

    /** A sentence whose audio is all in the track; it is done when the track has played up to [endFrame]. */
    private class Pending(val id: Int, val gen: Int, val endFrame: Long)

    private val items = LinkedBlockingQueue<Item>()
    private val pending = ConcurrentLinkedQueue<Pending>()

    /** The last few synthesized sentences by what they say; main thread only. */
    private val cache = object : LinkedHashMap<Key, Future<FloatArray?>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Future<FloatArray?>>?): Boolean = size > CACHE_SIZE
    }

    // play thread only
    private var trackGen = -1
    private var framesWritten = 0L

    // main thread only
    private var checking = false

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
            if (loaded != null) playPool.execute { playLoop() }
            main.post { if (!closed) onReady(loaded != null) }
        }
    }

    override fun speak(utteranceId: Int, text: String, lang: SpeechLang, rate: Float) {
        if (closed || tts == null) return
        val gen = playGen.incrementAndGet()
        items.clear()
        // a sentence not heard before: cut short any synthesis still running for something else
        val future = obtain(Key(text, sidFor(lang), rate), cancelOthers = true)
        items.put(Item(utteranceId, gen, text, future))
        cut()
    }

    override fun queue(utteranceId: Int, text: String, lang: SpeechLang, rate: Float): Boolean {
        if (closed || tts == null) return false
        val future = obtain(Key(text, sidFor(lang), rate), cancelOthers = false)
        items.put(Item(utteranceId, playGen.get(), text, future))
        return true
    }

    override fun stop() {
        playGen.incrementAndGet()
        items.clear()
        cut()
    }

    override fun shutdown() {
        if (closed) return
        closed = true
        playGen.incrementAndGet()
        epoch.incrementAndGet()
        items.clear()
        pending.clear()
        cache.clear()
        callback = null
        playPool.shutdownNow()
        // release on the synth thread, after any synthesis in progress has noticed `closed` and returned
        synthPool.execute {
            tts?.release()
            tts = null
        }
        synthPool.shutdown()
    }

    /** Silences what is playing now. The play thread starts a new track for whatever comes next. */
    private fun cut() {
        track?.let { runCatching { it.pause(); it.flush() } }
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

    /**
     * The audio for [key]: the one already synthesized (or being synthesized), else a new request. A request that
     * was cut short, or failed, is not reused.
     */
    private fun obtain(key: Key, cancelOthers: Boolean): Future<FloatArray?> {
        cache[key]?.let { hit ->
            val spoiled = hit.isDone && runCatching { hit.get() }.getOrNull() == null
            if (!spoiled) return hit
            cache.remove(key)
        }
        val e = if (cancelOthers) epoch.incrementAndGet() else epoch.get()
        val f = submit(key, e)
        cache[key] = f
        return f
    }

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
            if (closed || epoch.get() != myEpoch) null
            else SpeechAudio.shape(audio.samples, engine.sampleRate(), SpeechAudio.pauseMs(key.text))
        } catch (t: Throwable) {
            Log.e(TAG, "synthesis failed", t)
            null
        }
    }

    // ── Playback (play thread) ──────────────────────────────────────────

    private fun playLoop() {
        try {
            while (!closed) {
                val item = items.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (item.gen != playGen.get()) continue
                val samples = awaitSamples(item) ?: continue
                write(item, samples)
            }
        } catch (e: InterruptedException) {
            // shutdown
        } finally {
            track?.let { runCatching { it.pause(); it.flush(); it.release() } }
            track = null
        }
    }

    /** Waits for [item]'s audio without ever blocking a stop for long. Null means nothing to play (already reported). */
    private fun awaitSamples(item: Item): FloatArray? {
        while (true) {
            if (item.gen != playGen.get() || closed) return null
            try {
                val samples = item.future.get(40, TimeUnit.MILLISECONDS)
                if (samples == null || samples.isEmpty()) { fail(item); return null }
                return samples
            } catch (e: TimeoutException) {
                continue
            } catch (e: ExecutionException) {
                fail(item)
                return null
            } catch (e: InterruptedException) {
                throw e
            }
        }
    }

    /** Writes [item]'s audio into the track right behind what is already there. */
    private fun write(item: Item, samples: FloatArray) {
        val rate = tts?.sampleRate() ?: return
        var at = track
        if (at == null || trackGen != item.gen) {
            at?.let { runCatching { it.pause(); it.flush(); it.release() } }
            at = createTrack(rate)
            track = at
            trackGen = item.gen
            framesWritten = 0
            at.play()
        }
        val head = at.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        Log.d(TAG, "SENTENCE id=${item.id} starts with %.0fms still buffered (negative = a gap)".format(
            (framesWritten - head) * 1000.0 / rate))

        var off = 0
        while (off < samples.size) {
            if (item.gen != playGen.get() || closed) return
            val n = at.write(samples, off, minOf(CHUNK, samples.size - off), AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) { fail(item); return }
            if (n == 0) Thread.sleep(4) else off += n
        }
        framesWritten += samples.size
        pending.add(Pending(item.id, item.gen, framesWritten))
        main.post { ensureChecking() }
    }

    // ── Reporting (main thread) ─────────────────────────────────────────

    private fun ensureChecking() {
        if (checking || closed) return
        checking = true
        main.post(checker)
    }

    /** Reports each sentence as done when the track has played to the end of it. */
    private val checker = object : Runnable {
        override fun run() {
            if (closed) { checking = false; return }
            val at = track
            val gen = playGen.get()
            val head = if (at != null) at.playbackHeadPosition.toLong() and 0xFFFFFFFFL else 0L
            while (true) {
                val p = pending.peek() ?: break
                if (p.gen != gen) { pending.poll(); continue } // cut off
                if (at != null && head >= p.endFrame) {
                    pending.poll()
                    callback?.onDone(p.id)
                } else break
            }
            if (pending.isNotEmpty()) main.postDelayed(this, 15) else checking = false
        }
    }

    private fun fail(item: Item) {
        main.post { if (!closed && item.gen == playGen.get()) callback?.onError(item.id) }
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // about half a second of buffer: enough to ride over the moment between one sentence and the next
        val bytes = maxOf(minBytes, sampleRate * 2)
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
        const val CACHE_SIZE = 6
    }
}
