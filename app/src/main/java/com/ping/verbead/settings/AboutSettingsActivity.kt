package com.ping.verbead.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.ping.verbead.R
import com.ping.verbead.util.HapticUtil

class AboutSettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_about)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            HapticUtil.click(this)
            finish()
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
}
