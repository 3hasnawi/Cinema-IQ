package com.cinemaplus.rebuilt.data.iptv

import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class IptvTest {
    private fun source(http: Boolean = false, id: String = "one") = IptvSource(id, "Test", IptvKind.M3U, "https://provider.example/list.m3u", allowHttp = http)
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun reason(wanted: IptvException.Reason, block: () -> Unit) {
        try { block(); fail("Expected $wanted") } catch (e: IptvException) { assertEquals(wanted, e.reason) }
    }
    @Test fun playlistParsesQuotedCommasArabicAndRelativeUrls() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:-1 tvg-id=\"news\" tvg-logo=\"/news.png\" group-title=\"News, live\",قناة الأخبار\r\nnews.m3u8?token=fixture\r\n#EXTINF:-1 group-title=\"Kids \\\"HD\\\"\",Kids\nhttps://cdn.example/kids.mp4\n"
        val channels = IptvParsers.playlist(bytes(text), source()).channels
        assertEquals(2, channels.size); assertEquals("قناة الأخبار", channels[0].name)
        assertEquals("News, live", channels[0].group); assertEquals("Kids \"HD\"", channels[1].group)
        assertEquals("https://provider.example/news.m3u8?token=fixture", channels[0].url)
        assertEquals("https://provider.example/news.png", channels[0].logo)
        assertFalse(channels[0].id.contains("fixture"))
    }
    @Test fun hlsManifestStaysOneChannelRatherThanSegmentsOrVariants() {
        for (text in listOf("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\npart.ts", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\n720.m3u8")) {
            val result = IptvParsers.playlist(bytes(text), source())
            assertEquals(1, result.channels.size); assertEquals(source().address, result.channels.single().url)
        }
    }
    @Test fun sourceIdsAreStableAcrossTokenRefreshAndPlaylistOrder() {
        fun list(token: String, reverse: Boolean): ByteArray {
            val a = "#EXTINF:-1,News\nhttps://cdn.example/news?token=$token\n"
            val b = "#EXTINF:-1,Sport\nhttps://cdn.example/sport?token=$token\n"
            return bytes("#EXTM3U\n" + if (reverse) b + a else a + b)
        }
        val old = IptvParsers.playlist(list("old", false), source()).channels
        val fresh = IptvParsers.playlist(list("new", true), source()).channels
        assertEquals(old[0].id, fresh[1].id); assertEquals(old[1].id, fresh[0].id)
        assertNotEquals(old[0].id, IptvParsers.playlist(list("old", false), source(id = "other")).channels[0].id)
    }
    @Test fun httpRequiresConsentEvenInsideHttpsPlaylistAndUnsafeUrlsAreRejected() {
        val text = bytes("#EXTM3U\n#EXTINF:-1,Secure\nhttps://cdn.example/a.m3u8\n#EXTINF:-1,Plain\nhttp://cdn.example/b.m3u8\n#EXTINF:-1,Bad\nfile:///bad\n")
        assertEquals(1, IptvParsers.playlist(text, source()).channels.size)
        assertEquals(2, IptvParsers.playlist(text, source(true)).channels.size)
        for (url in listOf("file:///tmp/a", "ftp://host.example/a", "https://u:p@host.example/a", "https://host.example/a#secret"))
            reason(IptvException.Reason.InvalidUrl) { IptvUrls.validate(url, true) }
        reason(IptvException.Reason.HttpConsent) { IptvUrls.validate("http://host.example/list", false) }
        assertTrue(IptvUrls.sameOrigin("http://a.example/x".toHttpUrl(), "https://a.example/y".toHttpUrl()))
        assertFalse(IptvUrls.sameOrigin("https://a.example/x".toHttpUrl(), "http://a.example/y".toHttpUrl()))
        assertFalse(IptvUrls.sameOrigin("https://a.example/x".toHttpUrl(), "https://b.example/y".toHttpUrl()))
    }
    @Test fun htmlAndMalformedOrOversizedPlaylistsFailWithoutLeakingInput() {
        reason(IptvException.Reason.InvalidPlaylist) { IptvParsers.playlist(bytes("<html>Login</html>"), source()) }
        reason(IptvException.Reason.NoChannels) { IptvParsers.playlist(bytes("#EXTM3U\n#EXTINF:-1 tvg-name=\"broken\nfile:///bad"), source()) }
        reason(IptvException.Reason.TooLarge) { IptvParsers.playlist(ByteArray(IptvParsers.MAX_BYTES + 1), source()) }
        assertFalse(source().toString().contains("provider.example"))
    }
    @Test fun xtreamAuthenticationDistinguishesInvalidInactiveAndExpiredAccounts() {
        IptvParsers.authentication(bytes("""{"user_info":{"auth":1,"status":"Active","exp_date":"2000"}}"""), 1000)
        IptvParsers.authentication(bytes("""{"user_info":{"auth":true,"status":"Active","exp_date":null}}"""), 1000)
        reason(IptvException.Reason.Credentials) { IptvParsers.authentication(bytes("""{"user_info":{"auth":0}}""")) }
        reason(IptvException.Reason.Inactive) { IptvParsers.authentication(bytes("""{"user_info":{"auth":1,"status":"Expired"}}""")) }
        reason(IptvException.Reason.Inactive) { IptvParsers.authentication(bytes("""{"user_info":{"auth":1,"status":"Active","exp_date":"999"}}"""), 1000) }
    }
    @Test fun xtreamEscapesCredentialsAndRejectsServerQueryOrDotSegments() {
        val s = IptvSource("x", "Xtream", IptvKind.Xtream, "https://provider.example/base", "a+b/?", "p+&#/").validated()
        val api = IptvClient.apiUrl(s).toHttpUrl()
        assertEquals("a+b/?", api.queryParameter("username")); assertEquals("p+&#/", api.queryParameter("password"))
        assertTrue(api.encodedQuery!!.contains("%2B"))
        val live = IptvClient.liveUrl(s, "123").toHttpUrl()
        assertEquals(listOf("base", "live", "a+b/?", "p+&#/", "123.m3u8"), live.pathSegments)
        reason(IptvException.Reason.InvalidResponse) { IptvClient.liveUrl(s, "../123") }
        reason(IptvException.Reason.InvalidServer) { s.copy(address = "https://provider.example/player_api.php").validated() }
        reason(IptvException.Reason.InvalidServer) { s.copy(address = "https://provider.example/?token=x").validated() }
        reason(IptvException.Reason.Credentials) { s.copy(password = "..").validated() }
    }
    @Test fun xtreamClientLoadsCategoriesAndChannelsWithDuplicateAndBadIdsFiltered() = runBlocking {
        val requested = mutableListOf<String>()
        val client = IptvClient(IptvTransport { url, _ ->
            requested += url
            when (url.toHttpUrl().queryParameter("action")) {
                "get_live_categories" -> bytes("""[{"category_id":"1","category_name":"أخبار"}]""")
                "get_live_streams" -> bytes("""[{"stream_id":7,"name":"News","category_id":"1"},{"stream_id":7,"name":"Duplicate"},{"stream_id":"../9","name":"Bad"}]""")
                else -> bytes("""{"user_info":{"auth":"1","status":"Active"}}""")
            }
        })
        val s = IptvSource("x", "Xtream", IptvKind.Xtream, "https://provider.example", "user", "fixture")
        val channel = client.load(s).channels.single()
        assertEquals("أخبار", channel.group); assertTrue(channel.url.endsWith("/live/user/fixture/7.m3u8"))
        assertEquals(3, requested.size); assertFalse(channel.toString().contains("fixture"))
    }
    @Test fun sourceStorageRoundTripsCredentialsAndRejectsCorruptionOrUnknownSchema() {
        val a = source(true).copy(address = "http://provider.example/list?token=fixture")
        val b = IptvSource("b", "Xtream", IptvKind.Xtream, "https://provider.example/base", "u+name", "p&word", output = IptvOutput.TransportStream)
        assertEquals(listOf(a.validated(), b.validated()), IptvSourceCodec.decode(IptvSourceCodec.encode(listOf(a, b))))
        reason(IptvException.Reason.Storage) { IptvSourceCodec.decode(bytes("{}")) }
        reason(IptvException.Reason.Storage) { IptvSourceCodec.decode(bytes("""{"schema":2,"sources":[]}""")) }
        reason(IptvException.Reason.Storage) { IptvSourceCodec.encode(listOf(a, a)) }
        reason(IptvException.Reason.SourceLimit) { IptvSourceCodec.encode((0..20).map { a.copy(id = "$it") }) }
    }
    @Test fun providerRejectionsDoNotExposeCredentialBearingUrls() = runBlocking {
        val client = IptvClient(IptvTransport { _, _ -> throw IptvException(IptvException.Reason.Http, 403) })
        try { client.load(source()); fail() } catch (e: IptvException) {
            assertEquals(403, e.status); assertFalse(iptvMessage(e).contains("provider.example"))
        }
    }
}
