package com.cinemaplus.rebuilt.ui.mobile

import org.junit.Assert.*
import org.junit.Test

class MobilePlayerControlsTest {
    @Test fun timeIsFormattedLikeTheTvPlayer() {
        assertEquals("00:00", formatMobileTime(-5))
        assertEquals("03:52", formatMobileTime(232_000))
        assertEquals("2:16:14", formatMobileTime(8_174_000))
    }

    @Test fun touchPositionMapsToTheVideoAndStaysInsideIt() {
        assertEquals(50_000L, mobileSeekTarget(200f, 400, 100_000))
        assertEquals(0L, mobileSeekTarget(-30f, 400, 100_000))
        assertEquals(100_000L, mobileSeekTarget(900f, 400, 100_000))
    }

    @Test fun unknownDurationOrWidthNeverSeeks() {
        assertEquals(0L, mobileSeekTarget(100f, 400, 0))
        assertEquals(0L, mobileSeekTarget(100f, 0, 100_000))
    }
}
