package com.cinemaplus.rebuilt.data

import org.junit.Assert.*
import org.junit.Test

class MoodsTest {
    private fun film(id: String) = Film(id, "t$id")
    private val source = listOf(
        Shelf("genre-m-1", "رعب", listOf(film("a"), film("b"))),
        Shelf("genre-s-1", "رعب وإثارة", listOf(film("c"))),
        Shelf("genre-m-2", "كوميدي", listOf(film("d"))),
        Shelf("home-new", "رعب", listOf(film("x"))))

    private fun mood(id: String) = Moods.all.first { it.id == id }

    @Test fun moodCollectsMatchingCategoriesSeparatedIntoMoviesAndSeries() {
        val shelves = Moods.shelves(mood("fear"), source)
        assertEquals(listOf("explore-movies", "explore-series"), shelves.map { it.id })
        assertEquals(listOf("a", "b"), shelves[0].films.map { it.id })
        assertEquals(listOf("c"), shelves[1].films.map { it.id })
    }

    @Test fun onlyGenreShelvesAreUsed() {
        assertTrue(Moods.shelves(mood("fear"), source).flatMap { it.films }.none { it.id == "x" })
    }

    @Test fun moodWithoutMatchingCategoryIsEmpty() {
        assertTrue(Moods.shelves(mood("space"), source).isEmpty())
    }

    @Test fun arabicLetterVariantsStillMatch() {
        assertTrue(Moods.matches(mood("fear"), "إثارة"))
        assertTrue(Moods.matches(mood("action"), "أكشن"))
        assertTrue(Moods.matches(mood("laugh"), "كوميديا"))
    }

    @Test fun duplicatesAcrossCategoriesAreRemoved() {
        val twice = listOf(Shelf("genre-m-1", "رعب", listOf(film("a"))), Shelf("genre-m-3", "إثارة", listOf(film("a"))))
        assertEquals(1, Moods.shelves(mood("fear"), twice).single().films.size)
    }

    @Test fun everyMoodHasKeywordsAndUniqueId() {
        assertEquals(Moods.all.size, Moods.all.map { it.id }.distinct().size)
        assertTrue(Moods.all.all { it.keywords.isNotEmpty() })
    }
}
