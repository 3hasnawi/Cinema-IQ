package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceChoiceTest {
    @Test fun voduDoesNotRequestCinemana() = runBlocking {
        val requests = SourceChoice.VODU.requests(suspend { error("Cinemana must not be contacted") }, listOf(suspend { "VODU" }))
        assertEquals(listOf("VODU"), progressiveSources(requests) {}.map { it.getOrThrow() })
    }
    @Test fun cinemanaDoesNotRequestVodu() = runBlocking {
        val requests = SourceChoice.CINEMANA.requests(suspend { "Cinemana" }, listOf(suspend { error("VODU must not be contacted") }))
        assertEquals(listOf("Cinemana"), progressiveSources(requests) {}.map { it.getOrThrow() })
    }
    @Test fun preferenceRoundTripAndDefault() {
        SourceChoice.entries.forEach { assertEquals(it, SourceChoice.restore(it.name)) }
        assertEquals(SourceChoice.BOTH, SourceChoice.restore(null))
        assertEquals(SourceChoice.BOTH, SourceChoice.restore("unknown"))
        assertEquals(2, SourceChoice.BOTH.requests(suspend { 1 }, listOf(suspend { 2 })).size)
    }
}
