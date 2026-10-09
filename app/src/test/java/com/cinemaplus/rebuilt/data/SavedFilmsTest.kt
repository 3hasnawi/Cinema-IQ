package com.cinemaplus.rebuilt.data

import org.junit.Assert.*
import org.junit.Test

class SavedFilmsTest {
    @Test fun missingTextIsAnEmptyList() {
        assertEquals(emptyList<Film>(), SavedFilms.parse(null))
    }

    @Test fun damagedTextIsReportedAsNullInsteadOfThrowing() {
        assertNull(SavedFilms.parse("{not an array"))
        assertNull(SavedFilms.parse("[{\"nb\":"))
    }

    @Test fun validTextKeepsItsFilms() {
        val films = SavedFilms.parse("""[{"nb":"1","ar_title":"عمل"},{"nb":"2","ar_title":"آخر"}]""")!!
        assertEquals(listOf("1", "2"), films.map { it.id })
    }
}
