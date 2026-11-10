package com.ping.verbead.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.view.PreviewView
import androidx.cardview.widget.CardView
import com.ping.verbead.util.isViewAdded

/**
 * Gives an overlay panel a window of another size without a flicker.
 *
 * Resizing a window that hangs on the right edge makes its content jump, so the panel is moved into a new window of
 * the target size. Two windows cannot be changed at the same moment (each one is drawn and handed to the compositor on
 * its own, and a large window takes longer than a small one), so showing one while removing the other shows either
 * both of them or neither for a frame. This class therefore arranges that every visible change happens inside ONE
 * window, and that the other window is only added or removed while it is hidden by, or identical to, the one in front:
 *
 *  - Growing: the new window is bigger and covers the old one completely; it is shown at once, and the old window is
 *    removed a few frames later (nothing changes on screen when it goes).
 *  - Shrinking: first the old window itself changes to the shrunken look (a still picture of the panel at its new
 *    size, drawn into the same-sized image, so one window changes in one frame); then the new window is shown over
 *    the same pixels, and the old one is removed a few frames later.
 *
 * Until then the old window shows a still picture of the panel, camera image included.
 */
object OverlayWindowSwap {
    private const val TAG = "OverlayWindowSwap"

    /** Go on anyway if the new window has not reported a first frame by then. */
    private const val FIRST_FRAME_TIMEOUT_MS = 250L

    /** Frames to wait before the old window is removed, once it no longer matters what it shows. */
    private const val FRAMES_BEFORE_REMOVAL = 3

    private class Snapshot(val image: Bitmap, val camera: Bitmap?)

    /**
     * Moves [panel] from [oldWindow] into a new window with [newParams] and returns that window, or null when it could
     * not be done (nothing is changed then, and the caller resizes the old window itself).
     *
     * [previewView] and [cameraCard] let the still picture include the live camera image (a texture view is not
     * captured by drawing the panel).
     */
    fun swap(
        context: Context,
        windowManager: WindowManager,
        oldWindow: View,
        panel: View,
        previewView: PreviewView?,
        cameraCard: View?,
        newParams: WindowManager.LayoutParams,
    ): FrameLayout? {
        val oldGroup = panel.parent as? ViewGroup ?: return null
        if (!oldWindow.isAttachedToWindow) return null
        val shrinking = newParams.width in 1 until oldWindow.width

        val snapshot = snapshotOf(oldWindow, panel, previewView, cameraCard)
        val placeholder = snapshot?.let {
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.MATRIX
                setImageBitmap(it.image)
                layoutParams = FrameLayout.LayoutParams(it.image.width, it.image.height)
            }
        }
        val newWindow = FrameLayout(context)
        try {
            placeholder?.let { oldGroup.addView(it) }
            oldGroup.removeView(panel)
            newWindow.addView(panel)
            // a smaller window must not be seen before the old one has taken its shrunken look
            if (shrinking) newWindow.alpha = 0f
            windowManager.addView(newWindow, newParams)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the new overlay window", e)
            runCatching {
                newWindow.removeView(panel)
                placeholder?.let { oldGroup.removeView(it) }
                oldGroup.addView(panel)
            }
            snapshot?.let { release(it) }
            return null
        }

        val finish: () -> Unit = { snapshot?.let { release(it) }; Unit }
        if (shrinking) {
            shrink(windowManager, oldWindow, newWindow, panel, cameraCard, snapshot, placeholder, finish)
        } else {
            grow(windowManager, oldWindow, newWindow, finish)
        }
        return newWindow
    }

    private fun release(s: Snapshot) {
        if (!s.image.isRecycled) s.image.recycle()
        s.camera?.let { if (!it.isRecycled) it.recycle() }
    }

    // ── Growing ──────────────────────────────────────────────────────────────

    private fun grow(windowManager: WindowManager, old: View, new: View, finish: () -> Unit) {
        afterFirstFrame(new) {
            afterFrames(FRAMES_BEFORE_REMOVAL) {
                removeWindow(windowManager, old)
                finish()
            }
        }
    }

    // ── Shrinking ────────────────────────────────────────────────────────────

    private fun shrink(
        windowManager: WindowManager,
        old: View,
        new: View,
        panel: View,
        cameraCard: View?,
        snapshot: Snapshot?,
        placeholder: ImageView?,
        finish: () -> Unit,
    ) {
        afterFirstFrame(new) {
            // 1) the old window takes the shrunken look: one picture swapped for another of the same size
            val shrunken = if (snapshot != null && placeholder != null) shrunkenLook(old, new, panel, cameraCard, snapshot) else null
            if (shrunken != null) placeholder?.setImageBitmap(shrunken)
            // 2) one frame later the new window is shown over the same pixels, and the old one can go
            Choreographer.getInstance().postFrameCallback {
                new.alpha = 1f
                afterFrames(FRAMES_BEFORE_REMOVAL) {
                    removeWindow(windowManager, old)
                    shrunken?.let { if (!it.isRecycled) it.recycle() }
                    finish()
                }
            }
        }
    }

    /**
     * What the old window has to show once it has shrunk: the panel as the new window lays it out now, with the last
     * camera image in its card, placed in an image as big as the old one at the spot where the new window will be.
     * (The new window is not always at the same height as the old one.) Null when that spot is not inside the old
     * window: the picture would be cut off, so the caller goes without it.
     */
    private fun shrunkenLook(old: View, new: View, panel: View, cameraCard: View?, snapshot: Snapshot): Bitmap? {
        if (panel.width <= 0 || panel.height <= 0) return null
        val oldAt = IntArray(2).also { old.getLocationOnScreen(it) }
        val newAt = IntArray(2).also { new.getLocationOnScreen(it) }
        val dx = newAt[0] - oldAt[0]
        val dy = newAt[1] - oldAt[1]
        val fits = dx >= 0 && dy >= 0 &&
            dx + panel.width <= snapshot.image.width + 1 && dy + panel.height <= snapshot.image.height + 1
        if (!fits) return null
        return try {
            val look = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(look)
            panel.draw(canvas)
            drawCamera(canvas, new, cameraCard, snapshot.camera)
            val full = Bitmap.createBitmap(snapshot.image.width, snapshot.image.height, Bitmap.Config.ARGB_8888)
            Canvas(full).drawBitmap(look, dx.toFloat(), dy.toFloat(), null)
            look.recycle()
            full
        } catch (e: Throwable) {
            Log.w(TAG, "Could not draw the shrunken look", e)
            null
        }
    }

    // ── Pictures ─────────────────────────────────────────────────────────────

    private fun snapshotOf(oldWindow: View, panel: View, previewView: PreviewView?, cameraCard: View?): Snapshot? {
        if (panel.width <= 0 || panel.height <= 0) return null
        return try {
            val image = Bitmap.createBitmap(panel.width, panel.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            panel.draw(canvas)
            val camera = previewView?.bitmap
            drawCamera(canvas, oldWindow, cameraCard, camera)
            Snapshot(image, camera)
        } catch (e: Throwable) {
            Log.w(TAG, "Snapshot for the swap failed", e)
            null
        }
    }

    /** Draws [camera] into the area of [cameraCard] (found in the coordinates of [root]), with the card's rounded corners. */
    private fun drawCamera(canvas: Canvas, root: View, cameraCard: View?, camera: Bitmap?) {
        if (camera == null || camera.isRecycled || cameraCard == null || cameraCard.width <= 0) return
        val area = Rect(0, 0, cameraCard.width, cameraCard.height)
        (root as? ViewGroup)?.offsetDescendantRectToMyCoords(cameraCard, area)
        val radius = (cameraCard as? CardView)?.radius ?: 0f
        val saved = canvas.save()
        if (radius > 0f) {
            val path = Path().apply { addRoundRect(RectF(area), radius, radius, Path.Direction.CW) }
            canvas.clipPath(path)
        }
        canvas.drawBitmap(camera, null, area, null)
        canvas.restoreToCount(saved)
    }

    // ── Timing ───────────────────────────────────────────────────────────────

    /** Runs [action] in the display frame after [view] has drawn its first frame (or after a timeout). */
    private fun afterFirstFrame(view: View, action: () -> Unit) {
        val choreographer = Choreographer.getInstance()
        var done = false
        fun run() {
            if (done) return
            done = true
            action()
        }
        var sawFirstFrame = false
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (sawFirstFrame) return
                sawFirstFrame = true
                // a listener may not be removed while it runs
                view.post { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this) }
                choreographer.postFrameCallback { run() }
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
        view.postDelayed({ run() }, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun afterFrames(count: Int, action: () -> Unit) {
        if (count <= 0) {
            action()
            return
        }
        Choreographer.getInstance().postFrameCallback { afterFrames(count - 1, action) }
    }

    private fun removeWindow(windowManager: WindowManager, view: View) {
        if (!windowManager.isViewAdded(view)) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove the old overlay window", e)
        }
    }
}
