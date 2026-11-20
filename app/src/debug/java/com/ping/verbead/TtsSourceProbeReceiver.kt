package com.ping.verbead

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ping.verbead.ocr.PpOcrEngine
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Debug-only probe for the read-aloud feature (phase 0): what can the accessibility service see in the app that is
 * in front? Answers three questions with numbers:
 *   1. Is a text selection visible to us (which node, which range)? Can it be read, and does ACTION_COPY work?
 *   2. How much of the screen do text nodes cover, and are there large image nodes without text (OCR candidates)?
 *   3. (optional) How does a full-screen OCR compare with the node tree: time, boxes, characters.
 *
 * Switch to the app to test, select some text, then within the delay run:
 *   adb shell am broadcast -n <pkg>/com.ping.verbead.TtsSourceProbeReceiver --ei delay 6 [--ez copy true] [--ez ocr true]
 *   adb logcat -s TtsProbe
 *
 * `copy` presses ACTION_COPY on the selected node and tries to read the clipboard (the clipboard is restored
 * afterwards); this shows whether the clipboard route works on this Android version. `ocr` needs the OCR model
 * downloaded and Android 11+.
 */
class TtsSourceProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val delaySec = intent.getIntExtra("delay", 5)
        val tryCopy = intent.getBooleanExtra("copy", false)
        val tryOcr = intent.getBooleanExtra("ocr", false)
        thread(name = "tts-probe") {
            try {
                Thread.sleep(delaySec * 1000L)
                probe(app, tryCopy, tryOcr)
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private class Item(val rect: Rect, val text: String, val pkg: String, val cls: String)

    private fun probe(app: Context, tryCopy: Boolean, tryOcr: Boolean) {
        val svc = VoiceAccessibilityService.instance
        if (svc == null) {
            Log.e(TAG, "accessibility service is not running")
            return
        }
        val dm = svc.resources.displayMetrics
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels
        val windows = svc.windows
        Log.i(TAG, "screen=${dm.widthPixels}x${dm.heightPixels} windows=${windows.size} sdk=${android.os.Build.VERSION.SDK_INT}")

        val items = ArrayList<Item>()
        var selectionNode: AccessibilityNodeInfo? = null
        var selectionText = ""
        var nodes = 0
        var bigImages = 0
        var webViews = 0
        var skippedPassword = 0
        var ownOverlayNodes = 0
        val seen = HashSet<String>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 60) return
            nodes++
            val pkg = node.packageName?.toString() ?: ""
            val cls = node.className?.toString() ?: ""
            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (pkg == app.packageName) ownOverlayNodes++
            if (cls.contains("WebView")) webViews++

            val s = node.textSelectionStart
            val e = node.textSelectionEnd
            val text = node.text?.toString() ?: ""
            if (s >= 0 && e >= 0 && s != e && text.isNotEmpty()) {
                val lo = minOf(s, e).coerceIn(0, text.length)
                val hi = maxOf(s, e).coerceIn(0, text.length)
                Log.i(TAG, "SELECTION pkg=$pkg cls=$cls range=$lo..$hi editable=${node.isEditable} focused=${node.isFocused} " +
                    "text=\"${text.substring(lo, hi).take(60)}\"")
                if (selectionNode == null) { selectionNode = node; selectionText = text.substring(lo, hi) }
            }

            if (node.isPassword) {
                skippedPassword++
            } else if (node.isVisibleToUser && pkg != app.packageName) {
                // Text always counts; a content description only on a leaf (containers repeat their children).
                val label = if (text.isNotBlank()) text else if (node.childCount == 0) node.contentDescription?.toString() ?: "" else ""
                if (label.isNotBlank()) {
                    if (seen.add("${rect.flattenToString()}|$label")) items += Item(rect, label, pkg, cls)
                } else if (cls.contains("Image") && rect.width().toLong() * rect.height() > screenArea / 20) {
                    bigImages++
                    Log.i(TAG, "IMAGE-NODE no text, ${rect.width()}x${rect.height()} pkg=$pkg cls=$cls")
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        for (w in windows) {
            Log.i(TAG, "window type=${w.type} layer=${w.layer} active=${w.isActive} focused=${w.isFocused} title=${w.title}")
            walk(w.root, 0)
        }

        // Coverage: a coarse grid, one cell is marked when a text node covers it.
        val cols = 48
        val rows = 96
        val grid = BooleanArray(cols * rows)
        for (item in items) {
            val x0 = (item.rect.left.toLong() * cols / dm.widthPixels).toInt().coerceIn(0, cols - 1)
            val x1 = (item.rect.right.toLong() * cols / dm.widthPixels).toInt().coerceIn(0, cols - 1)
            val y0 = (item.rect.top.toLong() * rows / dm.heightPixels).toInt().coerceIn(0, rows - 1)
            val y1 = (item.rect.bottom.toLong() * rows / dm.heightPixels).toInt().coerceIn(0, rows - 1)
            for (y in y0..y1) for (x in x0..x1) grid[y * cols + x] = true
        }
        val coverage = grid.count { it } * 100 / grid.size
        val ordered = items.sortedWith(compareBy({ it.rect.top / 40 }, { it.rect.left }))
        val chars = ordered.sumOf { it.text.length }
        ordered.take(30).forEachIndexed { i, item ->
            Log.i(TAG, "  [$i] ${item.rect.flattenToShortString()} ${item.cls.substringAfterLast('.')} \"${item.text.take(40)}\"")
        }
        Log.i(TAG, "SUMMARY nodes=$nodes textNodes=${ordered.size} chars=$chars coverage=$coverage% bigImageNodes=$bigImages " +
            "webView=$webViews passwordSkipped=$skippedPassword ownOverlayNodes=$ownOverlayNodes " +
            "selection=${if (selectionNode != null) "yes(${selectionText.length} chars)" else "no"}")

        if (tryCopy) copyProbe(svc, selectionNode)
        if (tryOcr) ocrProbe(app, svc, chars)
        Log.i(TAG, "DONE")
    }

    /** ACTION_COPY on the node with the selection, then read the clipboard; the previous clip is restored. */
    private fun copyProbe(svc: VoiceAccessibilityService, node: AccessibilityNodeInfo?) {
        if (node == null) { Log.i(TAG, "COPY skipped: no selection found"); return }
        val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val before: ClipData? = runCatching { cm.primaryClip }.getOrNull()
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_COPY)
        Thread.sleep(400)
        val clip = try {
            cm.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: SecurityException) {
            Log.w(TAG, "COPY clipboard read blocked: ${e.message}")
            null
        }
        Log.i(TAG, "COPY performAction=$ok clipboard=${clip?.let { "\"${it.take(60)}\" (${it.length} chars)" } ?: "unreadable"}")
        runCatching { if (before != null) cm.setPrimaryClip(before) else cm.clearPrimaryClip() }
    }

    /** Full-screen OCR, to compare with what the node tree gave. */
    private fun ocrProbe(app: Context, svc: VoiceAccessibilityService, treeChars: Int) {
        val lock = Object()
        var shot: android.graphics.Bitmap? = null
        var finished = false
        val t0 = System.nanoTime()
        svc.captureScreen { bmp ->
            synchronized(lock) { shot = bmp; finished = true; lock.notifyAll() }
        }
        synchronized(lock) { if (!finished) lock.wait(8000) }
        val bmp = shot
        if (bmp == null) { Log.w(TAG, "OCR: screenshot failed (needs Android 11+)"); return }
        Log.i(TAG, "OCR screenshot ${bmp.width}x${bmp.height} in %.0fms".format((System.nanoTime() - t0) / 1e6))
        val engine = PpOcrEngine(app)
        if (!runBlocking { engine.load() }) { Log.w(TAG, "OCR: model not loaded: ${engine.lastLoadError}"); return }
        var t = System.nanoTime()
        val boxes = runBlocking { engine.detectText(bmp) }
        val detMs = (System.nanoTime() - t) / 1e6
        t = System.nanoTime()
        val lines = boxes.sortedBy { it.top }.map { runBlocking { engine.recognizeBox(bmp, it) } }
        val recMs = (System.nanoTime() - t) / 1e6
        val ocrChars = lines.sumOf { it.length }
        Log.i(TAG, "OCR boxes=${boxes.size} chars=$ocrChars (tree chars=$treeChars) det=%.0fms rec=%.0fms".format(detMs, recMs))
        engine.release()
    }

    private companion object { const val TAG = "TtsProbe" }
}
