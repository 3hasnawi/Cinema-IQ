package com.cinemaplus.rebuilt.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentDurationTest {
    @Test fun secondsBecomeHoursAndMinutes() {
        assertEquals("01:30", formatContentDuration("5400"))
        assertEquals("48 دقيقة", formatContentDuration("2880.0"))
        assertEquals("02:00", formatContentDuration("7200"))
    }
    @Test fun largeNumericDurationsUseMilliseconds() {
        assertEquals("2 دقيقة", formatContentDuration("154887"))
        assertEquals("01:30", formatContentDuration("5400000"))
    }
    @Test fun clockFormatsAreSupported() {
        assertEquals("01:23", formatContentDuration("01:23:45"))
        assertEquals("48 دقيقة", formatContentDuration("48:00"))
    }
    @Test fun invalidDurationsAreHidden() {
        listOf("", "null", "NaN", "-1", "0", "unknown").forEach {
            assertEquals("", formatContentDuration(it))
        }
    }
}
