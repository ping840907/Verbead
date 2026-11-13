package com.ping.verbead.util

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * 包住系統 WindowManager，記錄本程式加上的所有懸浮視窗，
 * 以便截圖前一次隱藏全部畫面、截完再還原，取得不含懸浮球的乾淨螢幕。
 */
class TrackingWindowManager(private val base: WindowManager) : WindowManager by base {
    private val tracked = LinkedHashSet<View>()
    private val savedVisibility = HashMap<View, Int>()
    /** Sea Glass coloured shadow windows, one behind each bubble window. */
    private val glows = HashMap<View, com.ping.verbead.ui.BubbleGlowWindow>()
    var isHidden: Boolean = false
        private set

    /** 這個視窗是經由本管理器加入、且尚未移除。 */
    fun isAdded(view: View): Boolean = view in tracked

    // WindowManager 的 default 方法不會被 Kotlin 委派轉發，必須逐一覆寫，否則會丟 UnsupportedOperationException
    @RequiresApi(Build.VERSION_CODES.R)
    override fun getCurrentWindowMetrics(): android.view.WindowMetrics = base.currentWindowMetrics

    @RequiresApi(Build.VERSION_CODES.R)
    override fun getMaximumWindowMetrics(): android.view.WindowMetrics = base.maximumWindowMetrics

    @RequiresApi(Build.VERSION_CODES.S)
    override fun isCrossWindowBlurEnabled(): Boolean = base.isCrossWindowBlurEnabled

    @RequiresApi(Build.VERSION_CODES.S)
    override fun addCrossWindowBlurEnabledListener(listener: java.util.function.Consumer<Boolean>) =
        base.addCrossWindowBlurEnabledListener(listener)

    @RequiresApi(Build.VERSION_CODES.S)
    override fun addCrossWindowBlurEnabledListener(
        executor: java.util.concurrent.Executor,
        listener: java.util.function.Consumer<Boolean>
    ) = base.addCrossWindowBlurEnabledListener(executor, listener)

    @RequiresApi(Build.VERSION_CODES.S)
    override fun removeCrossWindowBlurEnabledListener(listener: java.util.function.Consumer<Boolean>) =
        base.removeCrossWindowBlurEnabledListener(listener)


    private fun isBubbleRoot(view: View) = view.id == com.ping.verbead.R.id.layout_bubble_root

    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        // The glow window goes in first so it sits behind the bubble's own window.
        if (isBubbleRoot(view) && params is WindowManager.LayoutParams) {
            val glow = com.ping.verbead.ui.BubbleGlowWindow(base, view)
            glow.add(params)
            glows[view] = glow
            trackGlow(glow)
        }
        base.addView(view, params)
        tracked.add(view)
        if (isHidden) {
            savedVisibility[view] = view.visibility
            view.visibility = View.INVISIBLE
        }
    }

    override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
        base.updateViewLayout(view, params)
        if (params is WindowManager.LayoutParams) glows[view]?.move(params)
    }

    override fun removeView(view: View) {
        removeGlow(view)
        tracked.remove(view)
        savedVisibility.remove(view)
        base.removeView(view)
    }

    override fun removeViewImmediate(view: View) {
        removeGlow(view)
        tracked.remove(view)
        savedVisibility.remove(view)
        base.removeViewImmediate(view)
    }

    private fun trackGlow(glow: com.ping.verbead.ui.BubbleGlowWindow) {
        tracked.add(glow.view)
        if (isHidden) {
            savedVisibility[glow.view] = glow.view.visibility
            glow.view.visibility = View.INVISIBLE
        }
    }

    private fun removeGlow(bubble: View) {
        val glow = glows.remove(bubble) ?: return
        tracked.remove(glow.view)
        savedVisibility.remove(glow.view)
        glow.remove()
    }

    /** 隱藏所有已追蹤的懸浮視窗（只改可見度，不移除視窗）。 */
    fun hideAll() {
        if (isHidden) return
        isHidden = true
        for (v in tracked.toList()) {
            savedVisibility[v] = v.visibility
            v.visibility = View.INVISIBLE
        }
    }

    /** 還原先前隱藏的懸浮視窗。 */
    fun showAll() {
        if (!isHidden) return
        isHidden = false
        for ((v, vis) in savedVisibility) {
            if (tracked.contains(v)) v.visibility = vis
        }
        savedVisibility.clear()
    }
}

/**
 * 這個視窗是不是已經被加進 WindowManager、還沒移除。
 *
 * 不能用 [View.isAttachedToWindow] 判斷：addView 之後要等下一個畫面幀 View 才算「已附加」，
 * 這之前就想移除（例如輕點時預先建好又立刻丟棄的刪除區視窗）會被略過，視窗就變成沒人管的孤兒，
 * 永遠留在螢幕上。多個半透明的孤兒視窗疊在一起，不透明度會累加，超過系統上限後底下 App 的觸控就被擋掉。
 */
fun WindowManager.isViewAdded(view: View): Boolean =
    (this as? TrackingWindowManager)?.isAdded(view) ?: view.isAttachedToWindow
