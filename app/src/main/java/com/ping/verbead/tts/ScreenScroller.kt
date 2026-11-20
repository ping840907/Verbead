package com.ping.verbead.tts

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Scrolls the page the user is reading forward, through the accessibility service (no gestures are drawn on
 * the screen). It picks the largest scrollable area in view and asks it to scroll one page.
 */
class ScreenScroller(private val ownPackage: String) {

    /** True when something was asked to scroll; false when nothing in view can scroll forward (the end, or no list). */
    fun scrollForward(service: AccessibilityService): Boolean {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = windows.firstOrNull { it.isActive } ?: windows.firstOrNull() ?: return false
        val root = window.root ?: return false
        val target = largestScrollable(root, ownWindow = window.isActive) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    /** The bounds (l, t, r, b) of the area that [scrollForward] would scroll in the window under [root], or null. */
    fun scrollAreaBounds(root: AccessibilityNodeInfo, ownWindow: Boolean): IntArray? {
        val node = largestScrollable(root, ownWindow) ?: return null
        val r = Rect().also { node.getBoundsInScreen(it) }
        return intArrayOf(r.left, r.top, r.right, r.bottom)
    }

    private class Best(var node: AccessibilityNodeInfo? = null, var area: Long = 0)

    private fun largestScrollable(root: AccessibilityNodeInfo, ownWindow: Boolean): AccessibilityNodeInfo? {
        val best = Best()
        var budget = MAX_NODES
        val rect = Rect()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || budget-- <= 0) return
            val own = node.packageName?.toString() == ownPackage
            if (own && !ownWindow) return
            if (node.isScrollable && node.isVisibleToUser &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
            ) {
                node.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height()
                if (area > best.area) { best.node = node; best.area = area }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return best.node
    }

    private companion object {
        const val MAX_DEPTH = 60
        const val MAX_NODES = 6000
    }
}
