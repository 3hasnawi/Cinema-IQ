package com.cinemaplus.rebuilt.ui.mobile

import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.Shelf
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneRecommendationsTest {
    private val target = Film("target", "Target", rating = "8.0", year = "2025", genreIds = setOf(28, 12))
    private fun recommend(vararg films: Film) = phoneRelatedFilms(target, listOf(Shelf("latest", "Latest", films.toList())))

    @Test fun sharedGenresOutrankRatingsAndExcludeOtherFormatsAndEpisodes() {
        val best = Film("best", "Best", genreIds = setOf(28, 12), rating = "6.0")
        val partial = Film("partial", "Partial", genreIds = setOf(28), rating = "8.0")
        val unrelated = Film("unrelated", "Unrelated", genreIds = setOf(35), rating = "8.0")
        val series = best.copy(id = "series", series = true)
        val episode = best.copy(id = "episode", episode = 1, seriesId = "parent")
        assertEquals(listOf("best", "partial"), recommend(partial, target, unrelated, series, episode, best).map { it.id })
    }

    @Test fun missingGenresDoNotBecomeArbitraryRecommendations() {
        val near = Film("near", "Near", rating = "8.1", year = "2024")
        val old = near.copy(id = "old", year = "1990")
        val low = near.copy(id = "low", rating = "4.0")
        val unrelated = near.copy(id = "unrelated", genreIds = setOf(35))
        assertEquals(emptyList<String>(), recommend(low, old, unrelated, near).map { it.id })
    }

    @Test fun sourceGenreMembershipWorksWithoutTmdbAndDeduplicatesTitles() {
        val peer = Film("peer", "Peer")
        val result = phoneRelatedFilms(target, listOf(
            Shelf("genre-m-7", "Action", listOf(target, peer)),
            Shelf("latest", "Latest", listOf(peer, Film("other", "Other")))))
        assertEquals(listOf("peer"), result.map { it.id })
    }
    @Test fun knownOriginalLanguageExcludesDifferentAndUnknownLanguages() {
        val english = target.copy(originalLanguage = "en")
        val match = target.copy(id = "english", originalLanguage = "en")
        val turkish = match.copy(id = "turkish", originalLanguage = "tr")
        val unknown = match.copy(id = "unknown", originalLanguage = "")
        val series = match.copy(id = "series", series = true)
        assertEquals(listOf("english"), phoneRelatedFilms(english,
            listOf(Shelf("latest", "Latest", listOf(turkish, unknown, series, match)))).map { it.id })
    }
}
