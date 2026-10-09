package com.cinemaplus.rebuilt.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfSnapshotTest {
    private val film = Film("1", "فيلم", poster = "https://x/p.jpg", backdrop = "https://x/b.jpg", year = "2020", rating = "7.1", series = true, seriesId = "s")

    @Test fun roundTripKeepsIdentityAndArtwork() {
        val decoded = ShelfSnapshot.decode(ShelfSnapshot.encode(listOf(Shelf("a", "رف", listOf(film)))))!!
        assertEquals("a", decoded.single().id)
        val back = decoded.single().films.single()
        assertEquals("1", back.id)
        assertEquals("https://x/p.jpg", back.poster)
        assertEquals(true, back.series)
        assertEquals("s", back.seriesId)
    }
    @Test fun damagedTextIsRejectedAndEmptyShelvesDropped() {
        assertNull(ShelfSnapshot.decode("{not json"))
        assertNull(ShelfSnapshot.decode(null))
        assertEquals(emptyList<Shelf>(), ShelfSnapshot.decode(ShelfSnapshot.encode(listOf(Shelf("e", "فارغ", emptyList())))))
    }
    @Test fun snapshotIsBounded() {
        val many = (1..100).map { Film("m$it", "t$it") }
        val decoded = ShelfSnapshot.decode(ShelfSnapshot.encode((1..20).map { Shelf("s$it", "t", many) }))!!
        assertEquals(12, decoded.size)
        assertEquals(30, decoded.first().films.size)
    }
}
