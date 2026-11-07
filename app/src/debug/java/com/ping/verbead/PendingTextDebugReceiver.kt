package com.ping.verbead

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only: pushes recognized-looking texts as if no text field had focus.
 *   adb shell am broadcast -n <pkg>/com.ping.verbead.PendingTextDebugReceiver --es texts first
 *   ... --es bubbles 2           (set the bubble count)
 *   ... --es shake 2 --ef dx -300 --ef dy 500   (shake the first bubble twice, drag it, let go)
 */
class PendingTextDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = FloatingBubbleService.instance
        if (service == null) {
            Log.w("PendingDebug", "bubble service is not running")
            return
        }
        intent.getStringExtra("bubbles")?.toIntOrNull()?.let { service.debugSetBubbleCount(it) }
        intent.getStringExtra("shake")?.toIntOrNull()?.let {
            service.debugShakeChainDemo(it, intent.getFloatExtra("dx", -300f), intent.getFloatExtra("dy", 500f))
        }
        for (t in (intent.getStringExtra("texts") ?: "").split("|").filter { it.isNotBlank() }) {
            service.debugKeepUnfilledText(t)
        }
    }
}
