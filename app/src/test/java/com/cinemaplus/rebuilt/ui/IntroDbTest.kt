package com.cinemaplus.rebuilt.ui

import com.cinemaplus.rebuilt.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IntroDbTest {
    private fun response(segment: String) = JSONObject("""{"imdb_id":"tt123","is_movie":false,"season":1,"episode":2,"intro":$segment}""")
    @Test fun explicitSmallMillisecondsDoNotBecomeSeconds() {
        val markers = SkipMarkers.from(JSONObject("""{"introStartMs":5000,"introEndMs":30000}"""))
        assertEquals(SkipMarkers(5000, 30000), markers)
    }
    @Test fun metadataCannotReplaceCreditsBoundary() {
        val markers = SkipMarkers.from(JSONObject("""{"outro":{"start_ms":2400000,"duration":120,"confidence":1,"submission_count":7}}"""))
        assertEquals(2400000L, markers.creditsStartMs)
        assertEquals(SkipMarkers(), SkipMarkers.from(JSONObject("""{"credits":{"duration":120,"confidence":1}}""")))
    }
    @Test fun invalidNegativeStartIsNotTreatedAsZero() {
        assertEquals(SkipMarkers(), SkipMarkers.from(JSONObject("""{"intro_start_ms":-1,"intro_end_ms":90000}""")))
    }
    @Test fun declaredMillisecondsWinOverSecondsRegardlessOfFieldOrder() {
        val m = IntroDb.parse(response("""{"start_ms":5000,"end_ms":90000,"start_sec":9,"end_sec":10}"""), "tt123",1,2)
        assertEquals(SkipMarkers(5000,90000), m)
    }
    @Test fun wrongEpisodeOrShowIsRejected() {
        val o = response("""{"start_sec":5,"end_sec":90}""")
        assertEquals(SkipMarkers(), IntroDb.parse(o,"tt124",1,2))
        assertEquals(SkipMarkers(), IntroDb.parse(o,"tt123",1,3))
    }
    @Test fun sourceWinsPerSegmentAndFallbackFillsOnlyMissingBoundary() {
        assertEquals(SkipMarkers(1000,9000,50000), SkipMarkers(1000,9000).withFallback(SkipMarkers(2000,10000,50000)))
    }
    @Test fun invalidRangesUnknownDurationAndOutOfVideoAreNotOffered() {
        assertNull(PlaybackEndPolicy.skipIntroTarget(10,0,SkipMarkers(0,90000)))
        assertNull(PlaybackEndPolicy.skipIntroTarget(10,80000,SkipMarkers(0,90000)))
        assertNull(PlaybackEndPolicy.skipIntroTarget(10,100000,SkipMarkers(90000,80000)))
        assertEquals(SkipMarkers(), IntroDb.parse(response("""{"start_ms":5000,"end_ms":-1}"""),"tt123",1,2))
    }
    @Test fun invalidTimesAreRejected() {
        assertEquals(SkipMarkers(), SkipMarkers.from(JSONObject("""{"intro_end":"01:90"}""")))
        assertEquals(SkipMarkers(), SkipMarkers.from(JSONObject("""{"intro_end_ms":"Infinity"}""")))
    }
    @Test fun liveResponseShapeDoesNotTreatConfidenceAsOutroStart() {
        val o = JSONObject("""{"imdb_id":"tt0903747","is_movie":false,"season":1,"episode":1,"intro":null,"outro":{"start_sec":3431,"end_sec":3500,"start_ms":3431000,"end_ms":3500000,"confidence":1,"submission_count":1}}""")
        assertEquals(SkipMarkers(0,0,3431000), IntroDb.parse(o,"tt0903747",1,1))
    }
}
