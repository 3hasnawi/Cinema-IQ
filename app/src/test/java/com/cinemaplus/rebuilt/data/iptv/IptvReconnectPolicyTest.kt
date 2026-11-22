package com.cinemaplus.rebuilt.data.iptv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IptvReconnectPolicyTest {
    @Test fun returnOfWifiWakesOnlyThePendingTransportRetry() = runTest {
        val route = MutableStateFlow(true)
        val job = launch { IptvReconnectPolicy.awaitTvRoute(30_000, true, route) }
        advanceTimeBy(1_000)
        route.value = false
        advanceTimeBy(2_000)
        assertTrue(job.isActive)
        route.value = true
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test fun prolongedOutageWaitsForRouteAndCancelledChannelNeverRestarts() = runTest {
        val route = MutableStateFlow(false)
        var oldChannelAttempts = 0
        val previous = launch { IptvReconnectPolicy.awaitTvRoute(1_000, true, route); oldChannelAttempts++ }
        advanceTimeBy(120_000)
        assertTrue(previous.isActive)
        previous.cancelAndJoin()
        route.value = true
        runCurrent()
        assertEquals(0, oldChannelAttempts)
    }

    @Test fun connectedRouteStillUsesBackoffAndHandoverCannotShortenProviderCooldown() = runTest {
        val route = MutableStateFlow(true)
        val ordinary = launch { IptvReconnectPolicy.awaitTvRoute(8_000, true, route) }
        advanceTimeBy(7_999); assertTrue(ordinary.isActive)
        advanceTimeBy(1); runCurrent(); assertTrue(ordinary.isCompleted)
        val limited = launch { IptvReconnectPolicy.awaitTvRoute(60_000, false, route) }
        advanceTimeBy(5_000); route.value = false
        advanceTimeBy(5_000); route.value = true
        advanceTimeBy(49_999); assertTrue(limited.isActive)
        advanceTimeBy(1); runCurrent(); assertTrue(limited.isCompleted)
    }

    @Test fun lateRenewalCannotOverrideNewStationSourceOrReturnVisit() {
        val first = IptvChannel("one", "source", "News", "Local", "https://media.example/one")
        val pending = IptvPendingReconnect(first, 1)
        assertTrue(pending.matches(first.copy(url = "https://media.example/renewed"), 1))
        assertFalse(pending.matches(first.copy(id = "two"), 1))
        assertFalse(pending.matches(first.copy(sourceId = "other"), 1))
        assertFalse(pending.matches(first, 3))
    }

    @Test fun expiredLinkRenewalSurvivesTransientOutagesButHonoursRefusal() {
        assertTrue(IptvReconnectPolicy.retryableRenewal(IptvException(IptvException.Reason.Network)))
        for (code in listOf(408, 429, 500, 503)) {
            assertTrue(IptvReconnectPolicy.retryableRenewal(IptvException(IptvException.Reason.Http, code)))
        }
        for (code in listOf(401, 403, 404)) {
            assertFalse(IptvReconnectPolicy.retryableRenewal(IptvException(IptvException.Reason.Http, code)))
        }
        assertEquals(IptvRetry(8_000, true), IptvReconnectPolicy.televisionRetry(IptvFailure.Authentication, 3))
        assertNull(IptvReconnectPolicy.televisionRetry(IptvFailure.Authentication, 3, linkRenewed = true))
    }

    @Test fun televisionAuthorizationRenewsOnlyOnceAndCannotLoopRejectedCredentials() {
        assertTrue(IptvReconnectPolicy.televisionRetry(IptvFailure.Authentication, 0)!!.refreshLink)
        assertNull(IptvReconnectPolicy.televisionRetry(IptvFailure.Authentication, 1, linkRenewed = true))
        assertNull(IptvReconnectPolicy.televisionRetry(IptvFailure.Authentication, Int.MAX_VALUE, linkRenewed = true))
    }
    @Test fun televisionPreservesRateLimitConsentAndUnsupportedBoundaries() {
        assertEquals(120_000L, IptvReconnectPolicy.televisionRetry(IptvFailure.RateLimited, 5, 120_000)!!.delayMillis)
        assertFalse(IptvReconnectPolicy.televisionRetry(IptvFailure.RateLimited, 2)!!.refreshLink)
        assertNull(IptvReconnectPolicy.televisionRetry(IptvFailure.HttpConsent, 0))
        assertNull(IptvReconnectPolicy.televisionRetry(IptvFailure.Unsupported, 0))
    }
    @Test fun temporaryFailuresRetryWithCappedBackoff() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L),
            (0..6).map { IptvReconnectPolicy.retry(IptvFailure.Temporary, it)!!.delayMillis })
        assertEquals(30_000L, IptvReconnectPolicy.retry(IptvFailure.Temporary, Int.MAX_VALUE)!!.delayMillis)
    }
    @Test fun accessFailureTriesTheStreamBeforeRequestingANewLink() {
        assertFalse(IptvReconnectPolicy.retry(IptvFailure.Access, 0)!!.refreshLink)
        assertTrue(IptvReconnectPolicy.retry(IptvFailure.Access, 1)!!.refreshLink)
        assertTrue(IptvReconnectPolicy.retry(IptvFailure.Temporary, 2)!!.refreshLink)
    }
    @Test fun rateLimitsHonorTheProviderDelay() {
        assertEquals(30_000L, IptvReconnectPolicy.retry(IptvFailure.RateLimited, 0)!!.delayMillis)
        assertEquals(120_000L, IptvReconnectPolicy.retry(IptvFailure.RateLimited, 0, 120_000)!!.delayMillis)
        assertFalse(IptvReconnectPolicy.retry(IptvFailure.RateLimited, 2)!!.refreshLink)
        assertEquals(120_000L, IptvReconnectPolicy.retryAfter("120", 0))
        assertEquals(60_000L, IptvReconnectPolicy.retryAfter("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0L, IptvReconnectPolicy.retryAfter("invalid", 0))
    }
    @Test fun unsupportedFormatsAndMissingHttpConsentDoNotRetryForever() {
        assertNull(IptvReconnectPolicy.retry(IptvFailure.Unsupported, 0))
        assertNull(IptvReconnectPolicy.retry(IptvFailure.HttpConsent, 0))
    }
    @Test fun pausedOrBackgroundPlayersCannotReconnectAutomatically() {
        assertTrue(IptvReconnectPolicy.allowed(true, false))
        assertFalse(IptvReconnectPolicy.allowed(true, true))
        assertFalse(IptvReconnectPolicy.allowed(false, false))
        assertFalse(IptvReconnectPolicy.allowed(false, true))
    }
}
