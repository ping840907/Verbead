package com.ping.verbead.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingRegionTest {

    private val window = intArrayOf(0, 0, 1200, 2600)

    private fun item(top: Int, text: String) = ScreenTextItem(100, top, 900, top + 60, text)

    @Test
    fun aBigScrollingAreaBecomesTheRegion() {
        assertNotNull(ReadingRegion.of(intArrayOf(0, 266, 1200, 2550), window))
    }

    @Test
    fun aSmallScrollingAreaIsIgnored() {
        assertNull(ReadingRegion.of(intArrayOf(0, 1000, 1200, 1400), window))
    }

    @Test
    fun noScrollingAreaMeansNoRegion() {
        assertNull(ReadingRegion.of(null, window))
    }

    @Test
    fun theBrowserChromeAboveTheContentIsLeftOut() {
        val region = ReadingRegion.of(intArrayOf(0, 266, 1200, 2550), window)!!
        val kept = region.filter(listOf(item(150, "address bar"), item(700, "article"), item(2570, "bottom bar")))
        assertEquals(listOf("article"), kept.map { it.text })
    }

    @Test
    fun ifNothingIsInsideTheRegionEverythingIsKept() {
        val region = ReadingRegion.of(intArrayOf(0, 266, 1200, 2550), window)!!
        val all = listOf(item(100, "a"), item(2580, "b"))
        assertEquals(all, region.filter(all))
    }
}
