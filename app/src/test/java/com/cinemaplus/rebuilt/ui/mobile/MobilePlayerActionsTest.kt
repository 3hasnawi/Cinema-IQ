package com.cinemaplus.rebuilt.ui.mobile

import androidx.media3.common.C
import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class MobilePlayerActionsTest {
    private fun player(state: Int, duration: Long = 60_000L, seekable: Boolean = true,
        actions: MutableList<String> = mutableListOf()): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "getPlaybackState" -> state
            "getDuration" -> duration
            "isCurrentMediaItemSeekable" -> seekable
            "getPlayWhenReady" -> true
            "seekTo" -> { actions.add("seek:${args?.last()}"); null }
            "play", "pause" -> { actions.add(method.name); null }
            else -> null
        }
    } as Player

    @Test fun endedPlaybackSeeksToStartBeforePlayingEvenWithPlayWhenReadyTrue() {
        val actions = mutableListOf<String>()
        MobilePlayerActions.toggle(player(Player.STATE_ENDED, actions = actions))
        assertEquals(listOf("seek:0", "play"), actions)
    }

    @Test fun readyPlaybackStillPausesNormally() {
        val actions = mutableListOf<String>()
        MobilePlayerActions.toggle(player(Player.STATE_READY, actions = actions))
        assertEquals(listOf("pause"), actions)
    }

    @Test fun seekingRequiresReadyStateKnownDurationAndSeekableTimeline() {
        assertTrue(MobilePlayerActions.canSeek(player(Player.STATE_READY)))
        assertFalse(MobilePlayerActions.canSeek(player(Player.STATE_BUFFERING)))
        assertFalse(MobilePlayerActions.canSeek(player(Player.STATE_READY, C.TIME_UNSET)))
        assertFalse(MobilePlayerActions.canSeek(player(Player.STATE_READY, 0)))
        assertFalse(MobilePlayerActions.canSeek(player(Player.STATE_READY, seekable = false)))
    }
}
