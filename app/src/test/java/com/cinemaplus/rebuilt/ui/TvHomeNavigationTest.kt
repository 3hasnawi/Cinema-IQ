package com.cinemaplus.rebuilt.ui

import org.junit.Assert.*
import org.junit.Test

class TvHomeNavigationTest {
    @Test fun physicalArrowsFollowRTLOrderAndWrap() {
        assertEquals(1, TvHomeNavigation.carouselIndex(0, 4, right = false, rtl = true))
        assertEquals(3, TvHomeNavigation.carouselIndex(0, 4, right = true, rtl = true))
        assertEquals(0, TvHomeNavigation.carouselIndex(3, 4, right = false, rtl = true))
        assertEquals(1, TvHomeNavigation.carouselIndex(0, 4, right = true, rtl = false))
        assertEquals(3, TvHomeNavigation.carouselIndex(0, 4, right = false, rtl = false))
    }
    @Test fun emptySingleAndShrunkenCarouselAreSafe() {
        assertEquals(0, TvHomeNavigation.carouselIndex(8, 0, right = false, rtl = true))
        assertEquals(0, TvHomeNavigation.carouselIndex(8, 1, right = false, rtl = true))
        assertEquals(0, TvHomeNavigation.carouselIndex(8, 3, right = false, rtl = true))
    }
    @Test fun downEntersFirstAvailableShelfAndStopsAtEnd() {
        assertEquals(TvHomeNavigation.Destination.Shelf(0), TvHomeNavigation.verticalTarget(null, true, 3, true))
        assertEquals(TvHomeNavigation.Destination.Shelf(1), TvHomeNavigation.verticalTarget(0, true, 3, true))
        assertEquals(TvHomeNavigation.Destination.None, TvHomeNavigation.verticalTarget(2, true, 3, true))
        assertEquals(TvHomeNavigation.Destination.None, TvHomeNavigation.verticalTarget(null, true, 0, true))
    }
    @Test fun upReturnsThroughShelvesHeroThenRail() {
        assertEquals(TvHomeNavigation.Destination.Shelf(0), TvHomeNavigation.verticalTarget(1, false, 3, true))
        assertEquals(TvHomeNavigation.Destination.Hero, TvHomeNavigation.verticalTarget(0, false, 3, true))
        assertEquals(TvHomeNavigation.Destination.Rail, TvHomeNavigation.verticalTarget(null, false, 3, true))
        assertEquals(TvHomeNavigation.Destination.Rail, TvHomeNavigation.verticalTarget(0, false, 1, false))
        assertEquals(TvHomeNavigation.Destination.None, TvHomeNavigation.verticalTarget(3, false, 1, true))
    }
    @Test fun heldOKOpensOnceAndStrayReleaseNeverOpens() {
        val press = TvHomeSelectPress()
        assertFalse(press.update(false, 0))
        assertFalse(press.update(true, 0))
        assertTrue(press.armed)
        repeat(10) { assertFalse(press.update(true, it + 1)) }
        assertTrue(press.update(false, 0))
        assertFalse(press.update(false, 0))
        assertFalse(press.armed)
    }
    @Test fun losingFocusDisarmsOKUntilANewPhysicalPress() {
        val press = TvHomeSelectPress()
        press.update(true, 0)
        press.cancel()
        assertFalse(press.update(false, 0))
        assertFalse(press.update(true, 4))
        assertFalse(press.update(false, 0))
        press.update(true, 0)
        assertTrue(press.update(false, 0))
    }
}
