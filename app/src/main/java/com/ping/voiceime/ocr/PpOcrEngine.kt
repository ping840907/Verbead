package com.ping.voiceime.ocr

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
import com.ping.voiceime.engine.ModelConfig
import kotlinx.coroutines.Dispatchers
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
        private const val UNCLIP_RATIO = 1.4f
    }

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private val charDict = mutableListOf<String>()

    val isReady: Boolean
        get() = detSession != null && recSession != null

    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isReady) return@withContext true

            val detPath = ModelConfig.ocrDetPath(context)
            val recPath = ModelConfig.ocrRecPath(context)
            val dictPath = ModelConfig.ocrDictPath(context)

            if (!File(detPath).exists() || !File(recPath).exists()) {
                Log.w(TAG, "OCR models not found at: $detPath or $recPath")
                return@withContext false
            }

            env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)
                setIntraOpNumThreads(2)
            }

            detSession = env?.createSession(detPath, sessionOptions)
            recSession = env?.createSession(recPath, sessionOptions)

            loadDictionary(dictPath)
            Log.i(TAG, "PP-OCR engine loaded successfully! Dictionary size=${charDict.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load PP-OCR engine: ${e.message}", e)
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
        val session = detSession ?: return@withContext emptyList()
        val ortEnv = env ?: return@withContext emptyList()

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
        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}", e)
            emptyList()
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
                        // Unclip / expand box slightly
                        val expandX = (bw * (UNCLIP_RATIO - 1.0f) / 2).toInt()
                        val expandY = (bh * (UNCLIP_RATIO - 1.0f) / 2).toInt()
                        val unclipLeft = max(0, minX - expandX).toFloat()
                        val unclipTop = max(0, minY - expandY).toFloat()
                        val unclipRight = min(w - 1, maxX + expandX).toFloat()
                        val unclipBottom = min(h - 1, maxY + expandY).toFloat()

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
            val left = box.left.toInt().coerceIn(0, source.width - 1)
            val top = box.top.toInt().coerceIn(0, source.height - 1)
            val right = box.right.toInt().coerceIn(left + 1, source.width)
            val bottom = box.bottom.toInt().coerceIn(top + 1, source.height)
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
     * For vertical boxes (H > W * 1.15), it automatically unrolls upright characters into a horizontal strip
     * and evaluates orientation candidates to ensure reliable transcription of Chinese vertical columns and rotated text.
     */
    suspend fun recognizeText(crop: Bitmap): String = withContext(Dispatchers.Default) {
        val origW = crop.width
        val origH = crop.height
        if (origW < 4 || origH < 4) return@withContext ""

        try {
            val resultText = if (origH > origW * 1.15f) {
                // Vertical text box: evaluate multiple candidates
                // Candidate 1: Upright vertical text unrolled into horizontal strip
                val unrolled = unrollVerticalToHorizontal(crop)
                val candUnroll = if (unrolled != null) recognizeHorizontalStrip(unrolled) else ("" to 0f)
                if (unrolled != null && unrolled != crop) {
                    unrolled.recycle()
                }

                // Candidate 2: Rotated 270 degrees (counter-clockwise 90, for sideways text / Latin)
                val matrix270 = Matrix().apply { postRotate(270f) }
                val rot270 = Bitmap.createBitmap(crop, 0, 0, origW, origH, matrix270, true)
                val cand270 = recognizeHorizontalStrip(rot270)
                if (rot270 != crop) {
                    rot270.recycle()
                }

                // Candidate 3: Rotated 90 degrees
                val matrix90 = Matrix().apply { postRotate(90f) }
                val rot90 = Bitmap.createBitmap(crop, 0, 0, origW, origH, matrix90, true)
                val cand90 = recognizeHorizontalStrip(rot90)
                if (rot90 != crop) {
                    rot90.recycle()
                }

                // Score candidates: favor longer valid recognized text with good confidence
                fun score(cand: Pair<String, Float>): Float {
                    val clean = cand.first.trim()
                    if (clean.isEmpty()) return -1f
                    return clean.length * 15f + cand.second
                }

                val scoreUnroll = score(candUnroll)
                val score270 = score(cand270)
                val score90 = score(cand90)

                val bestCandidate = when {
                    scoreUnroll >= score270 && scoreUnroll >= score90 && scoreUnroll > 0f -> candUnroll.first
                    score270 >= score90 && score270 > 0f -> cand270.first
                    score90 > 0f -> cand90.first
                    else -> candUnroll.first.ifEmpty { cand270.first }
                }
                bestCandidate
            } else {
                // Normal horizontal text box
                recognizeHorizontalStrip(crop).first
            }

            ModelConfig.toTaiwanTraditional(resultText)
        } catch (e: Exception) {
            Log.e(TAG, "Recognition error: ${e.message}", e)
            ""
        }
    }

    /**
     * Unrolls a vertical column of upright Chinese characters into a horizontal strip of square character cells.
     * Uses row stroke energy analysis to detect gaps between characters and slice them precisely.
     */
    private fun unrollVerticalToHorizontal(crop: Bitmap): Bitmap? {
        val w = crop.width
        val h = crop.height
        if (w < 4 || h < 4) return null

        val rawRatio = h.toFloat() / w
        val estimatedCount = if (rawRatio >= 1.35f) max(2, rawRatio.roundToInt()).coerceIn(1, 40) else 1
        if (estimatedCount <= 1) {
            return Bitmap.createBitmap(crop)
        }

        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)

        // Calculate horizontal stroke energy per row to identify valleys between characters
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

        // 3-point moving average smoothing
        val smoothed = FloatArray(h)
        for (y in 0 until h) {
            var s = 0f
            var count = 0
            for (dy in -1..1) {
                val ny = y + dy
                if (ny in 0 until h) {
                    s += rowEnergy[ny]
                    count++
                }
            }
            smoothed[y] = s / count
        }

        // Find split points (valleys in stroke energy)
        val step = h.toFloat() / estimatedCount
        val splits = mutableListOf<Int>()
        splits.add(0)

        val searchRadius = (step * 0.35f).toInt().coerceAtLeast(2)
        for (k in 1 until estimatedCount) {
            val expectedY = (k * step).toInt()
            val yMin = (expectedY - searchRadius).coerceIn(splits.last() + 4, h - 4)
            val yMax = (expectedY + searchRadius).coerceIn(yMin, h - 1)

            var bestY = expectedY
            var minEnergy = Float.MAX_VALUE
            for (y in yMin..yMax) {
                if (smoothed[y] < minEnergy) {
                    minEnergy = smoothed[y]
                    bestY = y
                }
            }
            splits.add(bestY)
        }
        splits.add(h)

        // Extract slices
        val slices = mutableListOf<Bitmap>()
        for (i in 0 until splits.size - 1) {
            val top = splits[i]
            val bottom = splits[i + 1]
            val sliceH = bottom - top
            if (sliceH >= 4) {
                val slice = Bitmap.createBitmap(crop, 0, top, w, sliceH)
                slices.add(slice)
            }
        }

        if (slices.isEmpty()) return null

        val cellW = w
        val cellH = w
        val totalW = cellW * slices.size

        val outBitmap = Bitmap.createBitmap(totalW, cellH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBitmap)

        // Background color
        canvas.drawColor(pixels[0])

        for (i in slices.indices) {
            val slice = slices[i]
            val left = i * cellW
            val offsetX = left + max(0, (cellW - slice.width) / 2)
            val offsetY = max(0, (cellH - slice.height) / 2)
            canvas.drawBitmap(slice, offsetX.toFloat(), offsetY.toFloat(), null)
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
        floatBuffer.put(bPlane)
        floatBuffer.put(gPlane)
        floatBuffer.put(rPlane)
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
        } catch (_: Exception) {}
        detSession = null
        recSession = null
        env = null
    }
}
