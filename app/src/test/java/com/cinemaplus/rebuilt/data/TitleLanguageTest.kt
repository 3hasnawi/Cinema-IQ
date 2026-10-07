package com.cinemaplus.rebuilt.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class TitleLanguageTest {
    private val film = Film("1", "اسم عربي", enTitle = "English Title", originalTitle = "Original Title")
    @Test fun selectsRequestedTitleWithoutChangingSourceFilm() {
        assertEquals("اسم عربي", film.displayTitle(TitleLanguage.ARABIC))
        assertEquals("English Title", film.displayTitle(TitleLanguage.ENGLISH))
        assertEquals("Original Title", film.displayTitle(TitleLanguage.ORIGINAL))
        assertEquals("اسم عربي", film.title)
    }
    @Test fun missingTitlesFallBackToSourceTitle() {
        val missing = Film("1", "Source Title")
        assertEquals("Source Title", missing.displayTitle(TitleLanguage.ENGLISH))
        assertEquals("Source Title", missing.displayTitle(TitleLanguage.ORIGINAL))
        assertEquals(TitleLanguage.ARABIC, TitleLanguage.restore("unknown"))
    }
    @Test fun parsesSourceQualityEditionAndOriginalName() {
        val parsed = JsonMapper.film(JSONObject("""{"nb":"1","ar_title":"Title","original_name":"Original","videoQuality":"1080p","videoVersion":"WEB-DL"}"""))!!
        assertEquals("Original", parsed.originalTitle)
        assertEquals("1080p", parsed.contentQuality)
        assertEquals("WEB-DL", parsed.edition)
    }
    @Test fun absentSourceMetadataDoesNotInventQualityOrEdition() {
        val parsed = JsonMapper.film(JSONObject("""{"nb":"1","ar_title":"Title"}"""))!!
        assertEquals("", parsed.contentQuality)
        assertEquals("", parsed.edition)
        assertEquals("", availableQuality(emptyList()))
    }
    @Test fun bestAvailableQualityUsesNumericOrderIncluding4K() {
        assertEquals("1080p", availableQuality(listOf(Stream("a", "720p"), Stream("b", "1080p"), Stream("c", "480p"))))
        assertEquals("4K", availableQuality(listOf(Stream("a", "1080p"), Stream("b", "4K"))))
    }
}
