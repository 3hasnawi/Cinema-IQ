package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvCategoryPolicyTest {
    private fun channel(id: String, source: String = "subscription-one", group: String = "News") =
        IptvChannel(id, source, id, group, "https://media.example/live.m3u8")

    @Test fun categoryKeysSeparateSubscriptionsAndPreserveGroupCase() {
        val first = channel("one")
        val foreign = channel("two", source = "subscription-two")
        assertEquals(IptvCategoryPolicy.key(first.sourceId, first.group), IptvCategoryPolicy.key(first))
        assertNotEquals(IptvCategoryPolicy.key(first), IptvCategoryPolicy.key(foreign))
        assertNotEquals(IptvCategoryPolicy.key("subscription-one", "News"),
            IptvCategoryPolicy.key("subscription-one", "news"))

        val policy = IptvCategoryPolicy().hide(IptvCategoryPolicy.key(first))
        assertEquals(listOf(foreign), policy.available(listOf(first, foreign), emptySet()))
    }

    @Test fun categoryKeysNormalizeCanonicalUnicodeAndWhitespaceWithoutChangingChannels() {
        val original = channel("one", group = "  Cafe\u0301\u00a0\t Sports  ")
        val key = IptvCategoryPolicy.key(original)
        assertEquals(IptvCategoryPolicy.key(original.sourceId, "Caf\u00e9 Sports"), key)
        assertEquals("  Cafe\u0301\u00a0\t Sports  ", original.group)
        assertEquals("one", original.id)
    }

    @Test fun aHiddenCategoryCannotBeOpenedWithAnOldUnlockGrant() {
        val value = channel("one")
        val key = IptvCategoryPolicy.key(value)
        val policy = IptvCategoryPolicy().lock(key).hide(key)
        assertEquals(IptvCategoryGate.Hidden, policy.gate(value, setOf(key)))
        assertTrue(policy.available(listOf(value), setOf(key)).isEmpty())
    }

    @Test fun unlockingOnlyAuthorizesItsOwnCategoryForTheCurrentProjection() {
        val news = channel("one")
        val sport = channel("two", group = "Sports")
        val policy = IptvCategoryPolicy().lock(IptvCategoryPolicy.key(news)).lock(IptvCategoryPolicy.key(sport))
        val unlocked = setOf(IptvCategoryPolicy.key(news))
        assertEquals(IptvCategoryGate.Allowed, policy.gate(news, unlocked))
        assertEquals(IptvCategoryGate.PinRequired, policy.gate(sport, unlocked))
        assertEquals(listOf(news), policy.available(listOf(news, sport), unlocked))
        assertEquals(IptvCategoryGate.PinRequired, policy.gate(news))
        assertTrue(policy.available(listOf(news, sport), emptySet()).isEmpty())
    }

    @Test fun restoringVisibilityDoesNotRemoveALockAndRemovingALockDoesNotUnhide() {
        val value = channel("one")
        val key = IptvCategoryPolicy.key(value)
        val original = IptvCategoryPolicy().hide(key).lock(key)
        val restored = original.restore(key)
        assertEquals(IptvCategoryGate.PinRequired, restored.gate(value))
        assertEquals(IptvCategoryGate.Hidden, original.removeLock(key).gate(value))
        assertEquals(setOf(key), original.hidden)
        assertEquals(setOf(key), original.locked)
        assertEquals(IptvCategoryGate.Allowed, restored.removeLock(key).gate(value))
    }

    @Test fun applyingPolicyBeforeCollectionPreventsSearchFavoritesAndRecentBypasses() {
        val visible = channel("open")
        val locked = channel("locked", group = "Sports")
        val hidden = channel("hidden", group = "Films")
        val foreign = channel("foreign", source = "subscription-two", group = "Sports")
        val catalog = listOf(visible, locked, hidden, foreign)
        val collection = IptvChannelCollection(catalog.map { it.id }.toSet(), catalog.reversed().map { it.id })
        val policy = IptvCategoryPolicy().lock(IptvCategoryPolicy.key(locked)).hide(IptvCategoryPolicy.key(hidden))
        val allowed = policy.available(catalog, emptySet())

        for (section in listOf(IptvChannelSection.All, IptvChannelSection.Favorites, IptvChannelSection.Recent)) {
            assertEquals(listOf(visible), collection.channels(allowed, visible.sourceId, section))
            assertTrue(collection.channels(allowed, visible.sourceId, section, "locked").isEmpty())
            assertTrue(collection.channels(allowed, visible.sourceId, section, "Films").isEmpty())
        }
        assertTrue(collection.channels(allowed, visible.sourceId, IptvChannelSection.Group("Sports")).isEmpty())
        assertEquals(listOf(foreign), collection.channels(allowed, foreign.sourceId, IptvChannelSection.All))
    }

    @Test fun filteringAndRestoringKeepRawCatalogHistoryFavoritesAndProviderOrder() {
        val first = channel("one", group = "Sports")
        val second = channel("two")
        val third = channel("three", group = "Sports")
        val catalog = mutableListOf(first, second, third)
        val snapshot = catalog.toList()
        val collection = IptvChannelCollection(setOf(first.id, third.id), listOf(third.id, first.id))
        val key = IptvCategoryPolicy.key(first)
        val hidden = IptvCategoryPolicy().hide(key)

        assertEquals(listOf(second), hidden.available(catalog, emptySet()))
        assertTrue(collection.channels(hidden.available(catalog, emptySet()), first.sourceId, IptvChannelSection.Favorites).isEmpty())
        val restored = hidden.restore(key).available(catalog, emptySet())
        assertEquals(snapshot, restored)
        assertEquals(snapshot, catalog)
        assertEquals(setOf(first.id, third.id), collection.favorites)
        assertEquals(listOf(third.id, first.id), collection.recent)
        assertEquals(listOf(first, third), collection.channels(restored, first.sourceId, IptvChannelSection.Favorites))
        assertEquals(listOf(third, first), collection.channels(restored, first.sourceId, IptvChannelSection.Recent))
    }

    @Test fun channelSurfingSkipsUnavailableCategoriesAndStopsForAnEmptyProjection() {
        val locked = channel("one", group = "Sports")
        val open = channel("two")
        val hidden = channel("three", group = "Films")
        val policy = IptvCategoryPolicy().lock(IptvCategoryPolicy.key(locked)).hide(IptvCategoryPolicy.key(hidden))
        val allowed = policy.available(listOf(locked, open, hidden), emptySet())
        assertEquals(open, IptvNavigation.neighbor(allowed, locked.id, 1))
        assertEquals(open, IptvNavigation.neighbor(allowed, open.id, -1))
        assertNull(IptvNavigation.neighbor(policy.hide(IptvCategoryPolicy.key(open)).available(allowed, emptySet()), open.id, 1))
    }

    @Test fun policyCodecRoundTripPreservesIndependentRulesAndUsesDeterministicOrdering() {
        val news = IptvCategoryPolicy.key("subscription-one", "News")
        val sports = IptvCategoryPolicy.key("subscription-one", "Sports")
        val foreign = IptvCategoryPolicy.key("subscription-two", "News")
        val original = IptvCategoryPolicy(linkedSetOf(news, sports), linkedSetOf(sports, foreign))
        val encoded = IptvCategoryPolicyCodec.encode(original)

        assertEquals(original, IptvCategoryPolicyCodec.decode(encoded))
        assertArrayEquals(encoded, IptvCategoryPolicyCodec.encode(
            IptvCategoryPolicy(linkedSetOf(sports, news), linkedSetOf(foreign, sports))))
        assertEquals(IptvCategoryPolicy(), IptvCategoryPolicyCodec.decode(IptvCategoryPolicyCodec.encode(IptvCategoryPolicy())))
    }

    @Test fun startupSkipsHiddenLockedAndForeignRecentChannelsWithoutMutatingCollections() {
        val hidden = channel("hidden", group = "Films")
        val locked = channel("locked", group = "Sports")
        val open = channel("open")
        val foreign = channel("foreign", source = "subscription-two")
        val catalog = listOf(hidden, locked, open, foreign)
        val collection = IptvChannelCollection(catalog.map { it.id }.toSet(), listOf(hidden.id, locked.id, foreign.id))
        val snapshot = collection.copy()
        val policy = IptvCategoryPolicy().hide(IptvCategoryPolicy.key(hidden)).lock(IptvCategoryPolicy.key(locked))

        assertEquals(open, IptvTvStartup.channel(policy.available(catalog, emptySet()), open.sourceId, collection.recent))
        assertEquals(locked, IptvTvStartup.channel(policy.available(catalog, setOf(IptvCategoryPolicy.key(locked))), open.sourceId, collection.recent))
        assertNull(IptvTvStartup.channel(policy.hide(IptvCategoryPolicy.key(open)).available(catalog, emptySet()), open.sourceId, collection.recent))
        assertEquals(snapshot, collection)
        assertEquals(listOf(hidden, locked, open, foreign), catalog)
    }

    @Test fun policyCodecRejectsTruncationUnknownVersionAndTrailingBytes() {
        val policy = IptvCategoryPolicy().hide(IptvCategoryPolicy.key("subscription-one", "News"))
        val encoded = IptvCategoryPolicyCodec.encode(policy)
        for (length in listOf(0, 3, 4, 8, encoded.size - 1)) {
            assertMalformedPolicy(encoded.copyOf(length))
        }
        assertMalformedPolicy(encoded.copyOf().also { it[3] = 2 })
        assertMalformedPolicy(encoded + byteArrayOf(0))
    }

    private fun assertMalformedPolicy(bytes: ByteArray) {
        try {
            IptvCategoryPolicyCodec.decode(bytes)
            fail("Malformed policy must not silently remove existing locks")
        } catch (_: Exception) {
            // The Android store maps decode failures to the existing storage error.
        }
    }
}
