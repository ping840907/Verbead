package com.ping.verbead.bubble

import android.view.Choreographer
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 搖晃複製出的球，在這次手勢結束前會像鏈條一樣串在被拖曳的球後面（最近的在前）：
 * 每一顆以固定距離跟隨前一顆。手勢結束（[end]）後鏈解開，改由一般的貼邊吸附與碰撞接手。
 * 從 FloatingBubbleService 拆出；只依賴 [WindowManager] 與畫面邊界，不碰其他泡泡邏輯。
 */
class BubbleChainFollower(
    private val windowManager: WindowManager,
    private val density: Float,
    private val bounds: () -> Bounds
) {
    data class Bounds(val maxX: Float, val minY: Float, val maxY: Float)

    private val chain = ArrayList<FloatingBubbleItem>()
    private var leader: FloatingBubbleItem? = null
    private var lastFrameNs = 0L

    /** id -> [x, y, vx, vy]（px、px/s）：領頭球與每顆被串起的球，位置以浮點數保存 */
    private val state = HashMap<Int, FloatArray>()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (chain.isEmpty()) {
                lastFrameNs = 0L
                return
            }
            val dtMs = if (lastFrameNs == 0L) 16f else ((frameTimeNanos - lastFrameNs) / 1e6f).coerceIn(1f, 50f)
            lastFrameNs = frameTimeNanos
            step(dtMs)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    val isEmpty: Boolean get() = chain.isEmpty()

    operator fun contains(item: FloatingBubbleItem): Boolean = item in chain

    /** 鏈尾（已加入視窗者），新球會接在它後面；沒有則回傳 null */
    fun attachedTail(): FloatingBubbleItem? = chain.lastOrNull()?.takeIf { it.view.isAttachedToWindow }

    /** 把 [item] 串到 [dragged]（被拖曳的球）後面並開始跟隨 */
    fun link(dragged: FloatingBubbleItem, item: FloatingBubbleItem) {
        if (chain.isEmpty()) leader = dragged
        chain.add(item)
        lastFrameNs = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** 手勢結束：解開鏈（之後由一般的貼邊吸附與碰撞接手） */
    fun end() {
        if (chain.isEmpty()) return
        chain.clear()
        leader = null
        state.clear()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameNs = 0L
    }

    /**
     * 鏈的一個動畫影格。每顆球被拉向前一顆球周圍半徑 [SPACING_DP] 的圓（沿兩球連線，像繩子），並帶有慣性
     * （見 [STIFFNESS]）。手指按著時，被串起的球不參與碰撞物理。
     */
    private fun step(dtMs: Float) {
        val leader = leader ?: return
        val b = bounds()
        val dt = (dtMs / 1000f).coerceIn(0.001f, 0.05f)

        // 被拖曳的球由手指移動，這裡只追蹤它的位置與速度
        val lead = state.getOrPut(leader.id) {
            floatArrayOf(leader.windowLayoutParams.x.toFloat(), leader.windowLayoutParams.y.toFloat(), 0f, 0f)
        }
        val lx = leader.windowLayoutParams.x.toFloat()
        val ly = leader.windowLayoutParams.y.toFloat()
        // 觸控事件不是每個影格都到，所以量到的速度要平滑
        lead[2] += ((lx - lead[0]) / dt - lead[2]) * 0.5f
        lead[3] += ((ly - lead[1]) / dt - lead[3]) * 0.5f
        lead[0] = lx
        lead[1] = ly

        val spacing = SPACING_DP * density
        val k = STIFFNESS
        val c = 2f * DAMPING_RATIO * sqrt(k)
        var front = lead
        var frontView = leader
        for (f in chain) {
            val st = state.getOrPut(f.id) {
                floatArrayOf(f.windowLayoutParams.x.toFloat(), f.windowLayoutParams.y.toFloat(), 0f, 0f)
            }
            if (!f.view.isAttachedToWindow || !frontView.view.isAttachedToWindow) { front = st; frontView = f; continue }
            f.snapAnimator?.cancel()

            var dx = st[0] - front[0]
            var dy = st[1] - front[1]
            var d = hypot(dx, dy)
            if (d < 0.5f) { dx = 0f; dy = 1f; d = 1f } // 剛好疊在前一顆下面：從它下方出來
            // 靜止點：前一顆球周圍半徑 spacing 的圓上、這顆球目前所在的那一側
            val goalX = front[0] + dx / d * spacing
            val goalY = front[1] + dy / d * spacing

            // 朝靜止點的彈簧，阻尼相對於前一顆球（半隱式 Euler，每影格一小步）
            st[2] += (k * (goalX - st[0]) - c * (st[2] - front[2])) * dt
            st[3] += (k * (goalY - st[1]) - c * (st[3] - front[3])) * dt
            st[0] += st[2] * dt
            st[1] += st[3] * dt

            // 留在螢幕內；牆會吸收該方向的速度
            if (st[0] < 0f) { st[0] = 0f; if (st[2] < 0f) st[2] = 0f }
            if (st[0] > b.maxX) { st[0] = b.maxX; if (st[2] > 0f) st[2] = 0f }
            if (st[1] < b.minY) { st[1] = b.minY; if (st[3] < 0f) st[3] = 0f }
            if (st[1] > b.maxY) { st[1] = b.maxY; if (st[3] > 0f) st[3] = 0f }

            val nx = st[0].toInt()
            val ny = st[1].toInt()
            if (nx != f.windowLayoutParams.x || ny != f.windowLayoutParams.y) {
                f.windowLayoutParams.x = nx
                f.windowLayoutParams.y = ny
                windowManager.updateViewLayout(f.view, f.windowLayoutParams)
            }
            front = st
            frontView = f
        }
    }

    companion object {
        /** 被串起的球與前一顆球的圓心距：60dp 的球加上一小段間隙，確保不會重疊 */
        const val SPACING_DP = 68f

        /**
         * 被串起的球是掛在彈簧阻尼繩上的質點：剛性（1/s²，約 3.5 Hz）。阻尼作用在「相對於前一顆球的速度」，
         * 阻尼比小於 1：前一顆球猛然一甩時，這顆會被帶著走而不是被甩在後面，前一顆停下時也會因慣性稍微衝過頭。
         */
        const val STIFFNESS = 520f
        const val DAMPING_RATIO = 0.6f
    }
}
