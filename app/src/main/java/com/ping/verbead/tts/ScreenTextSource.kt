package com.ping.verbead.tts

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Finds the text to read aloud: the selection if there is one, otherwise what the screen shows.
 *
 * What it reads is limited on purpose. Password fields, the app's own overlay windows, the status
 * and navigation bars and the keyboard are skipped, and only text the user can currently see counts.
 * It never leaves the device.
 */
class ScreenTextSource(private val ownPackage: String) {

    enum class Kind { SELECTION, SCREEN, EMPTY }

    data class Result(val kind: Kind, val text: String, val pkg: String = "")

    fun read(service: AccessibilityService): Result {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

        // 1. A selection in any app window.
        for (w in windows) {
            val root = w.root ?: continue
            val sel = findSelection(root, 0, Budget())
            if (sel != null) return Result(Kind.SELECTION, sel.first, sel.second)
        }

        // 2. The visible text of the window the user is in (every app window when none is marked active).
        val targets = windows.filter { it.isActive }.ifEmpty { windows }
        val items = ArrayList<ScreenTextItem>()
        var pkg = ""
        for (w in targets) {
            val root = w.root ?: continue
            val before = items.size
            collect(root, 0, items, HashSet(), Budget())
            if (items.size > before && pkg.isEmpty()) pkg = root.packageName?.toString() ?: ""
        }
        val text = ReadingOrder.toText(items)
        return if (text.isBlank()) Result(Kind.EMPTY, "") else Result(Kind.SCREEN, text, pkg)
    }

    /** Stops a pathological tree (or a loop in a broken one) from running forever. */
    private class Budget(var nodes: Int = MAX_NODES) {
        fun take(): Boolean = nodes-- > 0
    }

    private fun findSelection(node: AccessibilityNodeInfo?, depth: Int, budget: Budget): Pair<String, String>? {
        if (node == null || depth > MAX_DEPTH || !budget.take()) return null
        val pkg = node.packageName?.toString() ?: ""
        if (pkg != ownPackage && !node.isPassword) {
            val s = node.textSelectionStart
            val e = node.textSelectionEnd
            val text = node.text?.toString().orEmpty()
            if (s >= 0 && e >= 0 && s != e && text.isNotEmpty()) {
                val lo = minOf(s, e).coerceIn(0, text.length)
                val hi = maxOf(s, e).coerceIn(0, text.length)
                if (hi > lo) return text.substring(lo, hi) to pkg
            }
        }
        for (i in 0 until node.childCount) {
            findSelection(node.getChild(i), depth + 1, budget)?.let { return it }
        }
        return null
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<ScreenTextItem>,
        seen: MutableSet<String>,
        budget: Budget
    ) {
        if (node == null || depth > MAX_DEPTH || !budget.take()) return
        val pkg = node.packageName?.toString() ?: ""
        if (node.isPassword || pkg == ownPackage) return
        if (node.isVisibleToUser) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            // Text always counts; a description only on a leaf (containers repeat what their children say).
            val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: if (node.childCount == 0) node.contentDescription?.toString().orEmpty() else ""
            if (label.isNotBlank() && !rect.isEmpty && seen.add("${rect.flattenToString()}|$label")) {
                out += ScreenTextItem(rect.left, rect.top, rect.right, rect.bottom, label)
            }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), depth + 1, out, seen, budget)
    }

    private companion object {
        const val MAX_DEPTH = 60
        const val MAX_NODES = 6000
    }
}
