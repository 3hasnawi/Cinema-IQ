package com.cinemaplus.rebuilt.ui.mobile

import com.cinemaplus.rebuilt.Shelf
import com.cinemaplus.rebuilt.data.Film
import org.junit.Assert.*
import org.junit.Test

class PhoneFeaturedTest {
    private fun film(id: String, rating: String, year: String, backdrop: String = "b$id") =
        Film(id = id, title = id, poster = "p$id", backdrop = backdrop, rating = rating, year = year)

    @Test fun featuredIsDistinctBoundedAndLeadsWithTheBestRated() {
        val films = (1..14).map { film("f$it", "%.1f".format(5 + it * .2), (2000 + it).toString()) }
        val featured = phoneFeaturedFilms(listOf(Shelf("a", "A", films), Shelf("b", "B", films.take(5))))
        assertEquals(featured.map { it.id }.distinct(), featured.map { it.id })
        assertTrue(featured.size <= 10)
        assertEquals("f14", featured.first().id)
    }

    @Test fun titlesWithoutBackdropsAreSkippedWhenEnoughRealOnesExist() {
        val films = listOf(film("a", "8", "2020"), film("b", "7", "2021"), film("c", "6", "2022"), film("d", "9", "2023", backdrop = ""))
        assertFalse(phoneFeaturedFilms(listOf(Shelf("s", "S", films))).any { it.id == "d" })
    }

    @Test fun fallsBackToPostersWhenBackdropsAreScarce() {
        val films = listOf(film("a", "8", "2020", backdrop = ""), film("b", "7", "2021", backdrop = ""))
        assertEquals(2, phoneFeaturedFilms(listOf(Shelf("s", "S", films))).size)
    }

    @Test fun emptyShelvesGiveNoFeatured() {
        assertTrue(phoneFeaturedFilms(emptyList()).isEmpty())
    }
}
