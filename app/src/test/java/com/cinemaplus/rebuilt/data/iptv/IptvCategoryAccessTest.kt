package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvCategoryAccessTest {
    private val first = channel("a1", "A")
    private val sibling = channel("a2", "A")
    private val other = channel("b1", "B")
    private val open = channel("open", "Open")
    private val firstKey = IptvCategoryPolicy.key(first)
    private val otherKey = IptvCategoryPolicy.key(other)
    private val policy = IptvCategoryPolicy().lock(firstKey).lock(otherKey)

    private fun channel(id: String, group: String, source: String = "source-one") =
        IptvChannel(id, source, id, group, "https://unit.invalid/stream/$id.m3u8")

    private fun playingFirst(): IptvCategoryAccess =
        IptvCategoryAccess().authorizeVisit(firstKey).start(first, policy)

    @Test fun browseAuthorizationAloneCannotPretendADecoderAlreadyStarted() {
        val visit = IptvCategoryAccess().authorizeVisit(firstKey)
        assertTrue(visit.canStart(first, policy))
        assertTrue(visit.canStart(sibling, policy))
        assertFalse(visit.canContinue(first, policy))
        assertFalse(visit.canContinue(sibling, policy))

        val playing = visit.start(first, policy)
        assertTrue(playing.canContinue(first, policy))
        assertFalse(playing.canContinue(sibling, policy))
    }

    @Test fun leavingCategoryExpiresNewStartsButKeepsOnlyTheCurrentDecoder() {
        val access = playingFirst().leaveVisit()

        assertTrue(access.unlockedCategories.isEmpty())
        assertFalse(access.canStart(first, policy))
        assertFalse(access.canStart(sibling, policy))
        assertTrue(access.canContinue(first, policy))
        assertFalse(access.canContinue(sibling, policy))
        assertFalse(access.canContinue(other, policy))
    }

    @Test fun continuationDoesNotLeakIntoSearchFavoritesRecentOrChannelSurfing() {
        val access = playingFirst().leaveVisit()
        val catalog = listOf(first, sibling, other, open)
        val collection = IptvChannelCollection(catalog.map { it.id }.toSet(), catalog.reversed().map { it.id })
        val browse = policy.available(catalog, access.unlockedCategories)

        assertEquals(listOf(open), browse)
        for (section in listOf(IptvChannelSection.All, IptvChannelSection.Favorites, IptvChannelSection.Recent)) {
            assertEquals(listOf(open), collection.channels(browse, first.sourceId, section))
            assertTrue(collection.channels(browse, first.sourceId, section, first.name).isEmpty())
        }
        assertTrue(collection.channels(browse, first.sourceId, IptvChannelSection.Group(first.group)).isEmpty())
        assertEquals(open, IptvNavigation.neighbor(browse, first.id, 1))
        assertTrue(access.canContinue(first, policy))
        assertEquals(catalog.map { it.id }.toSet(), collection.favorites)
    }

    @Test fun reenteringTheSameCategoryNeedsANewBrowseAuthorization() {
        val left = playingFirst().leaveVisit()
        assertFalse(left.canStart(first, policy))
        val reentered = left.authorizeVisit(firstKey)
        assertTrue(reentered.canStart(first, policy))
        assertTrue(reentered.canStart(sibling, policy))
        assertEquals(setOf(firstKey), reentered.unlockedCategories)
        assertFalse(reentered.leaveVisit().canStart(first, policy))
    }

    @Test fun authorizingAnotherVisitReplacesTheOldGrantWithoutInterruptingTheCurrentStream() {
        val access = playingFirst().authorizeVisit(otherKey)

        assertEquals(setOf(otherKey), access.unlockedCategories)
        assertFalse(access.canStart(first, policy))
        assertFalse(access.canStart(sibling, policy))
        assertTrue(access.canStart(other, policy))
        assertTrue(access.canContinue(first, policy))
        assertFalse(access.canContinue(other, policy))
        assertEquals(playingFirst().continuation, access.continuation)
    }

    @Test fun startingAnotherLockedChannelReplacesTheOldDecoderContinuation() {
        val access = playingFirst().authorizeVisit(otherKey).start(other, policy).leaveVisit()

        assertTrue(access.canContinue(other, policy))
        assertFalse(access.canStart(other, policy))
        assertFalse(access.canContinue(first, policy))
        assertFalse(access.canContinue(sibling, policy))
    }

    @Test fun startingAnUnprotectedChannelDropsAnOldLockedContinuation() {
        val access = playingFirst().leaveVisit().start(open, policy)

        assertNull(access.continuation)
        assertTrue(access.canStart(open, policy))
        assertTrue(access.canContinue(open, policy))
        assertFalse(access.canContinue(first, policy))
    }

    @Test fun aFailedStartCannotCreateOrExtendAuthorization() {
        val access = playingFirst().leaveVisit()

        assertSame(access, access.start(other, policy))
        assertSame(access, access.start(sibling, policy))
        // Even restarting the exact continued station requires a fresh visit authorization.
        assertSame(access, access.start(first, policy))
        assertTrue(access.unlockedCategories.isEmpty())
        assertTrue(access.canContinue(first, policy))
        assertFalse(IptvCategoryAccess().start(first, policy).canContinue(first, policy))
    }

    @Test fun hidingAlwaysDeniesBothAnAuthorizedVisitAndAnExistingContinuation() {
        val access = playingFirst()
        val hidden = policy.hide(firstKey)

        assertFalse(access.canStart(first, hidden))
        assertFalse(access.canContinue(first, hidden))
        assertFalse(access.leaveVisit().canContinue(first, hidden))
        assertTrue(hidden.available(listOf(first, sibling), access.unlockedCategories).isEmpty())
        val revoked = access.revoke(firstKey)
        assertFalse(revoked.canStart(first, hidden.restore(firstKey)))
        assertFalse(revoked.canContinue(first, hidden.restore(firstKey)))
    }

    @Test fun revocationRemovesOnlyTheMatchingVisitAndContinuation() {
        val access = playingFirst().authorizeVisit(otherKey)
        val firstRevoked = access.revoke(firstKey)
        assertEquals(setOf(otherKey), firstRevoked.unlockedCategories)
        assertNull(firstRevoked.continuation)
        assertFalse(firstRevoked.canContinue(first, policy))

        val otherRevoked = access.revoke(otherKey)
        assertTrue(otherRevoked.unlockedCategories.isEmpty())
        assertTrue(otherRevoked.canContinue(first, policy))
        assertFalse(otherRevoked.canStart(other, policy))
        assertSame(access.continuation, access.revoke("unrelated").continuation)
    }

    @Test fun aContinuationBindsSourceChannelAndCanonicalCategoryButNotExpiringUrl() {
        val access = playingFirst().leaveVisit()
        val foreign = first.copy(sourceId = "source-two")
        val regrouped = first.copy(group = other.group)
        val recased = first.copy(group = "a")
        val rules = policy.lock(IptvCategoryPolicy.key(foreign)).lock(IptvCategoryPolicy.key(recased))

        assertFalse(access.canContinue(foreign, rules))
        assertFalse(access.canContinue(regrouped, rules))
        assertFalse(access.canContinue(recased, rules))
        assertFalse(access.canContinue(sibling, rules))
        assertTrue(access.canContinue(first.copy(url = "https://unit.invalid/renewed-token.m3u8"), rules))
        assertTrue(access.canContinue(first.copy(group = "  A\t"), rules))
    }

    @Test fun openingAndLeavingAnotherCategoryWithoutStartingItKeepsTheOriginalStreamOnly() {
        val access = playingFirst().leaveVisit().authorizeVisit(otherKey).leaveVisit()

        assertTrue(access.canContinue(first, policy))
        assertFalse(access.canStart(first, policy))
        assertFalse(access.canStart(other, policy))
        assertFalse(access.canContinue(other, policy))
    }

    @Test fun lockingAgainCannotReuseARevokedPlaybackLease() {
        val access = playingFirst().leaveVisit().revoke(firstKey)
        val unlockedPolicy = policy.removeLock(firstKey)

        assertTrue(access.canStart(first, unlockedPolicy))
        assertTrue(access.canContinue(first, unlockedPolicy))
        assertFalse(access.canStart(first, unlockedPolicy.lock(firstKey)))
        assertFalse(access.canContinue(first, unlockedPolicy.lock(firstKey)))
    }
}
