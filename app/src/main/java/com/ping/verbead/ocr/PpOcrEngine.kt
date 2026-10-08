package com.ping.verbead.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import com.ping.verbead.engine.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.util.LinkedList
import java.util.Queue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device PP-OCRv6 inference engine powered by ONNX Runtime.
 * Performs fast text detection (Det) and targeted text recognition (Rec) on captured frames.
 */
class PpOcrEngine(private val context: Context) {

    companion object {
        private const val TAG = "PpOcrEngine"
        private const val DET_MAX_SIDE = 736
        private const val REC_HEIGHT = 48
        private const val DB_THRESH = 0.2f
        private const val BOX_THRESH = 0.4f
        private const val UNCLIP_RATIO = 1.6f

        /**
         * Pure evaluation function for deciding whether to adopt a 180-degree flipped OCR candidate over 0-degree upright.
         *
         * Design guarantees zero regression:
         * 1. Strong upright prior: If 0 deg result has good confidence (>= 0.78 with valid chars, or >= 0.85),
         *    it is ALWAYS kept, guaranteeing zero regression for upright text and avoiding misinverting symmetric
         *    characters like '6'/'9', 'no'/'on', etc.
         * 2. Strict asymmetric bar: 180 deg is only adopted when 0 deg is weak/failing and 180 deg demonstrates
         *    substantially more valid characters and higher confidence.
         */
        fun shouldAdopt180Rotation(
            text0: String,
            conf0: Float,
            text180: String,
            conf180: Float
        ): Boolean {
            val t0 = text0.trim()
            val t180 = text180.trim()

            val validChars0 = t0.count { it in '\u4e00'..'\u9fa5' || it.isLetterOrDigit() }
            val validChars180 = t180.count { it in '\u4e00'..'\u9fa5' || it.isLetterOrDigit() }

            // Fast-path guard: if upright is confident and valid, never flip
            if (conf0 >= 0.78f && validChars0 >= 2) return false
            if (conf0 >= 0.85f && validChars0 >= 1) return false

            // Strict asymmetric adoption criteria:
            // Condition A: 180 deg has more valid characters, and good confidence (>= 0.65) that is at least 0.15 higher than 0 deg
            if (validChars180 > validChars0 && conf180 >= 0.65f && conf180 > conf0 + 0.15f) return true

            // Condition B: 0 deg produced ZERO valid characters (gibberish/empty/punctuation only), while 180 deg produced valid characters with decent confidence
            if (validChars180 > 0 && validChars0 == 0 && conf180 >= 0.55f) return true

            // Condition C: Equal character count (>= 2), but 180 deg is very high confidence (>= 0.80) while 0 deg was poor (< 0.45)
            if (validChars180 == validChars0 && validChars180 >= 2 && conf180 >= 0.80f && conf0 < 0.45f) return true

            return false
        }
    }

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private val charDict = mutableListOf<String>()

    private val detMutex = Mutex()
    private val recMutex = Mutex()

    private var loadedModel: String? = null
    var lastLoadError: String? = null
        private set

    val isReady: Boolean
        get() = detSession != null && recSession != null

    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefModel = ModelConfig.selectedOcrModel(context)
            if (isReady && loadedModel == prefModel) return@withContext true
            if (isReady && loadedModel != prefModel) {
                release()
            }
            lastLoadError = null

            // 優先載入所選模型；若尚未備齊則回退至已備妥的任一模型（Tiny 或 Small）
            var ocrPaths = ModelConfig.findOcrPaths(context, prefModel)
            if (ocrPaths == null) {
                ocrPaths = ModelConfig.findOcrPaths(context, null)
            }

            if (ocrPaths == null) {
                val searched = ModelConfig.ocrDir(context)
                lastLoadError = "找不到模型檔案 (已搜尋: $searched)"
                Log.w(TAG, lastLoadError!!)
                return@withContext false
            }

            val detPath = ocrPaths.detPath
            val recPath = ocrPaths.recPath
            val dictPath = ocrPaths.dictPath
            ModelConfig.setSelectedOcrModel(context, ocrPaths.model)
            loadedModel = ocrPaths.model

            Log.i(TAG, "Loading PP-OCR models (${ocrPaths.model}): det=$detPath, rec=$recPath, dict=$dictPath")

            // Explicitly preload native libraries to ensure linker resolves symbols cleanly
            try {
                System.loadLibrary("onnxruntime")
            } catch (t: Throwable) {
                Log.w(TAG, "Preload onnxruntime: ${t.message}")
            }
            try {
                System.loadLibrary("onnxruntime4j_jni")
            } catch (t: Throwable) {
                Log.w(TAG, "Preload onnxruntime4j_jni: ${t.message}")
            }

            env = try {
                OrtEnvironment.getEnvironment()
            } catch (t: Throwable) {
                val cause = t.cause
                val msg = if (cause != null) {
                    "${t.javaClass.simpleName}: ${t.message} [原因: ${cause.javaClass.simpleName}: ${cause.message}]"
                } else {
                    "${t.javaClass.simpleName}: ${t.message}"
                }
                lastLoadError = "OrtEnvironment 初始化失敗 ($msg)"
                Log.e(TAG, lastLoadError!!, t)
                return@withContext false
            }

            if (env == null) {
                lastLoadError = "OrtEnvironment 初始化失敗 (env 為 null)"
                Log.e(TAG, lastLoadError!!)
                return@withContext false
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)
                setIntraOpNumThreads(2)
            }

            detSession = try {
                env?.createSession(detPath, sessionOptions)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to create detSession with sessionOptions, retrying with default: ${e.message}")
                env?.createSession(detPath)
            }

            recSession = try {
                env?.createSession(recPath, sessionOptions)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to create recSession with sessionOptions, retrying with default: ${e.message}")
                env?.createSession(recPath)
            }

            try {
                loadDictionary(dictPath)
            } catch (e: Throwable) {
                Log.w(TAG, "Non-fatal error loading dictionary from $dictPath: ${e.message}", e)
            }

            Log.i(TAG, "PP-OCR engine loaded successfully! Dictionary size=${charDict.size}")
            true
        } catch (e: Throwable) {
            val causeMsg = e.cause?.let { " [原因: ${it.javaClass.simpleName}: ${it.message}]" } ?: ""
            lastLoadError = "${e.javaClass.simpleName}: ${e.message ?: e.localizedMessage ?: "未知錯誤"}$causeMsg"
            Log.e(TAG, "Failed to load PP-OCR engine: $lastLoadError", e)
            release()
            false
        }
    }

    private fun loadDictionary(dictPath: String) {
        charDict.clear()
        val file = File(dictPath)
        val targetFile = if (file.exists()) file else File(file.parentFile, "inference.yml").takeIf { it.exists() }
        if (targetFile == null || !targetFile.exists()) {
            Log.e(TAG, "Dictionary file not found: $dictPath")
            return
        }

        val isYaml = targetFile.useLines { lines ->
            lines.take(40).any { it.contains("character_dict:") || it.contains("Global:") || it.contains("PostProcess:") }
        }

        if (isYaml) {
            parseYmlDict(targetFile)
        } else {
            targetFile.forEachLine { line ->
                val trimmed = line.trimEnd('\r', '\n')
                if (trimmed.isNotEmpty()) {
                    charDict.add(trimmed)
                }
            }
            if (!charDict.contains(" ")) {
                charDict.add(" ")
            }
        }
        Log.i(TAG, "Loaded dictionary from ${targetFile.name}: ${charDict.size} characters (isYaml=$isYaml)")
    }

    private fun parseYmlDict(file: File) {
        charDict.clear()
        var inDict = false
        file.forEachLine { line ->
            val lineClean = line.trimEnd('\r', '\n')
            if (lineClean.trim().startsWith("character_dict:")) {
                inDict = true
                return@forEachLine
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
                    charDict.add(char)
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    inDict = false
                }
            }
        }
        if (!charDict.contains(" ")) {
            charDict.add(" ")
        }
    }

    /**
     * Runs text detection (PP-OCR Det) on a bitmap.
     * Returns a list of detected bounding boxes mapped back to original bitmap coordinates.
     */
    suspend fun detectText(bitmap: Bitmap): List<RectF> = withContext(Dispatchers.Default) {
        detMutex.withLock {
            val session = detSession ?: return@withLock emptyList()
            val ortEnv = env ?: return@withLock emptyList()

            try {
                val origW = bitmap.width
                val origH = bitmap.height

                // Calculate scaled dimensions (multiples of 32)
                var scale = 1.0f
                if (max(origW, origH) > DET_MAX_SIDE) {
                    scale = DET_MAX_SIDE.toFloat() / max(origW, origH)
                }
                var targetW = (origW * scale).toInt()
                var targetH = (origH * scale).toInt()
                targetW = max(32, (targetW / 32) * 32)
                targetH = max(32, (targetH / 32) * 32)

                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

                // Normalize CHW tensor: mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]
                val floatBuffer = FloatBuffer.allocate(1 * 3 * targetH * targetW)
                val pixels = IntArray(targetW * targetH)
                scaledBitmap.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

                val rPlane = FloatArray(targetW * targetH)
                val gPlane = FloatArray(targetW * targetH)
                val bPlane = FloatArray(targetW * targetH)

                for (i in pixels.indices) {
                    val color = pixels[i]
                    val r = Color.red(color) / 255.0f
                    val g = Color.green(color) / 255.0f
                    val b = Color.blue(color) / 255.0f

                    rPlane[i] = (r - 0.485f) / 0.229f
                    gPlane[i] = (g - 0.456f) / 0.224f
                    bPlane[i] = (b - 0.406f) / 0.225f
                }
                floatBuffer.put(rPlane)
                floatBuffer.put(gPlane)
                floatBuffer.put(bPlane)
                floatBuffer.flip()

                val inputShape = longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
                val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, inputShape)

                val inputName = session.inputNames.iterator().next()
                val output = session.run(mapOf(inputName to inputTensor))
                val predTensor = output[0].value as Array<Array<Array<FloatArray>>> // [1, 1, H, W]
                val probMap = predTensor[0][0]

                inputTensor.close()
                output.close()
                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }

                // Post-process: connected components on probMap
                val rawBoxes = extractBoxesFromProbMap(probMap, targetW, targetH)

                // Scale boxes back to original bitmap coordinates
                val scaleX = origW.toFloat() / targetW
                val scaleY = origH.toFloat() / targetH

                rawBoxes.map { rect ->
                    RectF(
                        rect.left * scaleX,
                        rect.top * scaleY,
                        rect.right * scaleX,
                        rect.bottom * scaleY
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Detection error: ${e.message}", e)
                emptyList()
            }
        }
    }

    /**
     * Connected components labeling & box unclip on probability map.
     */
    private fun extractBoxesFromProbMap(
        probMap: Array<FloatArray>,
        w: Int,
        h: Int
    ): List<RectF> {
        val visited = Array(h) { BooleanArray(w) }
        val boxes = mutableListOf<RectF>()

        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                if (probMap[y][x] >= DB_THRESH && !visited[y][x]) {
                    var minX = x
                    var maxX = x
                    var minY = y
                    var maxY = y
                    var count = 0
                    var scoreSum = 0.0

                    val queue: Queue<Int> = LinkedList()
                    queue.add((y shl 16) or x)
                    visited[y][x] = true

                    while (!queue.isEmpty()) {
                        val pos = queue.poll() ?: continue
                        val cy = pos shr 16
                        val cx = pos and 0xFFFF

                        minX = min(minX, cx)
                        maxX = max(maxX, cx)
                        minY = min(minY, cy)
                        maxY = max(maxY, cy)
                        count++
                        scoreSum += probMap[cy][cx]

                        // 4-neighborhood exploration with step 2
                        val dirs = arrayOf(0 to 2, 0 to -2, 2 to 0, -2 to 0)
                        for (d in dirs) {
                            val ny = cy + d.first
                            val nx = cx + d.second
                            if (ny in 0 until h && nx in 0 until w && !visited[ny][nx] && probMap[ny][nx] >= DB_THRESH) {
                                visited[ny][nx] = true
                                queue.add((ny shl 16) or nx)
                            }
                        }
                    }

                    val bw = maxX - minX + 1
                    val bh = maxY - minY + 1
                    val avgScore = if (count > 0) scoreSum / count else 0.0

                    if (bw >= 6 && bh >= 6 && avgScore >= BOX_THRESH) {
                        // Standard DBNet contour unclip: offset distance is based on area / perimeter
                        val area = bw.toDouble() * bh
                        val perimeter = 2.0 * (bw + bh)
                        val d = ((area * (UNCLIP_RATIO - 1.0)) / perimeter).toInt().coerceAtLeast(1)
                        val unclipLeft = max(0, minX - d).toFloat()
                        val unclipTop = max(0, minY - d).toFloat()
                        val unclipRight = min(w - 1, maxX + d).toFloat()
                        val unclipBottom = min(h - 1, maxY + d).toFloat()

                        boxes.add(RectF(unclipLeft, unclipTop, unclipRight, unclipBottom))
                    }
                }
            }
        }
        return boxes
    }

    /**
     * Crops the specified bounding box from source bitmap and runs text recognition.
     */
    suspend fun recognizeBox(source: Bitmap, box: RectF): String = withContext(Dispatchers.Default) {
        try {
            // Expand box slightly with contextual padding so outer stroke curves are not clipped
            val padX = max(3, (box.width() * 0.08f).roundToInt())
            val padY = max(3, (box.height() * 0.04f).roundToInt())
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

                    // Score candidates: prioritize high confidence (>0.85), valid CJK/alphanumeric characters,
                    // favor natural unsliced candidates, and penalize noise punctuation/fragments and length that physically exceeds box capacity.
                    fun score(cand: Pair<String, Float>, isUnsliced: Boolean): Float {
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
                        if (text.length > maxPossibleChars) {
                            s -= (text.length - maxPossibleChars) * 15f
                        }
                        return s
                    }

                    val score270 = score(cand270, isUnsliced = true)
                    val scoreUnroll = score(candUnroll, isUnsliced = false)
                    val scoreUpright = score(candUpright, isUnsliced = true)
                    val score90 = score(cand90, isUnsliced = true)

                    Log.d(TAG, "Vertical candidates for [${origW}x${origH}]: 270='${cand270.first}' (conf=${cand270.second}, s=$score270), unroll='${candUnroll.first}' (conf=${candUnroll.second}, s=$scoreUnroll), upright='${candUpright.first}' (conf=${candUpright.second}, s=$scoreUpright), 90='${cand90.first}' (conf=${cand90.second}, s=$score90)")

                    val candidates = listOf(
                        Triple(cand270.first, score270, cand270.second),
                        Triple(candUnroll.first, scoreUnroll, candUnroll.second),
                        Triple(candUpright.first, scoreUpright, candUpright.second),
                        Triple(cand90.first, score90, cand90.second)
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
        // High-confidence upright text skips 180-degree bitmap creation and inference entirely.
        if (conf0 >= 0.78f && validChars0 >= 2) return text0
        if (conf0 >= 0.85f && validChars0 >= 1) return text0

        var rot180: Bitmap? = null
        return try {
            val matrix180 = Matrix().apply { postRotate(180f) }
            rot180 = Bitmap.createBitmap(crop, 0, 0, origW, origH, matrix180, true)
            val cand180 = recognizeHorizontalStrip(rot180)
            val text180 = cand180.first.trim()
            val conf180 = cand180.second

            val adopt180 = shouldAdopt180Rotation(text0, conf0, text180, conf180)
            if (adopt180) {
                Log.d(TAG, "Smart 180-deg orientation adopted: '$text0' (conf=$conf0) -> '$text180' (conf=$conf180)")
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

    /**
     * Recognizes a single horizontal image strip and returns (decodedText, avgConfidence).
     */
    private fun recognizeHorizontalStrip(strip: Bitmap): Pair<String, Float> {
        val session = recSession ?: return "" to 0f
        val ortEnv = env ?: return "" to 0f

        val curW = strip.width
        val curH = strip.height
        if (curW < 4 || curH < 4) return "" to 0f

        val targetH = REC_HEIGHT
        var targetW = (curW * (targetH.toFloat() / curH)).toInt().coerceIn(32, 1280)
        targetW = max(32, ((targetW + 3) / 4) * 4)

        val scaledBitmap = Bitmap.createScaledBitmap(strip, targetW, targetH, true)

        val floatBuffer = FloatBuffer.allocate(1 * 3 * targetH * targetW)
        val pixels = IntArray(targetW * targetH)
        scaledBitmap.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        val rPlane = FloatArray(targetW * targetH)
        val gPlane = FloatArray(targetW * targetH)
        val bPlane = FloatArray(targetW * targetH)

        for (i in pixels.indices) {
            val color = pixels[i]
            rPlane[i] = (Color.red(color) / 255.0f - 0.5f) / 0.5f
            gPlane[i] = (Color.green(color) / 255.0f - 0.5f) / 0.5f
            bPlane[i] = (Color.blue(color) / 255.0f - 0.5f) / 0.5f
        }
        floatBuffer.put(rPlane)
        floatBuffer.put(gPlane)
        floatBuffer.put(bPlane)
        floatBuffer.flip()

        val inputShape = longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, inputShape)

        val inputName = session.inputNames.iterator().next()
        val output = session.run(mapOf(inputName to inputTensor))
        val predTensor = output[0].value as Array<Array<FloatArray>>
        val logits = predTensor[0]

        inputTensor.close()
        output.close()
        if (scaledBitmap != strip) {
            scaledBitmap.recycle()
        }

        return ctcGreedyDecode(logits)
    }

    private fun ctcGreedyDecode(logits: Array<FloatArray>): Pair<String, Float> {
        val sb = StringBuilder()
        var lastIndex = -1
        var totalScore = 0f
        var emittedCount = 0

        for (timeStep in logits) {
            var maxIndex = 0
            var maxVal = timeStep[0]
            for (c in 1 until timeStep.size) {
                if (timeStep[c] > maxVal) {
                    maxVal = timeStep[c]
                    maxIndex = c
                }
            }

            if (maxIndex != 0 && maxIndex != lastIndex) {
                // Character index in dict is (maxIndex - 1)
                val dictIdx = maxIndex - 1
                if (dictIdx in charDict.indices) {
                    sb.append(charDict[dictIdx])
                    totalScore += maxVal
                    emittedCount++
                }
            }
            lastIndex = maxIndex
        }
        val text = sb.toString()
        val avgScore = if (emittedCount > 0) totalScore / emittedCount else 0f
        return text to avgScore
    }

    fun release() {
        try {
            detSession?.close()
            recSession?.close()
            env?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing PP-OCR engine resources", e)
        }
        detSession = null
        recSession = null
        env = null
        loadedModel = null
    }
}
