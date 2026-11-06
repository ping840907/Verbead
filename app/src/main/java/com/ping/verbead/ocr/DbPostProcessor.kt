package com.ping.verbead.ocr

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Turns the PP-OCR detector probability map into text boxes (connected components + unclip).
 *
 * Pure Kotlin so it can be unit tested. Kept deliberately permissive about size: the map is produced at a
 * reduced resolution (the whole screen is shrunk to fit the detector), so a normal 14px UI text line is only a
 * few pixels tall here. A larger minimum size silently drops that text (measured: 4 of 37 lines found with a
 * 6px minimum versus 37 of 37 with 3px on a 1080x2400 synthetic page).
 */
object DbPostProcessor {
    const val DB_THRESH = 0.2f
    const val BOX_THRESH = 0.4f
    const val UNCLIP_RATIO = 1.6f
    const val MIN_SIDE = 3

    fun extractBoxes(
        probMap: Array<FloatArray>,
        w: Int,
        h: Int,
        dbThresh: Float = DB_THRESH,
        boxThresh: Float = BOX_THRESH,
        unclipRatio: Float = UNCLIP_RATIO,
        minSide: Int = MIN_SIDE
    ): List<RectF> {
        val visited = Array(h) { BooleanArray(w) }
        val boxes = mutableListOf<RectF>()
        var stack = IntArray(1024)

        for (y in 0 until h) {
            val row = probMap[y]
            for (x in 0 until w) {
                if (row[x] < dbThresh || visited[y][x]) continue

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var count = 0
                var scoreSum = 0.0

                var sp = 0
                stack[sp++] = (y shl 16) or x
                visited[y][x] = true

                while (sp > 0) {
                    val pos = stack[--sp]
                    val cy = pos shr 16
                    val cx = pos and 0xFFFF
                    minX = min(minX, cx); maxX = max(maxX, cx)
                    minY = min(minY, cy); maxY = max(maxY, cy)
                    count++
                    scoreSum += probMap[cy][cx]

                    // 4-neighbourhood at full resolution (every pixel, so 1px-thin strokes stay connected)
                    for (k in 0 until 4) {
                        val ny = cy + DY[k]
                        val nx = cx + DX[k]
                        if (ny < 0 || ny >= h || nx < 0 || nx >= w) continue
                        if (visited[ny][nx] || probMap[ny][nx] < dbThresh) continue
                        visited[ny][nx] = true
                        if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = (ny shl 16) or nx
                    }
                }

                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                val avgScore = if (count > 0) scoreSum / count else 0.0
                if (bw < minSide || bh < minSide || avgScore < boxThresh) continue

                // DBNet unclip: offset distance from area / perimeter of the component's bounding rectangle
                val area = bw.toDouble() * bh
                val perimeter = 2.0 * (bw + bh)
                val d = ((area * (unclipRatio - 1.0)) / perimeter).toInt().coerceAtLeast(1)
                boxes.add(
                    RectF(
                        max(0, minX - d).toFloat(),
                        max(0, minY - d).toFloat(),
                        min(w - 1, maxX + d).toFloat(),
                        min(h - 1, maxY + d).toFloat()
                    )
                )
            }
        }
        return boxes
    }

    private val DY = intArrayOf(0, 0, 1, -1)
    private val DX = intArrayOf(1, -1, 0, 0)
}
