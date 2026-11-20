package com.ping.verbead

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Debug only: a screen made to try the read-aloud text sources.
 *
 *   adb push pic.png /sdcard/Android/data/<package>/files/ocrtest.png
 *   adb shell am start -n <package>/com.ping.verbead.OcrFallbackTestActivity            picture only (no text in the tree)
 *   adb shell am start -n <package>/com.ping.verbead.OcrFallbackTestActivity --ez text true   a line of real text above the picture
 *
 * With the line of text, the tree has that line and only the picture's writing is missing from it, which is
 * what the hybrid source is for.
 */
class OcrFallbackTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(getExternalFilesDir(null), "ocrtest.png")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if (intent.getBooleanExtra("text", false)) {
            root.addView(TextView(this).apply {
                text = "這一行是無障礙服務讀得到的文字。\n下面那張圖片裡的字，無障礙服務讀不到。"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                gravity = Gravity.CENTER
                setPadding(32, 160, 32, 32)
            })
        }
        root.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // no content description: a description would be read as text
            if (file.isFile) setImageBitmap(BitmapFactory.decodeFile(file.path))
        })
        setContentView(root)
    }
}
