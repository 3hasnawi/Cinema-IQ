package com.cinemaplus.rebuilt.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchNextPlanTest {
    private fun film(id: String, series: String = "") = Film(id, id, seriesId = series)

    @Test fun oneEntryPerSeriesKeepsTheFirstEpisode() {
        val plan = WatchNextPlan.plan(listOf(film("e2", "show"), film("e1", "show"), film("m1")), emptySet())
        assertEquals(listOf("e2", "m1"), plan.upsert.map { it.id })
    }
    @Test fun staleEntriesAreDeleted() {
        val plan = WatchNextPlan.plan(listOf(film("m1")), setOf("m1", "old", "series:gone"))
        assertEquals(setOf("old", "series:gone"), plan.deleteKeys)
    }
    @Test fun rowIsLimited() {
        val plan = WatchNextPlan.plan((1..25).map { film("m$it") }, emptySet())
        assertEquals(WatchNextPlan.LIMIT, plan.upsert.size)
        assertEquals("m1", plan.upsert.first().id)
    }
    @Test fun keyUsesSeriesWhenKnown() {
        assertEquals("series:s", WatchNextPlan.key(film("e", "s")))
        assertEquals("m", WatchNextPlan.key(film("m")))
    }
}
