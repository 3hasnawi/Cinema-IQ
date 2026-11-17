package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvPopupPositionTest {
    @Test fun centeredPopoverGrowsAboveThePressedIcon() {
        val placed = IptvPopupPosition.above(1920, 1080, 900, 960, 1000, 300, 200, 24, 8)
        assertEquals(800, placed.x); assertEquals(752, placed.y); assertEquals(.5f, placed.pivotX, .001f)
    }
    @Test fun edgeAnchorsRemainInsideTheViewportAndKeepTheirOrigin() {
        for (left in listOf(0, 1800)) {
            val p = IptvPopupPosition.above(1920, 1080, left, 960, left + 100, 400, 600, 24, 8)
            assertTrue(p.x >= 24 && p.x + 400 <= 1896)
            assertTrue(p.y >= 24 && p.y + 600 <= 1056)
            assertTrue(p.pivotX >= .08f && p.pivotX <= .92f)
        }
    }
}
