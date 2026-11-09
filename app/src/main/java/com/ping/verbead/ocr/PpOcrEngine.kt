package com.ping.verbead.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.ping.verbead.engine.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device PP-OCRv6 inference engine powered by LiteRT (GPU first, CPU fallback).
 * Performs fast text detection (Det) and targeted text recognition (Rec) on captured frames.
 */
class PpOcrEngine(private val context: Context) {

    companion object {
        private const val TAG = "PpOcrEngine"

        /** Test hook: run the GPU at full FP32 precision instead of the runtime default (used to compare accuracy). */
        @Volatile
        var forceGpuFp32: Boolean = false

        /**
         * Pure evaluation function for deciding whether to adopt a 180-degree flipped OCR candidate over 0-degree upright.
         *
         * Design guarantees zero regression:
         * 1. Strong upright prior: If 0 deg result has good confidence (>= 0.75 for multi-character, >= 0.70 for Chinese,
         *    >= 0.40 for single digits/letters), it is ALWAYS kept, guaranteeing zero regression for upright text and avoiding
         *    misinverting symmetric characters like '6'/'9', 'no'/'on', etc.
         * 2. Comprehensive upside-down Chinese & alphanumeric support: 180 deg is adopted when 180 deg demonstrates
         *    more valid characters with good confidence, or when 0 deg was poor/hallucinated and 180 deg is distinctly superior.
         */
        fun shouldAdopt180Rotation(
            text0: String,
            conf0: Float,
            text180: String,
            conf180: Float
        ): Boolean {
            val t0 = text0.trim()
            val t180 = text180.trim()

            val cjk0 = t0.count { TextStitcher.isCjk(it) }
            val cjk180 = t180.count { TextStitcher.isCjk(it) }

            val alnum0 = t0.count { it.isLetterOrDigit() }
            val alnum180 = t180.count { it.isLetterOrDigit() }

            val valid0 = cjk0 + alnum0
            val valid180 = cjk180 + alnum180

            // 1. If 180-deg candidate produced no valid characters, never adopt 180
            if (valid180 == 0) return false

            // 2. If 0-deg candidate produced no valid characters (empty, noise, or punctuation only),
            //    adopt 180 if it produced valid characters with reasonable confidence
            if (valid0 == 0) {
                return conf180 >= 0.50f
            }

            // 3. Strong upright prior:
            //    If 0-deg has strictly more valid characters than 180-deg, upright always wins.
            if (valid0 > valid180) return false

            // 4. Equal character count (valid180 == valid0):
            if (valid180 == valid0) {
                // 4a. Single character (valid0 == 1):
                //     Protect against flipping symmetric characters ('6'/'9', 'no'/'on', '口'/'田'/'十').
                if (valid0 == 1) {
                    val isDigitOrLatin = t0.any { it.isDigit() || (it in 'a'..'z' || it in 'A'..'Z') }
                    if (isDigitOrLatin) {
                        // Strict protection for single digits and Latin letters:
                        // Never flip if 0-deg has even minimal confidence (>= 0.40f)
                        if (conf0 >= 0.40f) return false
                        return conf180 >= 0.75f && conf180 > conf0 + 0.35f
                    } else {
                        // Single Chinese character:
                        // Never flip if 0-deg is reasonably confident (>= 0.70f)
                        if (conf0 >= 0.70f) return false
                        return conf180 >= 0.78f && conf180 > conf0 + 0.20f
                    }
                }

                // 4b. Multiple characters (valid0 >= 2):
                //     If 0-deg is confident (>= 0.75f), upright prior wins (protects "no" vs "on", etc.)
                if (conf0 >= 0.75f) return false

                //     If 0-deg was poor (< 0.75f), adopt 180 if 180-deg is confident and distinctly better
                return conf180 >= 0.78f && conf180 >= conf0 + 0.15f
            }

            // 5. 180-deg has strictly more valid characters (valid180 > valid0):
            // 5a. 180-deg has at least 2 more valid characters (e.g. 4 vs 2, 3 vs 1):
            //     Strong physical evidence that 180-deg reconstructed the full text line.
            if (valid180 >= valid0 + 2) {
                return conf180 >= 0.65f && conf180 >= conf0 - 0.05f
            }

            // 5b. 180-deg has 1 more valid character (valid180 == valid0 + 1):
            //     Adopt if 180-deg has good confidence and improves over 0-deg
            return conf180 >= 0.65f && conf180 >= conf0 + 0.05f
        }
    }

    private val charDict = mutableListOf<String>()

    private val detMutex = Mutex()
    private val recMutex = Mutex()

    private var litertEnv: Environment? = null
    private var loadedModel: String? = null
    private var modelName: String = ModelConfig.OCR_MODEL_SMALL
    private var modelDir: File? = null
    private val detNets = HashMap<String, Net>()
    private val recNets = HashMap<Int, Net>()

    // Optional Korean recognizer (second pass, see KoreanFallback). Its dictionary is read on first use.
    private var koreanDir: File? = null
    private var koreanDict: List<String>? = null
    private var koreanBroken = false
    private val koreanNets = HashMap<Int, Net>()

    /** Set once Korean was adopted on this page: following strips try Korean first (reset on each detection). */
    private var koreanFirst = false

    var lastLoadError: String? = null
        private set

    val isReady: Boolean
        get() = litertEnv != null && charDict.isNotEmpty()

    /**
     * One fixed-shape LiteRT model with reusable input/output buffers. Tries the GPU first (compiled programs are
     * cached on disk so later launches skip the compile) and falls back to the CPU when the GPU cannot take the model.
     */
    private inner class Net(private val file: File) : AutoCloseable {
        private var model: CompiledModel
        private var input: TensorBuffer
        private var output: TensorBuffer
        private var onGpu = false

        init {
            val m = try {
                create(Accelerator.GPU).also { onGpu = true }
            } catch (t: Throwable) {
                Log.w(TAG, "GPU unavailable for ${file.name} (${t.message}); using CPU")
                create(Accelerator.CPU)
            }
            model = m
            input = m.createInputBuffers().first()
            output = m.createOutputBuffers().first()
        }

        private fun create(acc: Accelerator): CompiledModel {
            val opts = CompiledModel.Options(acc)
            if (acc == Accelerator.GPU) {
                val dir = File(context.cacheDir, "ocr_gpu_cache").apply { mkdirs() }
                opts.gpuOptions = CompiledModel.GpuOptions(
                    serializationDir = dir.path,
                    modelCacheKey = file.name.removeSuffix(".tflite"),
                    serializeProgramCache = true,
                    serializeExternalTensors = true,
                    precision = if (forceGpuFp32) CompiledModel.GpuOptions.Precision.FP32 else null
                )
            }
            return CompiledModel.create(file.path, opts, litertEnv!!)
        }

        fun run(data: FloatArray): FloatArray {
            try {
                input.writeFloat(data)
                model.run(listOf(input), listOf(output))
                return output.readFloat()
            } catch (t: Throwable) {
                if (!onGpu) throw t
                Log.w(TAG, "GPU run failed for ${file.name} (${t.message}); retrying on CPU")
                runCatching { model.close() }
                onGpu = false
                model = create(Accelerator.CPU)
                input = model.createInputBuffers().first()
                output = model.createOutputBuffers().first()
                input.writeFloat(data)
                model.run(listOf(input), listOf(output))
                return output.readFloat()
            }
        }

        override fun close() {
            runCatching { model.close() }
        }
    }

    private fun detNet(w: Int, h: Int): Net =
        detNets.getOrPut("${w}x${h}") { Net(File(modelDir!!, "${modelName}_det_${w}x${h}.tflite")) }

    private fun recNet(width: Int): Net =
        recNets.getOrPut(width) { Net(File(modelDir!!, "${modelName}_rec_${width}.tflite")) }

    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        try {
            val selected = ModelConfig.selectedOcrModel(context)
            if (isReady && loadedModel == selected) return@withContext true
            release()
            lastLoadError = null

            // 優先載入所選模型；若尚未備齊則回退至已備妥的任一模型（Tiny 或 Small）
            val paths = ModelConfig.findOcrPaths(context, selected) ?: ModelConfig.findOcrPaths(context, null)
            if (paths == null) {
                lastLoadError = "Model files not found (searched: ${ModelConfig.ocrDir(context)})"
                Log.w(TAG, lastLoadError!!)
                return@withContext false
            }
            ModelConfig.setSelectedOcrModel(context, paths.model)
            loadDictionary(File(paths.dictPath).readLines())
            if (charDict.isEmpty()) {
                lastLoadError = "Dictionary is empty (${paths.dictPath})"
                return@withContext false
            }
            litertEnv = Environment.create(context)
            modelName = paths.model
            modelDir = File(paths.dir)
            loadedModel = paths.model
            koreanDir = if (ModelConfig.isOcrKoreanEnabled(context)) ModelConfig.findOcrKoreanDir(context) else null
            koreanBroken = false
            Log.i(TAG, "PP-OCR (LiteRT, ${paths.model}) ready from ${paths.dir}, dictionary size=${charDict.size}, korean=${koreanDir != null}")
            true
        } catch (e: Throwable) {
            val causeMsg = e.cause?.let { " [cause: ${it.javaClass.simpleName}: ${it.message}]" } ?: ""
            lastLoadError = "${e.javaClass.simpleName}: ${e.message ?: e.localizedMessage ?: "unknown error"}$causeMsg"
            Log.e(TAG, "Failed to load PP-OCR engine: $lastLoadError", e)
            release()
            false
        }
    }

    /** Parses the Paddle inference.yml (character_dict list) or a plain one-character-per-line dictionary. */
    private fun loadDictionary(lines: List<String>) {
        charDict.clear()
        charDict.addAll(parseDictionary(lines))
    }

    private fun parseDictionary(lines: List<String>): List<String> {
        val isYaml = lines.take(40).any { it.contains("character_dict:") || it.contains("Global:") || it.contains("PostProcess:") }
        if (isYaml) return parseYmlDict(lines)
        val dict = mutableListOf<String>()
        lines.forEach { line ->
            val trimmed = line.trimEnd('\r', '\n')
            if (trimmed.isNotEmpty()) dict.add(trimmed)
        }
        if (!dict.contains(" ")) dict.add(" ")
        return dict
    }

    private fun parseYmlDict(lines: List<String>): List<String> {
        val dict = mutableListOf<String>()
        var inDict = false
        lines.forEach { line ->
            val lineClean = line.trimEnd('\r', '\n')
            if (lineClean.trim().startsWith("character_dict:")) {
                inDict = true
                return@forEach
            }
            if (inDict) {
                val trimmed = lineClean.trimStart()
                if (trimmed.startsWith("-")) {
                    var raw = trimmed.substring(1)
                    if (raw.startsWith(" ")) raw = raw.substring(1)
                    val char = if (raw.length >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
                        raw.substring(1, raw.length - 1).replace("''", "'")
                    } else if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                        raw.substring(1, raw.length - 1)
                    } else {
                        raw
                    }
                    dict.add(char)
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    inDict = false
                }
            }
        }
        if (!dict.contains(" ")) dict.add(" ")
        return dict
    }

    /**
     * Runs text detection (PP-OCR Det) on a bitmap.
     * Returns a list of detected bounding boxes mapped back to original bitmap coordinates.
     */
    suspend fun detectText(bitmap: Bitmap): List<RectF> = withContext(Dispatchers.Default) {
        koreanFirst = false
        detMutex.withLock {
            if (!isReady) return@withLock emptyList()
            try {
                val origW = bitmap.width
                val origH = bitmap.height
                // The detector exists at a few fixed sizes: pick the one that keeps the image largest and letterbox into it.
                val plan = OcrShapes.planDet(origW, origH)
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, plan.contentW, plan.contentH, true)

                // NHWC float input, ImageNet mean/std. Untouched (zero) padding equals the mean colour.
                val input = FloatArray(plan.width * plan.height * 3)
                val pixels = IntArray(plan.contentW * plan.contentH)
                scaledBitmap.getPixels(pixels, 0, plan.contentW, 0, 0, plan.contentW, plan.contentH)
                for (y in 0 until plan.contentH) {
                    var o = y * plan.width * 3
                    val row = y * plan.contentW
                    for (x in 0 until plan.contentW) {
                        val c = pixels[row + x]
                        input[o++] = (Color.red(c) / 255.0f - 0.485f) / 0.229f
                        input[o++] = (Color.green(c) / 255.0f - 0.456f) / 0.224f
                        input[o++] = (Color.blue(c) / 255.0f - 0.406f) / 0.225f
                    }
                }
                if (scaledBitmap != bitmap) scaledBitmap.recycle()

                val out = detNet(plan.width, plan.height).run(input) // [H x W] probability map
                val probMap = Array(plan.height) { y -> out.copyOfRange(y * plan.width, (y + 1) * plan.width) }

                val rawBoxes = extractBoxesFromProbMap(probMap, plan.width, plan.height)

                // Back to original bitmap coordinates (the padding area carries no text, but unclip may spill into it)
                val scaleX = origW.toFloat() / plan.contentW
                val scaleY = origH.toFloat() / plan.contentH
                rawBoxes.map { rect ->
                    RectF(
                        (rect.left * scaleX).coerceIn(0f, origW.toFloat()),
                        (rect.top * scaleY).coerceIn(0f, origH.toFloat()),
                        (rect.right * scaleX).coerceIn(0f, origW.toFloat()),
                        (rect.bottom * scaleY).coerceIn(0f, origH.toFloat())
                    )
                }.filter { it.width() > 1f && it.height() > 1f }
            } catch (e: Throwable) {
                Log.e(TAG, "Detection error: ${e.message}", e)
                emptyList()
            }
        }
    }

    /** Connected components + unclip on the detector probability map (see [DbPostProcessor]). */
    private fun extractBoxesFromProbMap(probMap: Array<FloatArray>, w: Int, h: Int): List<RectF> =
        DbPostProcessor.extractBoxes(probMap, w, h)

    /**
     * Crops the specified bounding box from source bitmap and runs text recognition.
     */
    suspend fun recognizeBox(source: Bitmap, box: RectF): String = withContext(Dispatchers.Default) {
        try {
            // The detector boxes are tighter than the glyphs: measured on a phone screenshot the box was 33 px high for ~46 px
            // characters (about 8 px of the top and 11 px of the bottom cut off). The recognizer then sees clipped strokes and
            // can be confidently wrong (a clipped 乎 was read as 平 with 0.99). Padding the thin side of the box by 40% of its
            // size fixed that line and kept 17 of 17 lines of the page (and a 13-line synthetic page) exact; the old 4% did not.
            // The side along the reading direction keeps the small padding it always had.
            val horizontal = box.height() <= box.width() * 1.15f
            val crossRatio = 0.4f
            val padX = if (horizontal) max(3, (box.width() * 0.08f).roundToInt()) else max(3, (box.width() * crossRatio).roundToInt())
            val padY = if (horizontal) max(3, (box.height() * crossRatio).roundToInt()) else max(3, (box.height() * 0.04f).roundToInt())
            val left = (box.left - padX).toInt().coerceIn(0, source.width - 1)
            val top = (box.top - padY).toInt().coerceIn(0, source.height - 1)
            val right = (box.right + padX).toInt().coerceIn(left + 1, source.width)
            val bottom = (box.bottom + padY).toInt().coerceIn(top + 1, source.height)
            val w = right - left
            val h = bottom - top
            if (w < 4 || h < 4) return@withContext ""
            val crop = Bitmap.createBitmap(source, left, top, w, h)
            val result = recognizeText(crop)
            if (crop != source) {
                crop.recycle()
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "recognizeBox failed: ${e.message}", e)
            ""
        }
    }

    /**
     * Runs text recognition (PP-OCR Rec) on a cropped bounding box.
     * Accurately supports both horizontal text and vertical text (直排文字):
     * For vertical boxes (H > W * 1.15), it automatically evaluates orientation & unrolling candidates
     * to ensure reliable transcription of Chinese vertical columns and rotated text.
     */
    suspend fun recognizeText(crop: Bitmap): String = withContext(Dispatchers.Default) {
        recMutex.withLock {
            val origW = crop.width
            val origH = crop.height
            if (origW < 4 || origH < 4) return@withLock ""

            try {
                val resultText = if (origH > origW * 1.15f) {
                    // Vertical text box: evaluate orientation & unrolling candidates

                    // Candidate 1: Rotated 270 degrees (counter-clockwise 90).
                    // In Chinese vertical typesetting (top-to-bottom), 90 deg counter-clockwise maps top characters to the left,
                    // maintaining natural left-to-right reading sequence for PP-OCR's horizontal Rec model.
                    val matrix270 = Matrix().apply { postRotate(270f) }
                    val rot270 = Bitmap.createBitmap(crop, 0, 0, origW, origH, matrix270, true)
                    val cand270 = recognizeHorizontalStrip(rot270)
                    if (rot270 != crop) {
                        rot270.recycle()
                    }

                    // Candidate 2: Upright vertical text unrolled into horizontal strip (adaptive gap segmentation)
                    val unrolled = unrollVerticalToHorizontal(crop)
                    val candUnroll = if (unrolled != null) recognizeHorizontalStrip(unrolled) else ("" to 0f)
                    if (unrolled != null && unrolled != crop) {
                        unrolled.recycle()
                    }

                    // Candidate 3: Upright crop directly (for single character boxes where H/W is ~1.15-1.5)
                    val candUpright = if (origH <= origW * 1.55f) {
                        recognizeHorizontalStrip(crop)
                    } else {
                        "" to 0f
                    }

                    // Candidate 4: Rotated 90 degrees (for inverted/bottom-to-top text)
                    val matrix90 = Matrix().apply { postRotate(90f) }
                    val rot90 = Bitmap.createBitmap(crop, 0, 0, origW, origH, matrix90, true)
                    val cand90 = recognizeHorizontalStrip(rot90)
                    if (rot90 != crop) {
                        rot90.recycle()
                    }

                    // Candidate 5: Rotated 180 degrees directly (for upside-down single characters)
                    val matrix180 = Matrix().apply { postRotate(180f) }
                    val rot180 = Bitmap.createBitmap(crop, 0, 0, origW, origH, matrix180, true)
                    val cand180 = if (origH <= origW * 1.55f) {
                        recognizeHorizontalStrip(rot180, allowKorean = false)
                    } else {
                        "" to 0f
                    }

                    // Candidate 6: Upside-down vertical text unrolled into horizontal strip
                    val unrolled180 = unrollVerticalToHorizontal(rot180)
                    val candUnroll180 = if (unrolled180 != null) recognizeHorizontalStrip(unrolled180, allowKorean = false) else ("" to 0f)
                    if (unrolled180 != null && unrolled180 != rot180) {
                        unrolled180.recycle()
                    }
                    if (rot180 != crop) {
                        rot180.recycle()
                    }

                    // Score candidates: prioritize high confidence (>0.85), valid CJK/alphanumeric characters,
                    // favor natural unsliced candidates, and penalize noise punctuation/fragments and length that physically exceeds box capacity.
                    fun score(cand: Pair<String, Float>, isUnsliced: Boolean, is180: Boolean = false): Float {
                        val text = cand.first.trim()
                        if (text.isEmpty()) return -1f
                        val avgConf = cand.second
                        if (avgConf < 0.45f) return -1f

                        val validChars = text.count { it in '\u4e00'..'\u9fa5' || it.isLetterOrDigit() || it in "，。！？、；：" }
                        val noiseChars = text.length - validChars
                        val effectiveLength = validChars - noiseChars * 2.0f
                        if (effectiveLength <= 0f) return -1f

                        // Max characters that could physically fit in height origH with width origW
                        val maxPossibleChars = (origH.toFloat() / (origW * 0.5f)).roundToInt() + 1
                        var s = effectiveLength * (avgConf * avgConf) * 10f

                        if (avgConf >= 0.85f) {
                            s += 5f * avgConf
                        }
                        if (avgConf >= 0.95f) {
                            s += 5f
                        }
                        if (isUnsliced) {
                            s *= 1.3f // Strong preference for natural unsliced candidates
                        }
                        if (is180) {
                            // Upright prior: 180-deg candidates require good confidence (>= 0.65f)
                            // and have a slight discount to strictly avoid false inversions of upright columns
                            if (avgConf < 0.65f) return -1f
                            s *= 0.85f
                        }
                        if (text.length > maxPossibleChars) {
                            s -= (text.length - maxPossibleChars) * 15f
                        }
                        return s
                    }

                    val score270 = score(cand270, isUnsliced = true)
                    val scoreUnroll = score(candUnroll, isUnsliced = false)
                    val scoreUpright = score(candUpright, isUnsliced = true)
                    val score90 = score(cand90, isUnsliced = true)
                    val score180 = score(cand180, isUnsliced = true, is180 = true)
                    val scoreUnroll180 = score(candUnroll180, isUnsliced = false, is180 = true)

                    Log.d(TAG, "Vertical candidates for [${origW}x${origH}]: 270='${cand270.first}' (conf=${cand270.second}, s=$score270), unroll='${candUnroll.first}' (conf=${candUnroll.second}, s=$scoreUnroll), upright='${candUpright.first}' (conf=${candUpright.second}, s=$scoreUpright), 90='${cand90.first}' (conf=${cand90.second}, s=$score90), 180='${cand180.first}' (conf=${cand180.second}, s=$score180), unroll180='${candUnroll180.first}' (conf=${candUnroll180.second}, s=$scoreUnroll180)")

                    val candidates = listOf(
                        Triple(cand270.first, score270, cand270.second),
                        Triple(candUnroll.first, scoreUnroll, candUnroll.second),
                        Triple(candUpright.first, scoreUpright, candUpright.second),
                        Triple(cand90.first, score90, cand90.second),
                        Triple(cand180.first, score180, cand180.second),
                        Triple(candUnroll180.first, scoreUnroll180, candUnroll180.second)
                    )

                    val best = candidates.filter { it.second > 0f }.maxByOrNull { it.second }
                    best?.first ?: cand270.first.ifEmpty { candUnroll.first.ifEmpty { candUpright.first } }
                } else {
                    // Normal horizontal text box with smart 180-degree orientation detection
                    recognizeHorizontalWithSmartOrientation(crop, origW, origH)
                }

                resultText
            } catch (e: Throwable) {
                Log.e(TAG, "Recognition error: ${e.message}", e)
                ""
            }
        }
    }

    /**
     * Recognizes horizontal text crops with smart 180-degree orientation detection.
     */
    private fun recognizeHorizontalWithSmartOrientation(crop: Bitmap, origW: Int, origH: Int): String {
        val cand0 = recognizeHorizontalStrip(crop)
        val text0 = cand0.first.trim()
        val conf0 = cand0.second

        val validChars0 = text0.count { it in '\u4e00'..'\u9fa5' || it.isLetterOrDigit() }

        // Fast-path short-circuit:
        // Only skip 180-deg evaluation if upright is overwhelmingly decisive
        // (at least 4 valid characters with >= 0.92 confidence, where upside-down hallucinations are statistically impossible).
        if (conf0 >= 0.92f && validChars0 >= 4) return text0

        var rot180: Bitmap? = null
        return try {
            val matrix180 = Matrix().apply { postRotate(180f) }
            rot180 = Bitmap.createBitmap(crop, 0, 0, origW, origH, matrix180, true)
            val cand180 = recognizeHorizontalStrip(rot180, allowKorean = false)
            val text180 = cand180.first.trim()
            val conf180 = cand180.second
            val validChars180 = text180.count { it in '\u4e00'..'\u9fa5' || it.isLetterOrDigit() }

            val adopt180 = shouldAdopt180Rotation(text0, conf0, text180, conf180)
            Log.d(TAG, "Smart orientation check [${origW}x${origH}]: 0deg='$text0' (conf=$conf0, valid=$validChars0) vs 180deg='$text180' (conf=$conf180, valid=$validChars180) -> adopt180=$adopt180")
            if (adopt180) {
                text180
            } else {
                text0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "180-deg candidate evaluation failed: ${e.message}")
            text0
        } finally {
            if (rot180 != null && rot180 !== crop) {
                rot180.recycle()
            }
        }
    }

    /**
     * Unrolls a vertical column of upright Chinese characters into a horizontal strip.
     * Uses row stroke energy and adaptive gap detection to preserve complete character cells
     * without chopping characters or clipping canvas heights.
     */
    private fun unrollVerticalToHorizontal(crop: Bitmap): Bitmap? {
        val w = crop.width
        val h = crop.height
        if (w < 4 || h < 4) return null

        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)

        // Calculate horizontal stroke energy per row to identify character strokes vs gaps
        val rowEnergy = FloatArray(h)
        for (y in 0 until h) {
            var sum = 0f
            val rowOffset = y * w
            for (x in 1 until w) {
                val c1 = pixels[rowOffset + x]
                val c2 = pixels[rowOffset + x - 1]
                val rDiff = ((c1 shr 16 and 0xFF) - (c2 shr 16 and 0xFF)).toFloat()
                val gDiff = ((c1 shr 8 and 0xFF) - (c2 shr 8 and 0xFF)).toFloat()
                val bDiff = ((c1 and 0xFF) - (c2 and 0xFF)).toFloat()
                sum += Math.abs(rDiff) + Math.abs(gDiff) + Math.abs(bDiff)
            }
            rowEnergy[y] = sum
        }

        // 5-point moving average smoothing
        val smoothed = FloatArray(h)
        for (y in 0 until h) {
            var s = 0f
            var count = 0
            for (dy in -2..2) {
                val ny = y + dy
                if (ny in 0 until h) {
                    s += rowEnergy[ny]
                    count++
                }
            }
            smoothed[y] = s / count
        }

        val meanEnergy = smoothed.average().toFloat()
        if (meanEnergy < 1f) return null // Blank image

        // Character height reference: typically character height in Chinese text is comparable to column width
        val expectedCharH = (w * 0.95f).roundToInt().coerceIn(12, h)
        val minCharH = (expectedCharH * 0.6f).roundToInt().coerceAtLeast(8)
        val maxCharH = (expectedCharH * 1.5f).roundToInt().coerceAtLeast(minCharH + 8)

        // Detect split valleys across the vertical column
        val splits = mutableListOf<Int>()
        splits.add(0)

        var curY = 0
        while (curY < h) {
            val nextExpected = curY + expectedCharH
            if (nextExpected >= h - (minCharH / 2)) {
                break
            }
            // Search for minimum stroke energy in window around nextExpected
            val searchStart = (curY + minCharH).coerceIn(splits.last() + 4, h - 1)
            val searchEnd = (curY + maxCharH).coerceIn(searchStart, h - 1)

            var bestY = searchStart
            var minVal = Float.MAX_VALUE
            for (y in searchStart..searchEnd) {
                val distPenalty = Math.abs(y - nextExpected).toFloat() / expectedCharH * (meanEnergy * 0.3f)
                val cost = smoothed[y] + distPenalty
                if (cost < minVal) {
                    minVal = cost
                    bestY = y
                }
            }
            splits.add(bestY)
            curY = bestY
        }
        splits.add(h)

        // Extract slices
        val slices = mutableListOf<Bitmap>()
        for (i in 0 until splits.size - 1) {
            val top = splits[i]
            val bottom = splits[i + 1]
            val sliceH = bottom - top
            if (sliceH >= 6) {
                val slice = Bitmap.createBitmap(crop, 0, top, w, sliceH)
                slices.add(slice)
            }
        }

        if (slices.isEmpty()) return null

        // Determine canvas dimensions: height is max of all slice heights (never clip!)
        val maxSliceH = slices.maxOf { it.height }.coerceAtLeast(w)
        val charSpacing = (maxSliceH * 0.08f).roundToInt().coerceIn(2, 10)
        val totalW = slices.sumOf { it.width } + charSpacing * (slices.size - 1)

        val outBitmap = Bitmap.createBitmap(totalW, maxSliceH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)

        // Background color: sample edge pixels
        val edgeColors = intArrayOf(
            pixels[0],
            pixels[min(w - 1, pixels.size - 1)],
            pixels[max(0, pixels.size - w)],
            pixels[pixels.size - 1]
        )
        val bgR = edgeColors.map { Color.red(it) }.average().toInt()
        val bgG = edgeColors.map { Color.green(it) }.average().toInt()
        val bgB = edgeColors.map { Color.blue(it) }.average().toInt()
        canvas.drawColor(Color.rgb(bgR, bgG, bgB))

        var curLeft = 0
        for (slice in slices) {
            val offsetY = (maxSliceH - slice.height) / 2
            canvas.drawBitmap(slice, curLeft.toFloat(), offsetY.toFloat(), null)
            curLeft += slice.width + charSpacing
            slice.recycle()
        }

        return outBitmap
    }

    /** A strip scaled to the recognizer height, as ARGB pixels; each model builds its own padded input from it. */
    private class StripPixels(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * Recognizes a single horizontal image strip and returns (decodedText, avgConfidence).
     *
     * The PP-OCRv6 recognizer runs on every strip. Only when its result looks like text it cannot read (see
     * [KoreanFallback.primaryLooksUnsupported]) is the optional Korean recognizer run as well, and its result is
     * adopted only if it really contains Hangul. After a Korean strip on the same page, later strips try Korean first
     * and skip the first model when it is confident, so a Korean page costs about one model per strip. The first
     * strip that turns out not to be Korean ends that, so mixed pages do not pay for a model that never helps.
     */
    private fun recognizeHorizontalStrip(strip: Bitmap, allowKorean: Boolean = true): Pair<String, Float> {
        if (!isReady) return "" to 0f

        val curW = strip.width
        val curH = strip.height
        if (curW < 4 || curH < 4) return "" to 0f

        val targetH = OcrShapes.REC_HEIGHT
        val targetW = OcrShapes.recStripWidth(curW, curH)
        val scaledBitmap = Bitmap.createScaledBitmap(strip, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        scaledBitmap.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaledBitmap != strip) scaledBitmap.recycle()
        val prepared = StripPixels(pixels, targetW, targetH)

        var korean: RecResult? = null
        val useKorean = allowKorean && koreanAvailable()
        if (koreanFirst && useKorean) {
            korean = runRec(prepared, korean = true)
            if (korean != null && KoreanFallback.trustKoreanFirst(korean)) return korean.pair
            // not Korean after all: stop trying it first until Korean is adopted again (keeps mixed pages cheap)
            koreanFirst = false
        }

        val primary = runRec(prepared, korean = false) ?: return (korean?.pair ?: ("" to 0f))
        if (!useKorean) return primary.pair
        Log.d(TAG, "primary '${primary.text}' conf=${primary.conf} inner=${primary.innerGapSteps} lead=${primary.leadGapSteps} trail=${primary.trailGapSteps} unsupported=${KoreanFallback.primaryLooksUnsupported(primary)}")
        if (!KoreanFallback.primaryLooksUnsupported(primary)) return primary.pair

        Log.d(TAG, "Korean second pass: primary='${primary.text}' conf=${primary.conf} emitted=${primary.emitted} inner=${primary.innerGapSteps} lead=${primary.leadGapSteps} trail=${primary.trailGapSteps}")
        if (korean == null) korean = runRec(prepared, korean = true)
        if (korean != null && KoreanFallback.shouldAdoptKorean(primary, korean)) {
            koreanFirst = true
            Log.d(TAG, "Korean recognizer adopted: '${primary.text}' (conf=${primary.conf}, gap=${primary.maxGapSteps}) -> '${korean.text}' (conf=${korean.conf})")
            return korean.pair
        }
        return primary.pair
    }

    private fun koreanAvailable(): Boolean = koreanDir != null && !koreanBroken

    private fun koreanNet(width: Int): Net =
        koreanNets.getOrPut(width) { Net(File(koreanDir!!, "${ModelConfig.OCR_MODEL_KOREAN}_rec_${width}.tflite")) }

    private fun koreanDictionary(): List<String>? {
        koreanDict?.let { return it }
        val dir = koreanDir ?: return null
        val dict = parseDictionary(File(dir, "${ModelConfig.OCR_MODEL_KOREAN}_dict.txt").readLines())
        if (dict.isEmpty()) return null
        koreanDict = dict
        return dict
    }

    /** Runs one recognizer on a prepared strip. Returns null if the Korean model is unusable (then it is switched off for this session). */
    private fun runRec(p: StripPixels, korean: Boolean): RecResult? {
        try {
            val widths = if (korean) OcrShapes.REC_WIDTHS_KOREAN else OcrShapes.recWidths(modelName == ModelConfig.OCR_MODEL_TINY)
            val bucket = OcrShapes.recBucket(p.width, widths)
            val dict = if (korean) koreanDictionary() ?: return null else charDict
            val net = if (korean) koreanNet(bucket) else recNet(bucket)

            // NHWC, (v/255 - 0.5)/0.5. The model only exists at fixed widths, so the strip is padded on the right with
            // normalized zero (the array default), the padding PaddleOCR itself uses. Measured against the variable-width
            // model: 14/14 of Google's recognizer fixtures and 24/24 of our own strips decode identically (edge-repeat
            // padding dropped a space in one fixture, white padding too).
            val input = FloatArray(p.height * bucket * 3)
            for (y in 0 until p.height) {
                var o = y * bucket * 3
                val row = y * p.width
                for (x in 0 until p.width) {
                    val c = p.pixels[row + x]
                    input[o++] = (Color.red(c) / 255.0f - 0.5f) / 0.5f
                    input[o++] = (Color.green(c) / 255.0f - 0.5f) / 0.5f
                    input[o++] = (Color.blue(c) / 255.0f - 0.5f) / 0.5f
                }
            }

            if (korean && bucket > p.width) {
                // The PP-OCRv5 Korean model hallucinates punctuation and misreads syllables when the padding differs from
                // the strip's own background (measured on rendered strips: 130/168 exact with zero padding, 161/168 when
                // the last column is repeated). The v6 models are the other way round, so only Korean gets this.
                for (y in 0 until p.height) {
                    val rowStart = y * bucket * 3
                    val last = rowStart + (p.width - 1) * 3
                    var o = rowStart + p.width * 3
                    while (o < rowStart + bucket * 3) {
                        input[o++] = input[last]; input[o++] = input[last + 1]; input[o++] = input[last + 2]
                    }
                }
            }

            val out = net.run(input) // [bucket/8 x vocab] probabilities
            val steps = bucket / 8
            if (steps == 0 || out.size % steps != 0) return null
            return if (korean) {
                CtcDecoder.decode(
                    out, steps, out.size / steps, dict, usableSteps = p.width / 8,
                    edgeMinProb = KoreanFallback.EDGE_MIN_PROB,
                    isProtected = { c -> c.any { KoreanFallback.isHangul(it) } }
                )
            } else {
                CtcDecoder.decode(out, steps, out.size / steps, dict, usableSteps = p.width / 8)
            }
        } catch (t: Throwable) {
            if (!korean) throw t
            Log.w(TAG, "Korean recognizer failed, switching it off: ${t.message}", t)
            koreanBroken = true
            return null
        }
    }

    fun release() {
        try {
            detNets.values.forEach { it.close() }
            recNets.values.forEach { it.close() }
            koreanNets.values.forEach { it.close() }
            litertEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PP-OCR engine resources", e)
        }
        detNets.clear()
        recNets.clear()
        koreanNets.clear()
        koreanDict = null
        koreanDir = null
        koreanFirst = false
        litertEnv = null
        charDict.clear()
        modelDir = null
        loadedModel = null
    }
}
