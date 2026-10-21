package com.ping.verbead.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import java.util.concurrent.Executors

object HapticUtil {

    @Volatile
    private var cachedVibrator: Vibrator? = null

    @Volatile
    private var hasVibratorCached: Boolean? = null

    private val hapticExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "verbead-haptics").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
        }
    }

    private val effectTick by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createOneShot(10, 50)
        } else null
    }

    private val effectClick by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createOneShot(25, 130)
        } else null
    }

    private val effectHeavyClick by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE)
        } else null
    }

    private fun getVibrator(context: Context): Vibrator? {
        cachedVibrator?.let { return it }
        val appContext = context.applicationContext ?: context
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        cachedVibrator = vibrator
        return vibrator
    }

    private fun hasVibrator(context: Context): Boolean {
        hasVibratorCached?.let { return it }
        val vibrator = getVibrator(context) ?: run {
            hasVibratorCached = false
            return false
        }
        val has = try {
            vibrator.hasVibrator()
        } catch (_: Throwable) {
            false
        }
        hasVibratorCached = has
        return has
    }

    /**
     * Light tick feedback (TICK)
     * Triggered during:
     * - Dragging the floating bubble (every 24dp movement)
     * - Hovering across items in the capsule menu
     * - Dragging the zoom slider
     */
    fun tick(context: Context) {
        if (!hasVibrator(context)) return
        val vibrator = getVibrator(context) ?: return
        hapticExecutor.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    effectTick?.let { vibrator.vibrate(it) }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(10)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun tick(view: View) {
        tick(view.context)
    }

    /**
     * Standard click feedback (CLICK)
     * Triggered during:
     * - Normal button clicks
     * - Floating bubble snapping and docking to the screen edge
     */
    fun click(context: Context) {
        if (!hasVibrator(context)) return
        val vibrator = getVibrator(context) ?: return
        hapticExecutor.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    effectClick?.let { vibrator.vibrate(it) }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(25)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun click(view: View) {
        click(view.context)
    }

    /**
     * Strong / Heavy click feedback (HEAVY_CLICK)
     * Triggered during:
     * - 400ms long-press triggering capsule menu expansion
     * - Text / Barcode recognition successfully injected into target field
     */
    fun heavyClick(context: Context) {
        if (!hasVibrator(context)) return
        val vibrator = getVibrator(context) ?: return
        hapticExecutor.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    effectHeavyClick?.let { vibrator.vibrate(it) }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(55)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun heavyClick(view: View) {
        heavyClick(view.context)
    }
}
