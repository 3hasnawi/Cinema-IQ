package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test

class IptvLivePlaybackPolicyTest {
    @Test fun compressedBufferBudgetScalesWithHeapAndStaysBoundedOnLargeTvs() {
        val mib = 1024 * 1024
        for ((heap, expected) in listOf(64 to 8, 128 to 16, 256 to 32, 512 to 64, 1024 to 96)) {
            assertEquals(expected * mib, IptvLivePlaybackPolicy.targetBufferBytes(heap.toLong() * mib))
        }
    }

    @Test fun firstPlaybackStaysFastWhileRecoveryBecomesMoreStableAndRemainsBounded() {
        val policy = IptvLivePlaybackPolicy
        assertEquals(750_000L, policy.startThresholdUs(false, Int.MAX_VALUE, -1))
        assertEquals(listOf(2_000, 3_000, 4_000, 6_000, 6_000), (0..4).map(policy::rebufferMs))
        assertEquals(2_000, policy.rebufferMs(Int.MIN_VALUE))
        assertEquals(6_000_000L, policy.startThresholdUs(true, Int.MAX_VALUE, Long.MIN_VALUE))
        assertTrue(policy.rebufferMs(Int.MAX_VALUE) <= policy.MIN_BUFFER_MS)
        assertTrue(policy.MIN_BUFFER_MS < policy.MAX_BUFFER_MS)
    }

    @Test fun shortLiveWindowsCapBothStartupAndRecoveryInsteadOfWaitingForUnavailableMedia() {
        assertEquals(500_000L, IptvLivePlaybackPolicy.startThresholdUs(false, 0, 1_000_000))
        assertEquals(500_000L, IptvLivePlaybackPolicy.startThresholdUs(true, 3, 1_000_000))
        assertEquals(750_000L, IptvLivePlaybackPolicy.startThresholdUs(false, 0, Long.MAX_VALUE))
        assertEquals(6_000_000L, IptvLivePlaybackPolicy.startThresholdUs(true, 3, Long.MAX_VALUE))
        for (target in listOf(0L, 1L)) {
            assertEquals(0L, IptvLivePlaybackPolicy.startThresholdUs(false, 0, target))
            assertEquals(0L, IptvLivePlaybackPolicy.startThresholdUs(true, 3, target))
        }
    }

    @Test fun readyPlaybackMustRemainStuckForTheFullFifteenSeconds() {
        val watchdog = IptvLiveStallWatchdog()
        assertFalse(watchdog.sample("one", 0, 42_000, true, true))
        for (now in 2_000L..14_000L step 2_000) {
            assertFalse(watchdog.sample("one", now, 42_000, true, true))
        }
        assertFalse(watchdog.sample("one", 14_999, 42_000, true, true))
        assertTrue(watchdog.sample("one", 15_000, 42_000, true, true))
    }

    @Test fun recoveryFiresOnceUntilCumulativeProgressRearmsIt() {
        val watchdog = IptvLiveStallWatchdog()
        assertFalse(watchdog.sample("one", 0, 1_000, true, true))
        assertTrue(watchdog.sample("one", 15_000, 1_000, true, true))
        assertFalse(watchdog.sample("one", 60_000, 1_000, true, true))
        assertFalse(watchdog.sample("one", 62_000, 1_050, true, true))
        assertFalse(watchdog.sample("one", 64_000, 1_099, true, true))
        assertFalse(watchdog.sample("one", 66_000, 1_100, true, true))
        assertFalse(watchdog.sample("one", 80_999, 1_100, true, true))
        assertTrue(watchdog.sample("one", 81_000, 1_100, true, true))
    }

    @Test fun smallForwardStepsAccumulateWithoutFalseStallRecovery() {
        val watchdog = IptvLiveStallWatchdog()
        for (sample in 0L..60L) {
            assertFalse(watchdog.sample("one", sample * 2_000, sample * 25, true, true))
        }
    }

    @Test fun bufferingPauseAndSuppressionDiscardTheOldStallTimer() {
        val states = listOf(Triple(false, true, false), Triple(true, false, false), Triple(true, true, true))
        for ((ready, shouldPlay, suppressed) in states) {
            val watchdog = IptvLiveStallWatchdog()
            assertFalse(watchdog.sample("one", 0, 1_000, true, true))
            assertFalse(watchdog.sample("one", 14_000, 1_000, ready, shouldPlay, suppressed))
            assertFalse(watchdog.sample("one", 30_000, 1_000, ready, shouldPlay, suppressed))
            assertFalse(watchdog.sample("one", 31_000, 1_000, true, true))
            assertFalse(watchdog.sample("one", 45_999, 1_000, true, true))
            assertTrue(watchdog.sample("one", 46_000, 1_000, true, true))
        }
    }

    @Test fun invalidClocksAndPositionsCannotTriggerRecoveryOrPreserveAnOldTimer() {
        for ((now, position) in listOf(-1L to 1_000L, 14_000L to -1L, Long.MIN_VALUE to Long.MIN_VALUE)) {
            val watchdog = IptvLiveStallWatchdog()
            assertFalse(watchdog.sample("one", 0, 1_000, true, true))
            assertFalse(watchdog.sample("one", now, position, true, true))
            assertFalse(watchdog.sample("one", 15_000, 1_000, true, true))
            assertTrue(watchdog.sample("one", 30_000, 1_000, true, true))
        }
    }

    @Test fun backwardSeekIsDetectedEvenBeforeAccumulatingOneHundredMilliseconds() {
        val watchdog = IptvLiveStallWatchdog()
        assertFalse(watchdog.sample("one", 0, 1_000, true, true))
        assertFalse(watchdog.sample("one", 14_000, 1_050, true, true))
        assertFalse(watchdog.sample("one", 15_000, 1_040, true, true))
        assertFalse(watchdog.sample("one", 29_999, 1_040, true, true))
        assertTrue(watchdog.sample("one", 30_000, 1_040, true, true))
    }

    @Test fun backwardClockRestartsTheFullObservationWindow() {
        val watchdog = IptvLiveStallWatchdog()
        assertFalse(watchdog.sample("one", 0, 1_000, true, true))
        assertFalse(watchdog.sample("one", 14_000, 1_000, true, true))
        assertFalse(watchdog.sample("one", 13_000, 1_000, true, true))
        assertFalse(watchdog.sample("one", 27_999, 1_000, true, true))
        assertTrue(watchdog.sample("one", 28_000, 1_000, true, true))
    }

    @Test fun channelChangesAndExplicitResetRearmWithoutInheritingThePreviousDeadline() {
        val watchdog = IptvLiveStallWatchdog()
        assertFalse(watchdog.sample("one", 0, 1_000, true, true))
        assertTrue(watchdog.sample("one", 15_000, 1_000, true, true))
        assertFalse(watchdog.sample("two", 30_000, 1_000, true, true))
        assertFalse(watchdog.sample("two", 44_999, 1_000, true, true))
        assertTrue(watchdog.sample("two", 45_000, 1_000, true, true))
        watchdog.reset()
        assertFalse(watchdog.sample("two", 60_000, 1_000, true, true))
        assertTrue(watchdog.sample("two", 75_000, 1_000, true, true))
    }
}
