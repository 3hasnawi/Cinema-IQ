package com.cinemaplus.rebuilt.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TmdbSeasonTest {
    private fun metadata() = TmdbMetadata.parseSeason(JSONObject("""{
        "season_number":2,"name":"Season Two","overview":"Season story","poster_path":"/poster.jpg",
        "episodes":[{"id":100,"episode_number":1,"name":"Episode One","overview":"Episode story",
            "still_path":"/still.jpg","runtime":49,"vote_count":20,"vote_average":8.4,"air_date":"2026-01-02"},
            {"id":101,"episode_number":2,"runtime":null,"vote_count":0,"vote_average":0}]
    }"""))

    @Test fun parsesSeasonAndEpisodeMetadataWithoutAnImdbLabel() {
        val season = metadata()
        assertEquals("Season story", season.description)
        val episode = season.episodes.first()
        assertEquals("Episode story", episode.description)
        assertEquals("2940", episode.duration)
        assertEquals("8.4", episode.tmdbRating)
        assertEquals("", episode.imdbRating)
        assertEquals("2026-01-02", episode.airDate)
        assertEquals("https://image.tmdb.org/t/p/w780/still.jpg", episode.backdrop)
        assertEquals("", season.episodes[1].duration)
        assertEquals("", season.episodes[1].tmdbRating)
    }

    @Test fun mergesByEpisodeNumberPreservingSourceIdentityAndAvailability() {
        val original = Film("source-1", "Source title", season=2, episode=1, seriesId="source-show", imdbRating="9.1")
        val unmatched = Film("source-9", "Special", season=2, episode=9)
        val result = TmdbMetadata.mergeSeason(Season(2, listOf(original, unmatched)), metadata())
        assertEquals(listOf("source-1", "source-9"), result.episodes.map { it.id })
        assertEquals("source-show", result.episodes[0].seriesId)
        assertEquals("Source title", result.episodes[0].title)
        assertEquals("Episode One", result.episodes[0].episodeTitle)
        assertEquals("9.1", result.episodes[0].imdbRating)
        assertEquals("Episode story", result.episodes[0].description)
        assertEquals(unmatched, result.episodes[1])
        assertEquals("8.4", result.episodes[0].tmdbRating)
    }

    @Test fun rejectsDifferentSeasonAndRetainsKnownDuration() {
        val source = Season(3, listOf(Film("source", "title", season=3, episode=1)))
        assertEquals(source, TmdbMetadata.mergeSeason(source, metadata()))
        val runtime = Season(2, listOf(Film("source", "title", season=2, episode=1, duration="3600")))
        assertEquals("3600", TmdbMetadata.mergeSeason(runtime, metadata()).episodes[0].duration)
    }
}
