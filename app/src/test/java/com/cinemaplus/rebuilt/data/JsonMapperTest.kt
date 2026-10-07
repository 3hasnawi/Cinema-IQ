package com.cinemaplus.rebuilt.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JsonMapperTest {
    @Test fun mapsOriginalFieldNamesAndRelativePoster() {
        val film = JsonMapper.film(JSONObject("""{"nb":42,"ar_title":"اختبار","imgMediumThumbObjUrl":"posters/42.jpg","kind":2,"translations":[{"file":"https://cdn.example/42.vtt"}]}"""))!!
        assertEquals("42", film.id)
        assertEquals("https://cnth2.shabakaty.cc/posters/42.jpg", film.poster)
        assertTrue(film.series)
        assertEquals("https://cdn.example/42.vtt", film.subtitles.single().url)
    }
    @Test fun mapsSeasonNumberObjectAndSortsSeasons() {
        val seasons = JsonMapper.seasons(JSONObject("""{"2":[{"nb":22,"season":2,"episodeNummer":1}],"1":[{"nb":11,"season":1,"episodeNummer":1}]}"""))
        assertEquals(listOf(1,2), seasons.map { it.number })
        assertEquals("11", seasons.first().episodes.first().id)
    }
    @Test fun rejectsMalformedAndInsecureStreamUrls() {
        val streams = JsonMapper.streams(JSONArray("""[{"videoUrl":"https://cdn.example/v.mp4","resolution":"720p"},{"videoUrl":"http://cdn.example/x.mp4"},{"resolution":"1080p"}]"""))
        assertEquals(1, streams.size)
        assertEquals("720p", streams.single().resolution)
    }
    @Test fun unwrapsResultEnvelopeWithoutTreatingMetadataAsFilms() {
        val films = JsonMapper.films(JSONObject("""{"data":[{"nb":10,"ar_title":"فيلم"}],"page":1}"""))
        assertEquals(1, films.size)
        assertEquals("فيلم", films.single().title)
        assertTrue(JsonMapper.films(JSONObject("""{"error":"unavailable"}""")).isEmpty())
    }
    @Test fun readsGroupsEnvelopeAndPreservesEmbeddedContent() {
        val groups = JsonMapper.array(JSONObject("""{"groups":[{"groupsID":5,"ar_title":"أحدث الأفلام","content":[{"nb":10,"ar_title":"فيلم"}]}]}"""))
        assertEquals("5", JsonMapper.text(groups.single(), "groupsID"))
        assertEquals("10", JsonMapper.films(groups.single().opt("content")).single().id)
    }
    @Test fun subtitleLanguagesAreNotAllArabic() {
        val subs = JsonMapper.subtitles(JSONArray("""[{"file":"https://cdn.example/a.vtt","language":"ar"},{"file":"https://cdn.example/b.vtt","language":"English"},{"file":"https://cdn.example/c.vtt","languageCode":"fr"}]"""))
        assertEquals(listOf("ar", "en", "fr"), subs.map { it.language })
        assertEquals(listOf("العربية", "الإنجليزية", "الفرنسية"), subs.map { it.title })
    }
    @Test fun subtitleFilenameIsFallbackAndUnknownLanguageIsNotInvented() {
        val subs = JsonMapper.subtitles(JSONArray("""[{"file":"https://cdn.example/movie.en.vtt"},{"file":"https://cdn.example/12345.vtt"}]"""))
        assertEquals("en", subs[0].language)
        assertEquals("الإنجليزية", subs[0].title)
        assertEquals("und", subs[1].language)
        assertEquals("ترجمة 2", subs[1].title)
    }
    @Test fun subtitleMetadataTakesPriorityOverFilenameAndSupportsNestedLanguage() {
        val subs = JsonMapper.subtitles(JSONArray("""[{"file":"https://cdn.example/movie.ar.vtt","language":{"code":"en-US"}},{"file":"https://cdn.example/another.vtt","lang_name":"English"}]"""))
        assertEquals("en-us", subs[0].language)
        assertEquals(listOf("الإنجليزية · 1", "الإنجليزية · 2"), subs.map { it.title })
    }
}
