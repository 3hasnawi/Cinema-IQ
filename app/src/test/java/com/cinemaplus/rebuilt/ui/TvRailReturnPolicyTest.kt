package com.cinemaplus.rebuilt.ui

import org.junit.Assert.*
import org.junit.Test

class TvRailReturnPolicyTest {
    @Test fun leftStartsOneReturnAndConsumesReleaseAfterFocusMoves() {
        val press = TvRailReturnPress()
        assertEquals(TvRailReturnPress.Result(true, true), press.route(true, true, 0, true))
        repeat(5) { assertEquals(TvRailReturnPress.Result(true), press.route(true, true, it + 1, false)) }
        assertEquals(TvRailReturnPress.Result(true), press.route(true, false, 0, false))
        assertFalse(press.route(true, true, 0, false).consumed)
    }
    @Test fun otherKeysAndPageCarouselLeftRemainUntouched() {
        val press = TvRailReturnPress()
        assertFalse(press.route(false, true, 0, true).consumed)
        assertFalse(press.route(true, true, 0, false).consumed)
        assertFalse(press.route(true, false, 0, false).consumed)
    }
    @Test fun heldOrStrayReleaseOnRailDoesNotStartReturn() {
        val press = TvRailReturnPress()
        assertFalse(press.route(true, true, 3, true).restore)
        assertFalse(press.route(true, false, 0, true).restore)
        assertTrue(press.route(true, true, 0, true).restore)
    }
    @Test fun changingSectionResetsPressGate() {
        val press = TvRailReturnPress()
        press.route(true, true, 0, true)
        press.cancel()
        assertFalse(press.route(true, true, 0, false).consumed)
        assertTrue(press.route(true, true, 0, true).restore)
    }
    @Test fun ordinaryRowEntryNeverRequestsSavedFocus() {
        val scope = TvRailReturnScope()
        assertFalse(scope.restoring)
        assertEquals("restored", scope.restore { assertTrue(scope.restoring); "restored" })
        assertFalse(scope.restoring)
    }
    @Test fun nestedReturnScopesAndFailuresRestoreTheirPreviousMode() {
        val scope = TvRailReturnScope()
        scope.restore {
            scope.restore { assertTrue(scope.restoring) }
            assertTrue(scope.restoring)
        }
        try { scope.restore { throw IllegalStateException("test") } } catch (_: IllegalStateException) { }
        assertFalse(scope.restoring)
    }
}
