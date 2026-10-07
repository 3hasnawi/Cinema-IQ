package com.cinemaplus.rebuilt.ui.mobile

import com.cinemaplus.rebuilt.data.Stream
import org.junit.Assert.*
import org.junit.Test

class MobilePlaybackPolicyTest {
    @Test fun resolvesPreferencesAndFallsBackWhenQualityIsUnavailable() {
        val streams = listOf(Stream("a", "720p"), Stream("b", "1080p"))
        assertEquals(1, MobilePlaybackPolicy.preferredIndex(streams, "1080"))
        assertEquals(0, MobilePlaybackPolicy.preferredIndex(streams, "2160p"))
        assertEquals(0, MobilePlaybackPolicy.preferredIndex(emptyList(), "1080p"))
    }
    @Test fun switchingQualityUsesLivePositionInsteadOfOlderStoredProgress() {
        assertEquals(90000L, MobilePlaybackPolicy.resumePosition(true, 90000, 30000))
    }
    @Test fun changingEpisodeUsesThatEpisodesOwnSavedProgress() {
        assertEquals(30000L, MobilePlaybackPolicy.resumePosition(false, 90000, 30000))
        assertEquals(0L, MobilePlaybackPolicy.resumePosition(false, 90000, -1))
    }
}
