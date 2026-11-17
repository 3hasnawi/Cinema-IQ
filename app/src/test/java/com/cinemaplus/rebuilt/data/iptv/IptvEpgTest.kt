package com.cinemaplus.rebuilt.data.iptv

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test

class IptvEpgTest {
    private val now = 1_700_000_000_000L
    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)
    @Test fun xtreamDecodesArabicTitlesAndSelectsCurrentAndNextByEpochTime() {
        val title = "الأخبار".encodeUtf8().base64()
        val values = IptvEpgParser.xtream(bytes("""{"epg_listings":[
            {"title":"$title","start_timestamp":"1699999000","stop_timestamp":"1700001000"},
            {"title":"Next programme","start_timestamp":"1700001000","stop_timestamp":"1700002000"},
            {"title":"old","start_timestamp":"1600000000","stop_timestamp":"1600001000"}]}"""))
        val result = IptvEpgParser.nowNext(values, now)
        assertEquals("الأخبار", result.current!!.title)
        assertEquals("Next programme", result.next!!.title)
    }
    @Test fun missingMalformedAndEndedSchedulesHaveNoInventedInformation() {
        assertEquals(IptvNowNext(), IptvEpgParser.nowNext(emptyList(), now))
        assertTrue(IptvEpgParser.xtream(bytes("{}" )).isEmpty())
        val bad = IptvEpgParser.xtream(bytes("""{"epg_listings":[{"title":"broken","start_timestamp":"4","stop_timestamp":"3"}]}"""))
        assertTrue(bad.isEmpty())
        assertEquals(IptvNowNext(), IptvEpgParser.nowNext(listOf(IptvProgramme("ended", 1, 2)), now))
    }
    @Test fun xmltvReadsTimezoneOffsetsAndPrefersArabicWithoutJoiningTitles() {
        val data = bytes("""<?xml version="1.0"?><tv>
            <programme channel="news" start="20231115010000 +0300" stop="20231115020000 +0300"><title>News</title><title lang="ar">نشرة الأخبار</title></programme>
            <programme channel="news" start="20231115020000 +0300" stop="20231115030000 +0300"><title>Next</title></programme>
            </tv>""")
        val result = IptvEpgParser.nowNext(IptvEpgParser.xmltv(data, now).getValue("news"), now)
        assertEquals("نشرة الأخبار", result.current!!.title)
        assertEquals("Next", result.next!!.title)
    }
    @Test fun xmltvRejectsDtdAndExternalEntities() {
        val value = """<!DOCTYPE tv [<!ENTITY external SYSTEM "file:///does-not-exist">]><tv><programme><title>&external;</title></programme></tv>"""
        assertTrue(runCatching { IptvEpgParser.xmltv(bytes(value), now) }.isFailure)
    }
    @Test fun playlistDiscoversOnlyAConsentedXmltvUrlAndCachePreservesIt() {
        val source = IptvSource("one", "TV", IptvKind.M3U, "https://provider.example/list")
        val catalog = IptvParsers.playlist(bytes("#EXTM3U x-tvg-url=\"/guide.xml\"\n#EXTINF:-1 tvg-id=\"news\",News\nhttps://media.example/live.m3u8\n"), source)
        assertEquals("https://provider.example/guide.xml", catalog.epgUrl)
        val snapshot = IptvCatalogSnapshot(now, catalog)
        assertEquals(snapshot, IptvCatalogCodec.decode(source, IptvCatalogCodec.encode(source, snapshot)))
        val blocked = IptvParsers.playlist(bytes("#EXTM3U x-tvg-url=\"http://provider.example/guide.xml\"\nhttps://media.example/live.m3u8\n"), source)
        assertNull(blocked.epgUrl)
    }
    @Test fun optionalXtreamMetadataIsCachedAndUsesOnlyTheCurrentSourceChannel() = runBlocking {
        var calls = 0
        val source = IptvSource("one", "TV", IptvKind.Xtream, "https://provider.example", "fixture-user", "fixture-password")
        val transport = IptvTransport { url, _ ->
            calls++
            val parsed = url.toHttpUrl()
            assertEquals("get_short_epg", parsed.queryParameter("action"))
            assertEquals("7", parsed.queryParameter("stream_id"))
            bytes("""{"epg_listings":[]}""")
        }
        val epg = IptvEpgClient(transport) { now }
        val channel = IptvChannel("one:7", "one", "News", "News", IptvClient.liveUrl(source, "7"))
        assertEquals(IptvNowNext(), epg.load(source, channel, null))
        assertEquals(IptvNowNext(), epg.load(source, channel, null))
        assertEquals(IptvNowNext(), epg.load(source, channel.copy(sourceId = "foreign"), null))
        assertEquals(1, calls)
    }
    @Test fun unavailableMetadataIsOptionalAndRateLimitingCoversOtherChannels() = runBlocking {
        var calls = 0
        val source = IptvSource("one", "TV", IptvKind.Xtream, "https://provider.example", "fixture-user", "fixture-password")
        val client = IptvEpgClient(IptvTransport { _, _ -> calls++; throw IptvException(IptvException.Reason.Http, 429) }) { now }
        val channel = IptvChannel("one:7", "one", "News", "News", IptvClient.liveUrl(source, "7"))
        assertEquals(IptvNowNext(), client.load(source, channel, null))
        assertEquals(IptvNowNext(), client.load(source, channel.copy(id = "one:8", url = IptvClient.liveUrl(source, "8")), null))
        assertEquals(1, calls)
    }

}
