package com.cinemaplus.rebuilt.ui

import com.cinemaplus.rebuilt.data.SkipMarkers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipMarkersTest {
    @Test fun readsSecondsAndMinuteStrings() {
        val m = SkipMarkers.from(JSONObject("""{"introStart":5,"introEnd":"01:30","creditsStart":2400}"""))
        assertEquals(5_000L, m.introStartMs)
        assertEquals(90_000L, m.introEndMs)
        assertEquals(2_400_000L, m.creditsStartMs)
    }

    @Test fun missingDataGivesEmptyMarkers() {
        assertEquals(SkipMarkers(), SkipMarkers.from(JSONObject("""{"title":"x"}""")))
    }

    @Test fun skipIntroOnlyInsideTheIntro() {
        val m = SkipMarkers(10_000, 90_000, 0)
        assertEquals(90_000L, PlaybackEndPolicy.skipIntroTarget(30_000, 1_500_000, m))
        assertNull(PlaybackEndPolicy.skipIntroTarget(95_000, 1_500_000, m))
        assertNull(PlaybackEndPolicy.skipIntroTarget(30_000, 1_500_000, SkipMarkers()))
    }
}
