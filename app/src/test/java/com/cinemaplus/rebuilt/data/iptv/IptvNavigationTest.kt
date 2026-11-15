package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvNavigationTest {
    private fun channel(id: String, group: String = "News", source: String = "one") = IptvChannel(id, source, id, group, "https://media.example/live.m3u8")
    @Test fun queueKeepsProviderOrderWithinTheCurrentSourceAndCategory() {
        val all = listOf(channel("A"), channel("B", "Sport"), channel("Foreign", source = "two"), channel("C"))
        assertEquals(listOf("A", "C"), IptvNavigation.channels(all, "one", "News").map { it.id })
        assertEquals(listOf("B"), IptvNavigation.channels(all, "one", null, "sport").map { it.id })
    }
    @Test fun remoteSurfingWrapsAndHandlesDeletedOrSingleOrEmptyQueues() {
        val values = listOf(channel("A"), channel("B"), channel("C"))
        assertEquals("C", IptvNavigation.neighbor(values, "A", -1)?.id)
        assertEquals("A", IptvNavigation.neighbor(values, "C", 1)?.id)
        assertEquals("A", IptvNavigation.neighbor(values, "missing", 1)?.id)
        assertEquals("A", IptvNavigation.neighbor(values.take(1), "A", 1)?.id)
        assertNull(IptvNavigation.neighbor(emptyList(), "A", 1))
    }
    @Test fun catalogNumbersStayStableAfterFiltersAndIgnoreOtherSourceOrDuplicateIds() {
        val all = listOf(channel("A"), channel("Foreign", source = "two"), channel("B", "Sport"), channel("A"), channel("C"))
        val numbers = IptvNavigation.numbers(all, "one")
        assertEquals(mapOf("A" to 1, "B" to 2, "C" to 3), numbers)
        val searched = IptvNavigation.channels(all, "one", "Sport")
        assertEquals(2, numbers[searched.single().id])
        assertEquals(listOf("News", "Sport"), IptvNavigation.categories(all, "one").keys.toList())
        assertEquals(mapOf("News" to 3, "Sport" to 1), IptvNavigation.categories(all, "one"))
        assertEquals(listOf("A", "Foreign", "B", "A", "C"), all.map { it.id })
    }
    @Test fun guideEntryRestoresPlayingChannelOrFirstFilteredResult() {
        val values = listOf(channel("A"), channel("B"))
        assertEquals("B", IptvNavigation.entry(values, "B")); assertEquals("A", IptvNavigation.entry(values, "other"))
        assertNull(IptvNavigation.entry(emptyList(), "B"))
    }
}
