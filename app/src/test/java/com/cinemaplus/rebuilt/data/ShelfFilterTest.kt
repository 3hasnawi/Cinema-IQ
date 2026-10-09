package com.cinemaplus.rebuilt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShelfFilterTest {
    private val films = listOf(
        Film("a", "ب", rating = "6.0", year = "2019"),
        Film("b", "أ", rating = "8.5/10", year = "2023", series = true),
        Film("c", "ج", rating = "", year = ""),
    )
    @Test fun kindFiltersSeriesAndMovies() {
        assertEquals(listOf("b"), FilmFilter(KindFilter.Series).apply(films).map { it.id })
        assertEquals(listOf("a", "c"), FilmFilter(KindFilter.Movies).apply(films).map { it.id })
    }
    @Test fun ratingSortPutsUnratedLast() {
        assertEquals(listOf("b", "a", "c"), FilmFilter(sort = SortMode.Rating).apply(films).map { it.id })
    }
    @Test fun yearSortPutsUnknownLast() {
        assertEquals(listOf("b", "a", "c"), FilmFilter(sort = SortMode.Year).apply(films).map { it.id })
    }
    @Test fun defaultKeepsOrderAndIsInactive() {
        assertEquals(films, FilmFilter().apply(films))
        assertFalse(FilmFilter().active)
    }
    @Test fun cyclingWraps() {
        assertEquals(KindFilter.All, FilmFilter(KindFilter.Series).nextKind().kind)
        assertEquals(SortMode.Default, FilmFilter(sort = SortMode.Title).nextSort().sort)
    }
    @Test fun historyIsNewestFirstDeduplicatedAndCapped() {
        var h = emptyList<String>()
        for (q in listOf("one", "two", "ONE")) h = SearchHistory.add(h, q)
        assertEquals(listOf("ONE", "two"), h)
        for (i in 1..12) h = SearchHistory.add(h, "q$i")
        assertEquals(SearchHistory.LIMIT, h.size)
        assertEquals("q12", h.first())
    }
    @Test fun shortQueriesAreNotRemembered() {
        assertEquals(emptyList<String>(), SearchHistory.add(emptyList(), " a "))
        assertEquals(listOf("x1"), SearchHistory.remove(listOf("x1", "x2"), "x2"))
    }
}
