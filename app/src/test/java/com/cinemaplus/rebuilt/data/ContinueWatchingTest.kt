package com.cinemaplus.rebuilt.data

import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingTest {
    private fun episode(id: String, number: Int) = Film(id, id, episode = number)
    @Test fun nextEpisodeUsesEpisodeOrder() {
        val seasons = listOf(Season(1, listOf(episode("b", 2), episode("a", 1))))
        assertEquals("b", ContinueWatching.next(seasons, "a")?.id)
    }
    @Test fun movesToNextSeasonAndNormalizesSeasonNumber() {
        val seasons = listOf(Season(2, listOf(episode("b", 1))), Season(1, listOf(episode("a", 9))))
        val next = ContinueWatching.next(seasons, "a")
        assertEquals("b", next?.id)
        assertEquals(2, next?.season)
    }
    @Test fun finalEpisodeHasNoSuccessor() {
        assertNull(ContinueWatching.next(listOf(Season(1, listOf(episode("a", 1)))), "a"))
    }
    @Test fun unrelatedOrMissingEpisodeDoesNotSelectFirstEpisode() {
        assertNull(ContinueWatching.next(listOf(Season(1, listOf(episode("a", 1)))), "other"))
        assertNull(ContinueWatching.next(emptyList(), "a"))
    }
    @Test fun parentIdentitySurvivesLibraryJson() {
        val parsed = JsonMapper.film(org.json.JSONObject("""{"nb":"a","ar_title":"Episode","seriesId":"show","episodeNummer":2}"""))
        assertEquals("show", parsed?.seriesId)
        assertEquals(2, parsed?.episode)
    }
    @Test fun completedLastEpisodeDisappearsButNextReleaseCanBeOffered() {
        val completed = episode("a", 1).copy(seriesId = "show")
        val released = episode("b", 2).copy(seriesId = "show")
        val recent = ContinueWatching.replace(listOf(completed), completed, null, setOf("a"))
        assertTrue(recent.isEmpty())
        assertTrue(ContinueWatching.canOffer(completed, released, recent, null))
    }
    @Test fun newReleaseDoesNotOverwriteUnfinishedEpisodeOrActivePlayback() {
        val completed = episode("a", 1).copy(seriesId = "show")
        val released = episode("b", 2).copy(seriesId = "show")
        assertFalse(ContinueWatching.canOffer(completed, released, listOf(released), null))
        assertFalse(ContinueWatching.canOffer(completed, released, emptyList(), completed))
    }
    @Test fun differentProvidersDoNotBlockEachOther() {
        val completed = episode("a", 1).copy(seriesId = "42")
        val released = episode("b", 2).copy(seriesId = "42")
        val other = episode("vodu:42:1", 1).copy(seriesId = "vodu:42")
        assertTrue(ContinueWatching.canOffer(completed, released, listOf(other), other))
        assertFalse(ContinueWatching.canOffer(completed, other, emptyList(), null))
    }
    @Test fun replayingOlderEpisodeDoesNotAdvanceCheckpoint() {
        val current = episode("c", 3).copy(season = 1)
        assertFalse(ContinueWatching.isLater(episode("a", 1), current))
        assertFalse(ContinueWatching.isLater(current, current))
        assertTrue(ContinueWatching.isLater(episode("d", 4), current))
        assertTrue(ContinueWatching.isLater(episode("new-season", 1).copy(season = 2), current))
    }
    @Test fun noSuccessorUntilSourceAddsAnotherEpisode() {
        val completed = episode("a", 1).copy(seriesId = "show")
        assertNull(ContinueWatching.next(listOf(Season(1, listOf(completed))), "a"))
        val released = episode("b", 2).copy(seriesId = "show")
        assertEquals("b", ContinueWatching.next(listOf(Season(1, listOf(completed, released))), "a")?.id)
    }
    @Test fun completedShowReplacementKeepsOtherShowsAndRemovesDuplicateEpisodes() {
        val completed = episode("a", 1).copy(seriesId = "show")
        val released = episode("b", 2).copy(seriesId = "show")
        val unrelated = episode("other", 1).copy(seriesId = "different")
        val stale = episode("old", 0).copy(seriesId = "show")
        assertEquals(listOf(released, unrelated), ContinueWatching.replace(
            listOf(stale, completed, unrelated), completed, released, setOf("a", "b")))
    }
}
