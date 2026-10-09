package com.cinemaplus.rebuilt.ui

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class PlaybackScreenAwakeTest {
    @Test fun readyAndBufferingPlaybackPreventSleep() {
        assertTrue(playbackNeedsAwakeScreen(true, true, Player.STATE_READY, false))
        assertTrue(playbackNeedsAwakeScreen(true, true, Player.STATE_BUFFERING, false))
    }
    @Test fun pauseBackgroundAndFailureReleaseProtection() {
        assertFalse(playbackNeedsAwakeScreen(true, false, Player.STATE_READY, false))
        assertFalse(playbackNeedsAwakeScreen(false, true, Player.STATE_READY, false))
        assertFalse(playbackNeedsAwakeScreen(true, true, Player.STATE_READY, true))
    }
    @Test fun completionAndIdleReleaseProtection() {
        assertFalse(playbackNeedsAwakeScreen(true, true, Player.STATE_ENDED, false))
        assertFalse(playbackNeedsAwakeScreen(true, true, Player.STATE_IDLE, false))
    }
}
