package com.cinemaplus.rebuilt.data

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class VoduParserTest {
    private fun fixture(name: String) = Jsoup.parse(
        javaClass.getResource("/vodu/$name.html")!!.readText(), VoduApi.BASE)

    @Test fun movieMetadataAndPlayback() {
        val doc = fixture("movie")
        val film = VoduParser.detail(doc, Film("vodu:98489", ""))
        assertEquals("The Wild Robot", film.title)
        assertEquals("2024", film.year)
        assertTrue(film.description.isNotBlank())
        assertFalse(film.series)
        assertEquals("ar", film.subtitles.single().language)
        assertTrue(film.subtitles.single().url.endsWith(".webvtt"))
        assertEquals(listOf("720p", "1080p", "2160p", "360p"),
            VoduParser.streams(VoduParser.playAnchor(doc, film.id)!!).map { it.resolution })
    }

    @Test fun episodesUseParentAndDistinctIds() {
        val doc = fixture("series")
        val season = VoduParser.seasons(doc, "85752").single()
        assertEquals(1, season.number)
        assertEquals(10, season.episodes.size)
        val first = season.episodes.first()
        assertEquals("vodu:85752:14205032", first.id)
        assertEquals("85752", VoduApi.parentId(first.id))
        assertEquals(1, first.episode)
        assertEquals(3, VoduParser.streams(VoduParser.playAnchor(doc, first.id)!!).size)
        assertEquals(first.title, VoduParser.detail(doc, first).title)
        assertNull(VoduParser.playAnchor(doc, "vodu:85752:999999999"))
    }

    @Test fun catalogIdsSurviveLibraryMapping() {
        val films = VoduParser.catalog(fixture("catalog"), false)
        assertTrue(films.isNotEmpty())
        assertTrue(films.all { it.id.startsWith("vodu:") && it.poster.startsWith("https://movie.vodu.me/") })
        val saved = org.json.JSONObject().put("nb", films.first().id).put("ar_title", films.first().title)
        assertEquals(films.first().id, JsonMapper.film(saved)!!.id)
    }

    @Test fun unavailableAndMissingQualities() {
        val doc = Jsoup.parse("<a class='play' data-url='' data-webvtt='' data-url360='https://example.com/a.mp4'></a>", VoduApi.BASE)
        assertEquals(listOf("360p"), VoduParser.streams(doc.selectFirst("a")!!).map { it.resolution })
        assertTrue(VoduParser.subtitles(doc.selectFirst("a")).isEmpty())
        assertFalse(VoduApi.owns("98489"))
        assertTrue(VoduApi.owns("vodu:98489"))
        assertThrows(IllegalArgumentException::class.java) { VoduApi.parentId("vodu:../1") }
        assertThrows(IllegalStateException::class.java) { VoduParser.detail(Jsoup.parse("<html>Blocked</html>"), Film("vodu:1", "")) }
    }
}
