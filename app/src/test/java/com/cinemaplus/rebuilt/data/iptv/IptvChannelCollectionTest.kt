package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvChannelCollectionTest {
    private fun channel(id: String, source: String = "one", group: String = "News") = IptvChannel(id, source, id, group, "https://media.example/live.m3u8?token=private")
    @Test fun recentDeduplicatesAndFiltersWithinSourceWithoutDeletingMissingIds() {
        val library = listOf("A", "B", "Foreign", "A", "removed").fold(IptvChannelCollection()) { value, id -> value.watched(id) }
        val catalog = listOf(channel("A"), channel("B"), channel("Foreign", source = "two"))
        assertEquals(listOf("removed", "A", "Foreign", "B"), library.recent)
        assertEquals(listOf("A", "B"), library.channels(catalog, "one", IptvChannelSection.Recent).map { it.id })
        assertEquals(listOf("Foreign"), library.channels(catalog, "two", IptvChannelSection.Recent).map { it.id })
        assertEquals("removed", library.recent.first())
    }
    @Test fun favoritesKeepProviderOrderAndSupportSearchAndToggle() {
        val library = IptvChannelCollection().toggle("B").toggle("A")
        val catalog = listOf(channel("A"), channel("B", group = "Sport"), channel("C"))
        assertEquals(listOf("A", "B"), library.channels(catalog, "one", IptvChannelSection.Favorites).map { it.id })
        assertEquals(listOf("B"), library.channels(catalog, "one", IptvChannelSection.Favorites, "sport").map { it.id })
        assertEquals(listOf("B"), library.toggle("A").channels(catalog, "one", IptvChannelSection.Favorites).map { it.id })
        assertEquals(listOf("A", "C"), library.channels(catalog, "one", IptvChannelSection.Group("News")).map { it.id })
    }
    @Test fun recordsAreBoundedAndImmutable() {
        val original = IptvChannelCollection()
        val library = (0 until 2100).fold(original) { value, id -> value.watched("$id").toggle("$id") }
        assertEquals(100, library.recent.size); assertEquals("2099", library.recent.first())
        assertEquals(2000, library.favorites.size); assertTrue(original.recent.isEmpty())
        assertEquals(library, library.watched("").toggle("X".repeat(513)))
    }
    @Test fun tvFavoriteSurfingStaysInSourceAndHandlesRemovingThePlayingChannel() {
        val catalog = listOf(channel("A"), channel("B"), channel("C"), channel("Foreign", source = "two"))
        val favorites = IptvChannelCollection().toggle("A").toggle("C").toggle("Foreign")
        fun queue(collection: IptvChannelCollection) = collection.channels(catalog, "one", IptvChannelSection.Favorites)
        assertEquals("C", IptvNavigation.neighbor(queue(favorites), "A", 1)?.id)
        assertEquals("A", IptvNavigation.neighbor(queue(favorites), "C", 1)?.id)
        val removedCurrent = favorites.toggle("A")
        assertEquals("C", IptvNavigation.neighbor(queue(removedCurrent), "A", 1)?.id)
        assertNull(IptvNavigation.neighbor(queue(removedCurrent.toggle("C")), "C", 1))
    }
}
