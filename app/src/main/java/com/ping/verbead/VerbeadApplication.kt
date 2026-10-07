package com.ping.verbead

import android.app.Application
import com.google.android.material.color.DynamicColors

class VerbeadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply Material You dynamic color theming across activities on Android 12+ (API 31+)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
