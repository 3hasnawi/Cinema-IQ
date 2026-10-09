package com.cinemaplus.rebuilt.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun cyclesThroughStepsAndWraps() {
        assertEquals(1.25f, PlaybackSpeed.next(1f), 0f)
        assertEquals(0.75f, PlaybackSpeed.next(2f), 0f)
    }
    @Test fun unknownSpeedResetsToNormal() {
        assertEquals(1f, PlaybackSpeed.next(3.3f), 0f)
    }
    @Test fun labelsAreCompact() {
        assertEquals("1×", PlaybackSpeed.label(1f))
        assertEquals("1.25×", PlaybackSpeed.label(1.25f))
        assertEquals("0.75×", PlaybackSpeed.label(0.75f))
        assertEquals("1.5×", PlaybackSpeed.label(1.5f))
    }
}
