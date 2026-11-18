package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvReconnectPolicyTest {
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
