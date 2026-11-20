package com.ping.verbead

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import java.io.File

/**
 * Debug only: shows one picture and nothing else, so the accessibility tree has no text. Used to try the
 * read-aloud fallback that recognizes the screen as an image.
 *
 *   adb push pic.png /sdcard/Android/data/<package>/files/ocrtest.png
 *   adb shell am start -n <package>/com.ping.verbead.OcrFallbackTestActivity
 */
class OcrFallbackTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(getExternalFilesDir(null), "ocrtest.png")
        val view = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            // no content description: a description would be read as text
            if (file.isFile) setImageBitmap(BitmapFactory.decodeFile(file.path))
        }
        setContentView(view)
    }
}
