package com.ping.verbead.util

import android.widget.Toast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastUtilTest {

    @Test
    fun cjkCountsDoubleWhenMeasuringReadingLength() {
        assertEquals(5, ToastUtil.weight("Hello"))
        assertEquals(6, ToastUtil.weight("已複製"))
        assertEquals(7, ToastUtil.weight("Hi 已複"))
    }

    @Test
    fun longTextGetsTheLongToast() {
        assertEquals(Toast.LENGTH_SHORT, ToastUtil.durationFor("已複製"))
        assertEquals(Toast.LENGTH_SHORT, ToastUtil.durationFor("Copied"))
        assertEquals(Toast.LENGTH_LONG, ToastUtil.durationFor("Please grant microphone permission to use voice input"))
        assertEquals(Toast.LENGTH_LONG, ToastUtil.durationFor("語音啟動失敗：麥克風正被其他應用程式使用"))
    }

    @Test
    fun sameTextIsSuppressedForAShortTimeButOtherTextAndLaterRepeatsPass() {
        val d = ToastUtil.Debouncer(2000L)
        assertTrue(d.allow("已複製", 1_000L))
        assertFalse(d.allow("已複製", 1_500L))      // repeated tap
        assertTrue(d.allow("轉譯失敗", 1_600L))     // different text
        assertTrue(d.allow("已複製", 3_700L))       // far enough from the last time it was shown
        assertFalse(d.allow("已複製", 4_000L))
    }
}
