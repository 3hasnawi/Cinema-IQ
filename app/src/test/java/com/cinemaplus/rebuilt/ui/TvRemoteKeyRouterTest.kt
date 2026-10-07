package com.cinemaplus.rebuilt.ui

import org.junit.Assert.*
import org.junit.Test
import com.cinemaplus.rebuilt.ui.TvRemoteKeyRouter.Key
import com.cinemaplus.rebuilt.ui.TvRemoteKeyRouter.Command

class TvRemoteKeyRouterTest {
    @Test fun hiddenSelectTogglesOnceAndConsumesReleaseAfterReveal() {
        val router = TvRemoteKeyRouter()
        val down = router.route(Key.Select, true, 0, false)
        assertEquals(listOf(Command.TogglePlayback, Command.Reveal), down.commands)
        assertTrue(router.route(Key.Select, false, 0, true).consumed)
        assertTrue(router.route(Key.Select, false, 0, true).commands.isEmpty())
    }
    @Test fun holdingSelectDoesNotToggleRepeatedly() {
        val router = TvRemoteKeyRouter()
        router.route(Key.Select, true, 0, false)
        repeat(8) { assertTrue(router.route(Key.Select, true, it + 1, true).commands.isEmpty()) }
        router.route(Key.Select, false, 0, true)
        assertFalse(router.route(Key.Select, true, 0, true).consumed)
    }
    @Test fun visibleSelectPassesToTheFocusedAction() {
        val router = TvRemoteKeyRouter()
        assertFalse(router.route(Key.Select, true, 0, true).consumed)
        assertFalse(router.route(Key.Select, false, 0, true).consumed)
    }
    @Test fun mediaToggleWorksWithHiddenOrVisibleControls() {
        for(visible in listOf(false, true)) {
            val router = TvRemoteKeyRouter()
            assertEquals(Command.TogglePlayback, router.route(Key.PlayPause, true, 0, visible).commands.first())
            assertTrue(router.route(Key.PlayPause, false, 0, true).consumed)
        }
    }
    @Test fun hiddenDirectionsSeekOrRevealButVisibleDirectionsKeepNavigation() {
        val router = TvRemoteKeyRouter()
        assertEquals(listOf(Command.SeekForward, Command.Reveal), router.route(Key.Right, true, 0, false).commands)
        router.route(Key.Right, false, 0, true)
        assertFalse(router.route(Key.Right, true, 0, true).consumed)
        assertEquals(listOf(Command.Reveal), router.route(Key.Up, true, 0, false).commands)
    }
    @Test fun separatePlayAndPauseButtonsHaveExplicitCommands() {
        val router = TvRemoteKeyRouter()
        assertEquals(Command.Play, router.route(Key.Play, true, 0, true).commands.first())
        router.route(Key.Play, false, 0, true)
        assertEquals(Command.Pause, router.route(Key.Pause, true, 0, false).commands.first())
    }
}
