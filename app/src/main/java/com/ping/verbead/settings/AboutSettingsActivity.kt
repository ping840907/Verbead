package com.ping.verbead.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.ping.verbead.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ping.verbead.util.AppLocale
import com.ping.verbead.util.AppTheme
import com.ping.verbead.util.HapticUtil

class AboutSettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_about)
        com.ping.verbead.ui.GlassStage.install(this)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
        }

        val languageValue = findViewById<TextView>(R.id.text_language_value)
        languageValue.text = languageLabel(AppLocale.currentTag(this))
        findViewById<View>(R.id.row_language).setOnClickListener {
            HapticUtil.click(this)
            showLanguageDialog()
        }

        val themeValue = findViewById<TextView>(R.id.text_theme_value)
        themeValue.text = themeLabel(AppTheme.current(this))
        findViewById<View>(R.id.row_theme).setOnClickListener {
            HapticUtil.click(this)
            showThemeDialog()
        }

        val themeStyleValue = findViewById<TextView>(R.id.text_theme_style_value)
        themeStyleValue.text = styleLabel(AppTheme.getStyle(this))
        findViewById<View>(R.id.row_theme_style).setOnClickListener {
            HapticUtil.click(this)
            showThemeStyleDialog(themeStyleValue)
        }

        fun openWebUrl(url: String) {
            HapticUtil.click(this)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (ex: Exception) {
                Toast.makeText(this, getString(R.string.toast_url_open_error, ex.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }

        val creditUrls = mapOf(
            R.id.row_credit_sherpa to "https://github.com/k2-fsa/sherpa-onnx",
            R.id.row_credit_xasr to "https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m",
            R.id.row_credit_qwen to "https://github.com/QwenLM/Qwen3-ASR",
            R.id.row_credit_nvidia_asr to "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3",
            R.id.row_credit_moonshine to "https://huggingface.co/UsefulSensors/moonshine-streaming-tiny",
            R.id.row_credit_ppocr to "https://huggingface.co/collections/PaddlePaddle/pp-ocrv6",
            R.id.row_credit_korean_ppocr to "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec",
            R.id.row_credit_litert to "https://github.com/google-ai-edge/LiteRT",
            R.id.row_credit_zxing to "https://github.com/zxing-cpp/zxing-cpp",
            R.id.row_credit_onnx to "https://github.com/microsoft/onnxruntime",
            R.id.row_credit_opencc to "https://github.com/houbb/opencc4j",
            R.id.row_credit_seaglass to "https://github.com/mttmcknn/sea-glass",
            R.id.row_credit_material to "https://github.com/material-components/material-components-android",
            R.id.row_credit_commons_compress to "https://commons.apache.org/proper/commons-compress/"
        )
        creditUrls.forEach { (id, url) ->
            findViewById<View?>(id)?.setOnClickListener { openWebUrl(url) }
        }
    }

    private fun languageLabel(tag: String): String = when (tag) {
        "zh-TW" -> getString(R.string.settings_language_zh_tw)
        "zh-CN" -> getString(R.string.settings_language_zh_cn)
        "en" -> getString(R.string.settings_language_en)
        "ja" -> getString(R.string.settings_language_ja)
        "ko" -> getString(R.string.settings_language_ko)
        else -> getString(R.string.settings_language_follow_system)
    }

    private fun showLanguageDialog() {
        val tags = listOf("") + AppLocale.SUPPORTED_TAGS
        val current = tags.indexOf(AppLocale.currentTag(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_language_dialog_title)
            .setSingleChoiceItems(tags.map(::languageLabel).toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) AppLocale.setTag(this, tags[which])
            }
            .show()
    }

    private fun themeLabel(mode: String): String = when (mode) {
        AppTheme.LIGHT -> getString(R.string.settings_theme_light)
        AppTheme.DARK -> getString(R.string.settings_theme_dark)
        else -> getString(R.string.settings_theme_system)
    }

    private fun showThemeDialog() {
        val modes = AppTheme.MODES
        val current = modes.indexOf(AppTheme.current(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_theme_title)
            .setSingleChoiceItems(modes.map(::themeLabel).toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) AppTheme.set(this, modes[which])
            }
            .show()
    }

    private fun styleLabel(style: String): String = when (style) {
        AppTheme.STYLE_SEA_GLASS -> getString(R.string.settings_theme_style_sea_glass)
        else -> getString(R.string.settings_theme_style_expressive)
    }

    private fun showThemeStyleDialog(valueView: TextView) {
        val styles = AppTheme.STYLES
        val current = styles.indexOf(AppTheme.getStyle(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_theme_style_title)
            .setSingleChoiceItems(styles.map(::styleLabel).toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) {
                    AppTheme.setStyle(this, styles[which])
                    valueView.text = styleLabel(styles[which])
                    recreate()
                }
            }
            .show()
    }
}
