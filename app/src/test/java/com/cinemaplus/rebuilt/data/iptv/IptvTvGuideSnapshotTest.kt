package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvTvGuideSnapshotTest {
    private val source = "subscription-one"
    private fun channel(id: String, name: String = id, group: String = "News", sourceId: String = source) =
        IptvChannel(id, sourceId, name, group, "https://media.example/live.m3u8")

    private fun catalog(channels: List<IptvChannel>, hidden: Set<String> = emptySet()) =
        IptvTvGuideCatalog.build(channels, source, IptvNavigation.numbers(channels, source), hidden)

    private fun ids(page: IptvTvGuidePage) = page.rows.map { it.id }

    @Test fun onlyAuthorizedSourceRowsCanReachAnySectionOrSearch() {
        val open = channel("open")
        val hidden = channel("hidden", name = "Hidden Film", group = "Hidden")
        val foreign = channel("foreign", name = "Foreign Sport", sourceId = "other-subscription")
        val values = listOf(open, hidden, foreign)
        val snapshot = catalog(values, setOf("Hidden")).snapshot(IptvChannelCollection(
            setOf("foreign", "hidden", "missing-protected", "open"), listOf("missing-protected", "hidden", "foreign", "open")))

        for (section in listOf(IptvChannelSection.All, IptvChannelSection.Favorites, IptvChannelSection.Recent)) {
            assertEquals(listOf("open"), ids(snapshot.page(section)))
            assertTrue(snapshot.search(section, "Hidden").rows.isEmpty())
            assertTrue(snapshot.search(section, "Foreign").rows.isEmpty())
        }
        assertTrue(snapshot.page(IptvChannelSection.Group("Hidden")).rows.isEmpty())
        assertFalse(snapshot.page(IptvChannelSection.All).indexById.containsKey("foreign"))
    }

    @Test fun favoritesUseProviderOrderAndRecentUsesItsOwnRecencyOrder() {
        val values = listOf(channel("A"), channel("foreign", sourceId = "other-subscription"),
            channel("B", group = "Sports"), channel("C"), channel("D", group = "Sports"))
        val collection = IptvChannelCollection(setOf("D", "foreign", "missing", "B"), listOf("C", "A", "missing", "D", "C", "foreign"))
        val snapshot = catalog(values).snapshot(collection)

        assertEquals(listOf("A", "B", "C", "D"), ids(snapshot.page(IptvChannelSection.All)))
        assertEquals(listOf("A", "C"), ids(snapshot.page(IptvChannelSection.Group("News"))))
        assertEquals(listOf("B", "D"), ids(snapshot.page(IptvChannelSection.Favorites)))
        assertEquals(listOf("C", "A", "D"), ids(snapshot.page(IptvChannelSection.Recent)))
        assertEquals(mapOf("C" to 0, "A" to 1, "D" to 2), snapshot.page(IptvChannelSection.Recent).indexById)
        assertEquals(listOf("C", "A", "missing", "D", "C", "foreign"), collection.recent)
    }

    @Test fun searchPreservesArabicCompatibilityNumbersAndNumericNameFallback() {
        val news = channel("news", "الأَخْبَار الأولى HD", "قنوات إخبارية")
        val sport = channel("sport", "Sport 24", "رياضة")
        val numbered = channel("numbered", "Public", "General")
        val film = channel("film", "أَبو ظبي", "اﻷفلام")
        val english = channel("english", "Late Night", "  ENGLISH   HD ")
        val snapshot = IptvTvGuideCatalog.build(listOf(news, sport, numbered, film, english), source,
            mapOf("news" to 17, "sport" to 5, "numbered" to 24, "film" to 9, "english" to 1))
            .snapshot(IptvChannelCollection(setOf("news", "numbered"), listOf("numbered", "sport")))

        for (query in listOf("الاخبار", "اﻷخبار", "الاولي", "١٧", "۱۷")) {
            assertEquals(listOf("news"), ids(snapshot.search(IptvChannelSection.All, query)))
        }
        assertEquals(listOf("film"), ids(snapshot.search(IptvChannelSection.All, "الافلام")))
        assertEquals(listOf("english"), ids(snapshot.search(IptvChannelSection.All, " english hd ")))
        assertEquals(listOf("sport", "numbered"), ids(snapshot.search(IptvChannelSection.All, "٢٤")))
        assertEquals(listOf("numbered"), ids(snapshot.search(IptvChannelSection.All, "024")))
        assertEquals(listOf("sport"), ids(snapshot.search(IptvChannelSection.Group("رياضة"), "24")))
        assertEquals(listOf("numbered"), ids(snapshot.search(IptvChannelSection.Favorites, "24")))
        assertEquals(listOf("numbered", "sport"), ids(snapshot.search(IptvChannelSection.Recent, "24")))
    }

    @Test fun canonicalNumbersDoNotChangeWithGroupOrCollectionFilters() {
        val values = listOf(channel("A", "Alpha"), channel("B", "Beta", "Sports"), channel("C", "Gamma"))
        val snapshot = IptvTvGuideCatalog.build(values, source, mapOf("A" to 11, "B" to 37, "C" to 84))
            .snapshot(IptvChannelCollection(setOf("C", "B"), listOf("C", "B")))
        assertEquals(listOf("B"), ids(snapshot.search(IptvChannelSection.Group("Sports"), "٣٧")))
        assertEquals(listOf("C"), ids(snapshot.search(IptvChannelSection.Favorites, "84")))
        assertEquals(listOf("B"), ids(snapshot.search(IptvChannelSection.Recent, "37")))
        assertTrue(snapshot.search(IptvChannelSection.Group("Sports"), "1").rows.isEmpty())
    }

    @Test fun blankSearchAndWarmPageRetrievalReuseExactPagesAcrossCollectionChanges() {
        val value = catalog(listOf(channel("A"), channel("B", group = "Sports")))
        val first = value.snapshot(IptvChannelCollection(setOf("A"), listOf("A")))
        val next = value.snapshot(IptvChannelCollection(setOf("B"), listOf("B")))
        val sections = listOf(IptvChannelSection.All, IptvChannelSection.Group("News"),
            IptvChannelSection.Favorites, IptvChannelSection.Recent, IptvChannelSection.Group("Missing"))
        for (section in sections) {
            for (query in listOf("", " \t\n ", "َ")) assertSame(first.page(section), first.search(section, query))
            assertSame(first.page(section), first.page(section))
        }
        assertSame(first.page(IptvChannelSection.All), next.page(IptvChannelSection.All))
        assertSame(first.page(IptvChannelSection.Group("News")), next.page(IptvChannelSection.Group("News")))
        assertEquals(listOf("A"), ids(first.page(IptvChannelSection.Favorites)))
        assertEquals(listOf("B"), ids(next.page(IptvChannelSection.Favorites)))
        assertEquals(listOf("A"), ids(first.page(IptvChannelSection.Recent)))
        assertEquals(listOf("B"), ids(next.page(IptvChannelSection.Recent)))
    }

    @Test fun snapshotsDoNotRetainMutableInputCollectionsOrNumberMaps() {
        val values = mutableListOf(channel("A", "Alpha"), channel("B", "Beta"))
        val numbers = linkedMapOf("A" to 7, "B" to 19)
        val hidden = mutableSetOf("Unused")
        val favorites = mutableSetOf("A")
        val recent = mutableListOf("B", "A")
        val catalog = IptvTvGuideCatalog.build(values, source, numbers, hidden)
        val snapshot = catalog.snapshot(IptvChannelCollection(favorites, recent))

        values.clear(); numbers["A"] = 900; hidden.add("News")
        favorites.clear(); favorites.add("B"); recent.clear(); recent.add("A")
        assertEquals(listOf("A", "B"), ids(snapshot.page(IptvChannelSection.All)))
        assertEquals(listOf("A", "B"), ids(snapshot.page(IptvChannelSection.Group("News"))))
        assertEquals(listOf("A"), ids(snapshot.page(IptvChannelSection.Favorites)))
        assertEquals(listOf("B", "A"), ids(snapshot.page(IptvChannelSection.Recent)))
        assertEquals(listOf("A"), ids(snapshot.search(IptvChannelSection.All, "7")))
        assertTrue(snapshot.search(IptvChannelSection.All, "900").rows.isEmpty())
        try {
            (snapshot.page(IptvChannelSection.All).rows as MutableList<IptvChannel>).clear()
            fail("Guide rows must be immutable")
        } catch (_: UnsupportedOperationException) { }
        try {
            (snapshot.page(IptvChannelSection.All).indexById as MutableMap<String, Int>)["other"] = 9
            fail("Guide indexes must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun collectionOnlyUpdatesReuseUnchangedFavoriteAndRecentPagesIndependently() {
        val value = catalog(listOf(channel("A"), channel("B")))
        val first = value.snapshot(IptvChannelCollection(setOf("A"), listOf("A")))
        val recentChanged = value.snapshot(IptvChannelCollection(setOf("A"), listOf("B", "A")), first)
        assertSame(first.page(IptvChannelSection.Favorites), recentChanged.page(IptvChannelSection.Favorites))
        assertNotSame(first.page(IptvChannelSection.Recent), recentChanged.page(IptvChannelSection.Recent))
        assertEquals(listOf("B", "A"), ids(recentChanged.page(IptvChannelSection.Recent)))

        val favoriteChanged = value.snapshot(IptvChannelCollection(setOf("B"), listOf("B", "A")), recentChanged)
        assertSame(recentChanged.page(IptvChannelSection.Recent), favoriteChanged.page(IptvChannelSection.Recent))
        assertNotSame(recentChanged.page(IptvChannelSection.Favorites), favoriteChanged.page(IptvChannelSection.Favorites))
        assertEquals(listOf("B"), ids(favoriteChanged.page(IptvChannelSection.Favorites)))
        assertSame(favoriteChanged.page(IptvChannelSection.Favorites),
            favoriteChanged.search(IptvChannelSection.Favorites, " "))
    }

    @Test fun previousSnapshotKeysAreCopiedBeforeMutableCollectionsChange() {
        val value = catalog(listOf(channel("A"), channel("B")))
        val favoriteKeys = mutableSetOf("A")
        val recentKeys = mutableListOf("A")
        val collection = IptvChannelCollection(favoriteKeys, recentKeys)
        val first = value.snapshot(collection)
        favoriteKeys.clear(); favoriteKeys.add("B")
        val next = value.snapshot(collection, first)
        assertEquals(listOf("A"), ids(first.page(IptvChannelSection.Favorites)))
        assertEquals(listOf("B"), ids(next.page(IptvChannelSection.Favorites)))
        assertSame(first.page(IptvChannelSection.Recent), next.page(IptvChannelSection.Recent))
        recentKeys.clear(); recentKeys.add("B")
        val last = value.snapshot(collection, next)
        assertEquals(listOf("A"), ids(first.page(IptvChannelSection.Recent)))
        assertEquals(listOf("B"), ids(last.page(IptvChannelSection.Recent)))
        assertSame(next.page(IptvChannelSection.Favorites), last.page(IptvChannelSection.Favorites))
    }

    @Test fun previousPagesCannotCrossCatalogueIdentityOrReintroduceRevokedAuthorization() {
        val open = channel("open")
        val protected = channel("protected", "Protected", "Protected")
        val values = listOf(open, protected)
        val collection = IptvChannelCollection(setOf("open", "protected"), listOf("protected", "open"))
        val admitted = catalog(values).snapshot(collection)
        val equivalentNewCatalog = catalog(values).snapshot(collection, admitted)
        assertNotSame(admitted.page(IptvChannelSection.Favorites), equivalentNewCatalog.page(IptvChannelSection.Favorites))
        assertNotSame(admitted.page(IptvChannelSection.Recent), equivalentNewCatalog.page(IptvChannelSection.Recent))
        assertEquals(listOf("protected", "open"), ids(equivalentNewCatalog.page(IptvChannelSection.Recent)))

        val revoked = catalog(listOf(open)).snapshot(collection, admitted)
        assertNotSame(admitted.page(IptvChannelSection.Favorites), revoked.page(IptvChannelSection.Favorites))
        assertNotSame(admitted.page(IptvChannelSection.Recent), revoked.page(IptvChannelSection.Recent))
        assertEquals(listOf("open"), ids(revoked.page(IptvChannelSection.Favorites)))
        assertEquals(listOf("open"), ids(revoked.page(IptvChannelSection.Recent)))
        assertTrue(revoked.search(IptvChannelSection.Favorites, "Protected").rows.isEmpty())
        assertTrue(revoked.search(IptvChannelSection.Recent, "Protected").rows.isEmpty())
    }

    @Test fun revokedAuthorizedProjectionCannotBeRecreatedFromFavoritesRecentOrCanonicalNumbers() {
        val open = channel("open")
        val protected = channel("protected", "Protected", "Protected")
        val numbers = mapOf("open" to 1, "protected" to 2)
        val collection = IptvChannelCollection(setOf("protected"), listOf("protected"))
        val admitted = IptvTvGuideCatalog.build(listOf(open, protected), source, numbers).snapshot(collection)
        val revoked = IptvTvGuideCatalog.build(listOf(open), source, numbers).snapshot(collection)
        assertEquals(listOf("protected"), ids(admitted.page(IptvChannelSection.Group("Protected"))))
        assertEquals(listOf("open"), ids(revoked.page(IptvChannelSection.All)))
        for (section in listOf(IptvChannelSection.Favorites, IptvChannelSection.Recent, IptvChannelSection.Group("Protected"))) {
            assertTrue(revoked.page(section).rows.isEmpty())
        }
        assertTrue(revoked.search(IptvChannelSection.All, "Protected").rows.isEmpty())
        assertTrue(revoked.search(IptvChannelSection.All, "2").rows.isEmpty())
    }

    @Test fun duplicateIdsUseFirstAuthorizedSourceRowWithoutForeignOrLaterMetadata() {
        val foreign = channel("same", "Foreign", sourceId = "other-subscription")
        val first = channel("same", "First", "News")
        val duplicate = channel("same", "Later", "Sports")
        val snapshot = catalog(listOf(foreign, first, duplicate, channel("last"))).snapshot(IptvChannelCollection())
        assertEquals(listOf("same", "last"), ids(snapshot.page(IptvChannelSection.All)))
        assertSame(first, snapshot.page(IptvChannelSection.All).rows.first())
        assertTrue(snapshot.page(IptvChannelSection.Group("Sports")).rows.isEmpty())
        assertTrue(snapshot.search(IptvChannelSection.All, "Foreign").rows.isEmpty())
        assertTrue(snapshot.search(IptvChannelSection.All, "Later").rows.isEmpty())
    }

    @Test fun largeCatalogueHasDirectFirstMiddleLastIndexesAndReusablePages() {
        val count = 11_962
        val values = List(count) { index -> channel("id-$index", "Station $index", "Group ${index % 17}") }
        val value = catalog(values)
        val snapshot = value.snapshot(IptvChannelCollection(setOf("id-0", "id-${count-1}"), listOf("id-${count-1}", "id-0")))
        val all = snapshot.page(IptvChannelSection.All)
        assertEquals(count, all.rows.size)
        for (index in listOf(0, count/2, count-1)) {
            assertEquals(index, all.indexById["id-$index"])
            val group = snapshot.page(IptvChannelSection.Group("Group ${index % 17}"))
            assertEquals(index/17, group.indexById["id-$index"])
            assertSame(values[index], group.rows[index/17])
        }
        assertEquals(listOf("id-${count-1}"), ids(snapshot.search(IptvChannelSection.All, count.toString())))
        repeat(100) {
            assertSame(all, snapshot.page(IptvChannelSection.All))
            assertSame(all, snapshot.search(IptvChannelSection.All, " "))
            assertSame(snapshot.page(IptvChannelSection.Group("Group 8")), snapshot.page(IptvChannelSection.Group("Group 8")))
        }
        assertSame(all, value.snapshot(IptvChannelCollection()).page(IptvChannelSection.All))
    }
}
