package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DawwirliTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/dawwirli/$name.json")!!.readText())
    @Test fun existingChoicesAndDefaultsStayCompatible() = runBlocking {
        assertEquals(SourceChoice.BOTH,SourceChoice.restore(null))
        assertEquals(SourceChoice.BOTH,SourceChoice.restore("BOTH"))
        assertEquals(listOf("daw"),SourceChoice.DAWWIRLI.requests(suspend { error("No Cinemana") },listOf(suspend { error("No VODU") }),listOf(suspend { "daw" })).map { it() })
        assertFalse(SourceChoice.BOTH.accepts("daw:series:64878"))
        assertFalse(SourceChoice.CINEMANA.accepts("daw:movie:123:mp4"))
        assertTrue(SourceChoice.DAWWIRLI.accepts("daw:series:64878"))
    }
    @Test fun multiPanelIdsAndContainersSurviveSavedListSerialization() {
        val film=DawwirliMapper.item(JSONObject("""{"id":"b~123","name":"EN - A Film","ext":"mkv"}"""))!!
        val restored=JsonMapper.film(FilmJson.encode(film))!!
        assertEquals(film.id,restored.id)
        val url=DawwirliMapper.stream(restored.id).url.toHttpUrl()
        assertEquals("b~123",url.queryParameter("id"))
        assertEquals("mkv",url.queryParameter("ext"))
        assertEquals("movie",url.queryParameter("kind"))
        assertEquals("premium.dawwirli.watch",url.host)
    }
    @Test fun realMovieDetailsUseSourceMetadata() {
        val f=DawwirliMapper.detail(Film("daw:movie:1501967:mp4","Placeholder"),fixture("movie"))
        assertTrue(f.title.contains("صقر"))
        assertEquals("02:04:38",f.duration)
        assertEquals("2026",f.year)
        assertTrue(f.poster.startsWith("https://"))
    }
    @Test fun realEpisodesCarryParentAndEpisodeIdentity() {
        val seasons=DawwirliMapper.seasons("daw:series:64878",fixture("series"))
        val ep=seasons.first().episodes.first()
        assertEquals("daw:series:64878",ep.seriesId)
        assertEquals(1,ep.episode)
        val restored=JsonMapper.film(FilmJson.encode(ep))!!
        assertEquals(ep.id,restored.id)
        assertEquals(ep.seriesId,restored.seriesId)
        val url=DawwirliMapper.stream(ep.id).url.toHttpUrl()
        assertEquals("1497401",url.queryParameter("id"))
        assertEquals("series",url.queryParameter("kind"))
    }
    @Test fun subtitlePathsResolveWithoutInventingEmbeddedTracks() {
        assertTrue(DawwirliMapper.subtitles(JSONObject("""{"tracks":[],"hardsub":true}""")).isEmpty())
        val subtitles=DawwirliMapper.subtitles(JSONObject("""{"tracks":[{"url":"/subs/a.srt","lang":"ar","label":"العربية"},{"url":"javascript:alert(1)"}]}"""))
        assertEquals(1,subtitles.size)
        assertEquals("https://premium.dawwirli.watch/subs/a.srt",subtitles[0].url)
        assertEquals("application/x-subrip",subtitles[0].mimeType)
    }
    @Test fun malformedIdsCannotBecomeRequestPaths() {
        listOf("daw:movie:../../a:mp4","daw:movie:x:mp4?token=a","daw:episode:x:y","other:123").forEach {
            assertTrue(runCatching { DawwirliMapper.stream(it) }.isFailure)
        }
    }
}
