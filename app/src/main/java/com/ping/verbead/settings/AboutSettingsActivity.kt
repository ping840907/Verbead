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

        fun openWebUrl(url: String) {
            HapticUtil.click(this)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (ex: Exception) {
                Toast.makeText(this, getString(R.string.toast_url_open_error, ex.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View?>(R.id.row_credit_ppocr)?.setOnClickListener {
            openWebUrl("https://github.com/PaddlePaddle/PaddleOCR")
        }
        findViewById<View?>(R.id.row_credit_zxing)?.setOnClickListener {
            openWebUrl("https://github.com/zxing-cpp/zxing-cpp")
        }
        findViewById<View?>(R.id.row_credit_sherpa)?.setOnClickListener {
            openWebUrl("https://github.com/k2-fsa/sherpa-onnx")
        }
        findViewById<View?>(R.id.row_credit_xasr)?.setOnClickListener {
            openWebUrl("https://huggingface.co/Luigi/x-asr-zh-tw-en-streaming-ft75m")
        }
        findViewById<View?>(R.id.row_credit_qwen)?.setOnClickListener {
            openWebUrl("https://github.com/QwenLM/Qwen3-ASR")
        }
        findViewById<View?>(R.id.row_credit_onnx)?.setOnClickListener {
            openWebUrl("https://github.com/microsoft/onnxruntime")
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
}
