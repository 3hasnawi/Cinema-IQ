package com.cinemaplus.rebuilt.ui

import org.junit.Assert.*
import org.junit.Test

class TvBrowseFocusPolicyTest {
    @Test fun loadingWithoutAFirstCardNeverFallsBackToTheMiddle() {
        assertEquals(TvBrowseFocusPolicy.Entry.Wait, TvBrowseFocusPolicy.entry(false, true))
    }
    @Test fun explicitEntryWinsOverSpatialSearchEvenWhileLoading() {
        assertEquals(TvBrowseFocusPolicy.Entry.Explicit, TvBrowseFocusPolicy.entry(true, true))
        assertEquals(TvBrowseFocusPolicy.Entry.Explicit, TvBrowseFocusPolicy.entry(true, false))
    }
    @Test fun emptyFinishedPageCanFocusItsRetryOrHeaderControl() {
        assertEquals(TvBrowseFocusPolicy.Entry.EmptyPage, TvBrowseFocusPolicy.entry(false, false))
    }
    @Test fun downAlwaysSelectsTheFirstCardOfTheNextRow() {
        for (index in 0..4) assertEquals(5, TvBrowseFocusPolicy.gridRowFirst(index, 13, 5, true))
        for (index in 5..9) assertEquals(10, TvBrowseFocusPolicy.gridRowFirst(index, 13, 5, true))
    }
    @Test fun upAlwaysSelectsTheFirstCardOfThePreviousRow() {
        for (index in 5..9) assertEquals(0, TvBrowseFocusPolicy.gridRowFirst(index, 13, 5, false))
        for (index in 10..12) assertEquals(5, TvBrowseFocusPolicy.gridRowFirst(index, 13, 5, false))
    }
    @Test fun incompleteLastRowNeverWrapsOrInventsAnItem() {
        assertNull(TvBrowseFocusPolicy.gridRowFirst(12, 13, 5, true))
        assertNull(TvBrowseFocusPolicy.gridRowFirst(3, 13, 5, false))
        assertEquals(4, TvBrowseFocusPolicy.gridRowFirst(3, 5, 4, true))
        assertNull(TvBrowseFocusPolicy.gridRowFirst(4, 5, 4, true))
    }
    @Test fun changedColumnCountUsesTheCurrentLayout() {
        assertEquals(6, TvBrowseFocusPolicy.gridRowFirst(5, 14, 3, true))
        assertEquals(4, TvBrowseFocusPolicy.gridRowFirst(9, 14, 4, false))
    }
    @Test fun emptySingleAndInvalidPositionsAreSafe() {
        assertNull(TvBrowseFocusPolicy.gridRowFirst(0, 0, 5, true))
        assertNull(TvBrowseFocusPolicy.gridRowFirst(0, 1, 1, true))
        assertNull(TvBrowseFocusPolicy.gridRowFirst(-1, 10, 5, true))
        assertNull(TvBrowseFocusPolicy.gridRowFirst(10, 10, 5, false))
        assertNull(TvBrowseFocusPolicy.gridRowFirst(0, 10, 0, true))
    }
}
