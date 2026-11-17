package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvTvStartupTest {
    private fun channel(id: String, source: String = "one", url: String = "https://example.test/$id.m3u8") =
        IptvChannel(id, source, id, "News", url)

    @Test fun resumesLastWatchedRatherThanFirstProviderChannel() {
        val values = listOf(channel("A"), channel("B"), channel("C"))
        assertEquals(values[2], IptvTvStartup.channel(values, "one", listOf("C", "B")))
    }
    @Test fun skipsDeletedChannelsAndHistoryFromOtherSources() {
        val values = listOf(channel("A"), channel("foreign", "two"), channel("B"))
        assertEquals("B", IptvTvStartup.channel(values, "one", listOf("deleted", "foreign", "B", "A"))?.id)
        assertNull(IptvTvStartup.channel(values, "missing", listOf("foreign", "B")))
    }
    @Test fun usesFreshCatalogUrlForStableSavedId() {
        val refreshed = channel("A", url = "https://example.test/live/current.m3u8")
        assertSame(refreshed, IptvTvStartup.channel(listOf(refreshed), "one", listOf("A")))
    }
    @Test fun firstUseOrRemovedHistoryFallsBackToFirstChannelInSelectedSource() {
        val values = listOf(channel("foreign", "two"), channel("B"), channel("A"))
        assertEquals("B", IptvTvStartup.channel(values, "one", emptyList())?.id)
        assertEquals("B", IptvTvStartup.channel(values, "one", listOf("removed"))?.id)
        assertNull(IptvTvStartup.channel(emptyList(), "one", listOf("A")))
    }
}
