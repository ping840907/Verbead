package com.ping.verbead

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.concurrent.thread

/**
 * Debug only: lists the scrollable areas of the window in front, and asks one to scroll in a chosen way,
 * to find out which scroll action a given app (a browser, say) really answers.
 *
 *   adb shell am broadcast -n <package>/com.ping.verbead.ScrollProbeReceiver --es action forward|down|pagedown|list [--ei delay 3]
 *   adb logcat -s ScrollProbe
 */
class ScrollProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val mode = intent.getStringExtra("action") ?: "list"
        val delaySec = intent.getIntExtra("delay", 0)
        thread(name = "scroll-probe") {
            try {
                if (delaySec > 0) Thread.sleep(delaySec * 1000L)
                run(mode)
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun run(mode: String) {
        val svc: AccessibilityService = VoiceAccessibilityService.instance ?: return Unit.also { Log.e(TAG, "service not running") }
        val windows = svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = windows.firstOrNull { it.isActive } ?: windows.firstOrNull() ?: return Unit.also { Log.e(TAG, "no window") }
        val root = window.root ?: return Unit.also { Log.e(TAG, "no root") }
        val found = ArrayList<AccessibilityNodeInfo>()
        val rect = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60) return
            if (n.isScrollable) found += n
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        for (n in found) {
            n.getBoundsInScreen(rect)
            val actions = n.actionList.joinToString(",") { it.id.toString() }
            Log.i(TAG, "scrollable ${n.className} bounds=$rect visible=${n.isVisibleToUser} actions=[$actions]")
        }
        if (mode == "list") return
        val target = found.maxByOrNull { n -> n.getBoundsInScreen(rect); rect.width().toLong() * rect.height() } ?: return Unit.also { Log.w(TAG, "nothing scrollable") }
        val ok = when (mode) {
            "forward" -> target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "down" -> target.performAction(AccessibilityAction.ACTION_SCROLL_DOWN.id)
            "pagedown" -> target.performAction(AccessibilityAction.ACTION_PAGE_DOWN.id)
            else -> false
        }
        Log.i(TAG, "performed $mode on ${target.className}: $ok")
    }

    private companion object { const val TAG = "ScrollProbe" }
}
