package com.cinemaplus.rebuilt.cloud

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

class CloudLibraryRecordsTest {
    @Test fun explicitRemovalCreatesTombstoneWithoutRemovingOtherDeviceTitles() {
        val before = mapOf("favorite/a" to "{\"nb\":\"a\"}")
        val change = CloudLibraryRecords.changes(before, emptyMap())
        assertEquals("", change["favorite/a"])
        val combined = mapOf("favorite/b" to "{\"nb\":\"b\"}") + change
        assertEquals("b", JSONArray(CloudLibraryRecords.decode(combined)["favorites"] as String).getJSONObject(0).getString("nb"))
    }
    @Test fun playbackStateIsAnAtomicRecordAndResetIsNotMaxPosition() {
        val records = CloudLibraryRecords.encode(mapOf("position_a" to 0L, "duration_a" to 1000L, "completed_a" to false))
        val restored = CloudLibraryRecords.decode(records)
        assertEquals(0L, restored["position_a"])
        assertEquals(1000L, restored["duration_a"])
        assertEquals(false, restored["completed_a"])
    }
    @Test fun sourceCredentialsAndDeviceSettingsAreExcluded() {
        val records = CloudLibraryRecords.encode(mapOf("token" to "private", "password" to "secret", "content_source" to "VODU", "quality" to "1080p"))
        assertTrue(records.isEmpty())
    }
    @Test fun nextEpisodeReplacesOneSeriesRecord() {
        val list = "[{\"nb\":\"e2\",\"seriesId\":\"show\"}]"
        val records = CloudLibraryRecords.encode(mapOf("recent" to list))
        assertEquals(setOf("recent/show"), records.keys)
    }
    @Test fun invalidListItemsDoNotCrashSync() {
        val records = CloudLibraryRecords.encode(mapOf("favorites" to "[null,42,{\"nb\":\"vodu:123\"}]"))
        assertEquals(setOf("favorite/vodu:123"), records.keys)
    }
    @Test fun sourcesWithDifferentIdsStaySeparate() {
        val records = CloudLibraryRecords.encode(mapOf("favorites" to "[{\"nb\":\"123\"},{\"nb\":\"vodu:123\"}]"))
        assertEquals(2, JSONArray(CloudLibraryRecords.decode(records)["favorites"] as String).length())
    }
}
