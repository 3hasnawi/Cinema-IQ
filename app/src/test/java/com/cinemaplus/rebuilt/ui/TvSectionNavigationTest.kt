package com.cinemaplus.rebuilt.ui

import org.junit.Assert.*
import org.junit.Test

class TvSectionNavigationTest {
    @Test fun heroEntersFirstRailAndFirstRailReturnsToHero() {
        assertEquals(1, adjacentTvSection(0, 4, true))
        assertEquals(0, adjacentTvSection(1, 4, false))
    }
    @Test fun sectionOrderSupportsEpisodesCastAndRelatedWithoutWrapping() {
        assertEquals(3, adjacentTvSection(2, 5, true))
        assertEquals(4, adjacentTvSection(3, 5, true))
        assertEquals(2, adjacentTvSection(3, 5, false))
        assertNull(adjacentTvSection(4, 5, true))
        assertNull(adjacentTvSection(0, 5, false))
    }
    @Test fun loadingAndRemovedTargetsDoNotCreateInvalidDestinations() {
        assertNull(adjacentTvSection(0, 0, true))
        assertNull(adjacentTvSection(0, 1, true))
        assertNull(adjacentTvSection(-1, 4, true))
        assertNull(adjacentTvSection(4, 2, false))
    }
}
