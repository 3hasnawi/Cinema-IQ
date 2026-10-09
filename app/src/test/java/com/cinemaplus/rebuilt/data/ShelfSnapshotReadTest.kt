package com.cinemaplus.rebuilt.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShelfSnapshotReadTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun removedFileIsACacheMiss() {
        val file = temp.newFile()
        assertTrue(file.delete())
        assertNull(ShelfSnapshot.read(file))
    }
    @Test fun directoryInsteadOfFileIsACacheMiss() {
        assertNull(ShelfSnapshot.read(temp.newFolder()))
    }
    @Test fun damagedContentIsACacheMiss() {
        val file = temp.newFile().apply { writeText("incomplete JSON") }
        assertNull(ShelfSnapshot.read(file))
    }
    @Test fun validFileStillLoads() {
        val file = temp.newFile().apply {
            writeText(ShelfSnapshot.encode(listOf(Shelf("home", "Home", listOf(Film("one", "One"))))))
        }
        assertEquals("one", ShelfSnapshot.read(file)!!.single().films.single().id)
    }
}
