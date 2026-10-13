package com.cinemaplus.rebuilt.data

import com.cinemaplus.rebuilt.cloud.CloudLibraryRecords
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WatchedHistoryTest {
    @Test fun completionHistoryKeepsEpisodesAndDeduplicatesRewatch() {
        var raw = WatchedHistory.add(null, Film("e1","Episode 1",seriesId="show",episode=1),10)
        raw = WatchedHistory.add(raw, Film("e2","Episode 2",seriesId="show",episode=2),20)
        raw = WatchedHistory.add(raw, Film("e1","Episode 1",seriesId="show",episode=1),30)
        val list = JSONArray(raw)
        assertEquals(2,list.length())
        assertEquals("e1",list.getJSONObject(0).getString("nb"))
        assertEquals(30L,list.getJSONObject(0).getLong("watchedAt"))
    }
    @Test fun historyIsBoundedAndDamagedStorageIsRecoverable() {
        var raw = "bad"
        repeat(WatchedHistory.LIMIT+1) { raw = WatchedHistory.add(raw,Film("$it","Work"),it.toLong()) }
        val list = JSONArray(raw)
        assertEquals(WatchedHistory.LIMIT,list.length())
        assertEquals("500",list.getJSONObject(0).getString("nb"))
    }
    @Test fun historySurvivesRewatchResetAndUsesExistingCloudPlaybackPath() {
        val raw = WatchedHistory.add(null,Film("a","Movie"),10)
        val records = CloudLibraryRecords.encode(mapOf("watched" to raw,"completed_a" to false,"position_a" to 0L))
        assertEquals(setOf("playback/a"),records.keys)
        val restored = CloudLibraryRecords.decode(records)
        assertEquals(false,restored["completed_a"])
        assertEquals("a",JSONArray(restored["watched"] as String).getJSONObject(0).getString("nb"))
    }
    @Test fun oldCloudProgressRecordsRemainReadable() {
        val restored = CloudLibraryRecords.decode(mapOf("playback/a" to "{\"position\":12,\"completed\":true}"))
        assertEquals(12L,restored["position_a"])
        assertEquals("[]",restored["watched"])
    }
    @Test fun olderDeviceProgressDoesNotEraseLocalHistory() {
        val previous = WatchedHistory.add(null,Film("a","Movie"),10)
        val restored = CloudLibraryRecords.decode(mapOf("playback/a" to "{\"position\":12,\"completed\":false}"),previous)
        assertEquals(1,JSONArray(restored["watched"] as String).length())
    }
}
