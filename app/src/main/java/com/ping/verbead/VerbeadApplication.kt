package com.ping.verbead

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.ping.verbead.engine.ModelConfig
import com.ping.verbead.util.AppLocale

class VerbeadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocale.restore(this)
        // models the user added by hand (custom_models.json in the models folder)
        runCatching { ModelConfig.reloadCustomModels(this) }
        // Apply Material You dynamic color theming across activities on Android 12+ (API 31+)
        DynamicColors.applyToActivitiesIfAvailable(this)
        // PP-OCR runs on LiteRT now: drop the ONNX models older versions downloaded
        Thread { runCatching { ModelConfig.deleteLegacyOcrOnnx(this) } }.start()
    }
}
