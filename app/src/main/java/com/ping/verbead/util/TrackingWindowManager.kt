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


    override fun addView(view: View, params: ViewGroup.LayoutParams) {
        base.addView(view, params)
        tracked.add(view)
        if (isHidden) {
            savedVisibility[view] = view.visibility
            view.visibility = View.INVISIBLE
        }
    }

    override fun removeView(view: View) {
        tracked.remove(view)
        savedVisibility.remove(view)
        base.removeView(view)
    }

    override fun removeViewImmediate(view: View) {
        tracked.remove(view)
        savedVisibility.remove(view)
        base.removeViewImmediate(view)
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
