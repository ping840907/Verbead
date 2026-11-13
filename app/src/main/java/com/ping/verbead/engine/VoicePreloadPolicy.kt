package com.ping.verbead.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Decides whether the speech models may be loaded in the background before the user asks for them, and remembers
 * what happened last time so that loading can never turn into a loop on a device that is short of memory.
 *
 * The rule that makes a loop impossible: a load that was pushed out by memory pressure (or that was cut short by
 * the system) stops all automatic loading for hours; from then on the models are only loaded when the user taps the
 * bubble, exactly as before. Only a load the user asked for ends that pause.
 */
class VoicePreloadPolicy(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {

    enum class Verdict { GO, DISABLED, EVICTED, STRUCK_OUT, TOO_SOON, LOW_MEMORY, POWER_SAVE, LOW_BATTERY, SCREEN_OFF }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The user's switch. Until it was touched it follows the device: on, except on a low-RAM device. */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, !isLowRamDevice())
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    private fun isLowRamDevice(): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    /** Whether a background load may start now. [userInitiated]: the user just picked a model or turned the option on. */
    fun check(requiredMb: Int, userInitiated: Boolean): Verdict {
        if (!isEnabled) return Verdict.DISABLED
        val t = now()
        if (!userInitiated && t < prefs.getLong(KEY_EVICTED_UNTIL, 0L)) return Verdict.EVICTED
        if (t < prefs.getLong(KEY_BLOCKED_UNTIL, 0L)) return Verdict.STRUCK_OUT
        if (!userInitiated && t - prefs.getLong(KEY_LAST_ATTEMPT, 0L) < MIN_INTERVAL_MS) return Verdict.TOO_SOON

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            // A low-RAM device stays out unless the user switched the option on themselves.
            if (am.isLowRamDevice && !prefs.contains(KEY_ENABLED)) return Verdict.LOW_MEMORY
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val availMb = info.availMem / (1024 * 1024)
            if (info.lowMemory || availMb < requiredMb * HEADROOM_FACTOR + HEADROOM_MB) return Verdict.LOW_MEMORY
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isPowerSaveMode == true) return Verdict.POWER_SAVE
        if (pm?.isInteractive == false) return Verdict.SCREEN_OFF
        if (isBatteryLow()) return Verdict.LOW_BATTERY
        return Verdict.GO
    }

    private fun isBatteryLow(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0 || scale <= 0 || charging) return false
        return level * 100 / scale < LOW_BATTERY_PERCENT
    }

    /** Written before a load starts and cleared when it ends: if it is still there at start-up, the load was cut short. */
    fun markAttemptStarted() {
        prefs.edit().putBoolean(KEY_IN_PROGRESS, true).putLong(KEY_LAST_ATTEMPT, now()).apply()
    }

    /** The load was given up on purpose (memory pressure): it did not fail, so it is no strike. */
    fun markAttemptAbandoned() {
        prefs.edit().putBoolean(KEY_IN_PROGRESS, false).apply()
    }

    fun markAttemptSucceeded() {
        prefs.edit().putBoolean(KEY_IN_PROGRESS, false).putInt(KEY_STRIKES, 0).apply()
    }

    fun markAttemptFailed() {
        val strikes = prefs.getInt(KEY_STRIKES, 0) + 1
        val pause = if (strikes >= 2) LONG_PAUSE_MS else SHORT_PAUSE_MS
        prefs.edit().putBoolean(KEY_IN_PROGRESS, false).putInt(KEY_STRIKES, strikes)
            .putLong(KEY_BLOCKED_UNTIL, now() + pause).apply()
        Log.w(TAG, "background load failed (strike $strikes): automatic loading paused for ${pause / 60_000} min")
    }

    /** The models were released because the system needs the memory: no automatic loading for a long while. */
    fun markEvicted() {
        prefs.edit().putLong(KEY_EVICTED_UNTIL, now() + EVICTION_PAUSE_MS).apply()
        Log.w(TAG, "released under memory pressure: automatic loading paused for ${EVICTION_PAUSE_MS / 3_600_000} h")
    }

    /** The user loaded the models by tapping the bubble and it worked: that ends the pauses. */
    fun onUserLoadSucceeded() {
        prefs.edit().remove(KEY_EVICTED_UNTIL).remove(KEY_BLOCKED_UNTIL).putInt(KEY_STRIKES, 0)
            .putBoolean(KEY_IN_PROGRESS, false).apply()
    }

    /** Call once per process start: finds loads that were cut short and processes the system killed for memory. */
    fun onProcessStart() {
        if (prefs.getBoolean(KEY_IN_PROGRESS, false)) {
            Log.w(TAG, "the previous background load never finished")
            markAttemptFailed()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val recentlyKilled = runCatching {
                am.getHistoricalProcessExitReasons(context.packageName, 0, 5).any {
                    it.reason == ApplicationExitInfo.REASON_LOW_MEMORY && now() - it.timestamp < KILLED_WINDOW_MS
                }
            }.getOrDefault(false)
            if (recentlyKilled) {
                Log.w(TAG, "the system recently killed this app for memory")
                markEvicted()
            }
        }
    }

    companion object {
        private const val TAG = "VoicePreload"
        private const val PREFS = "voice_preload"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_EVICTED_UNTIL = "evicted_until"
        private const val KEY_BLOCKED_UNTIL = "blocked_until"
        private const val KEY_LAST_ATTEMPT = "last_attempt"
        private const val KEY_IN_PROGRESS = "in_progress"
        private const val KEY_STRIKES = "strikes"

        private const val MIN_INTERVAL_MS = 10 * 60_000L
        private const val SHORT_PAUSE_MS = 60 * 60_000L
        private const val LONG_PAUSE_MS = 24 * 60 * 60_000L
        private const val EVICTION_PAUSE_MS = 6 * 60 * 60_000L
        private const val KILLED_WINDOW_MS = 60 * 60_000L
        private const val HEADROOM_FACTOR = 1.5
        private const val HEADROOM_MB = 300
        private const val LOW_BATTERY_PERCENT = 15

        /**
         * Set by the bubble service while it runs: called when the user has picked a model or turned the option on,
         * so the models start loading right then.
         */
        @Volatile
        var onUserWantsPreload: (() -> Unit)? = null
    }
}
