package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProgressiveSourcesTest {
    @Test fun fastSourceAppearsWhileSlowSourceIsStillWaiting() = runBlocking {
        withTimeout(5000) {
            val slow = CompletableDeferred<String>()
            val published = CompletableDeferred<String>()
            val seen = mutableListOf<String>()
            val loading = async {
                progressiveSources(listOf(suspend { slow.await() }, suspend { "VODU" })) {
                    seen.add(it)
                    published.complete(it)
                }
            }
            assertEquals("VODU", published.await())
            assertTrue(loading.isActive)
            assertEquals(listOf("VODU"), seen)
            slow.complete("Cinemana")
            assertEquals(listOf("Cinemana", "VODU"), loading.await().map { it.getOrThrow() })
            assertEquals(listOf("VODU", "Cinemana"), seen)
        }
    }

    @Test fun failedSourceDoesNotDiscardSuccessfulSource() = runBlocking {
        val seen = mutableListOf<String>()
        val results = progressiveSources(listOf(suspend { error("offline") }, suspend { "VODU" })) { seen.add(it) }
        assertTrue(results[0].isFailure)
        assertEquals("VODU", results[1].getOrThrow())
        assertEquals(listOf("VODU"), seen)
    }

    @Test fun cancellingLoadDoesNotPublishOldResults() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var published = false
        val loading = launch {
            progressiveSources(listOf(suspend { started.complete(Unit); awaitCancellation() })) { published = true }
        }
        started.await()
        loading.cancelAndJoin()
        assertFalse(published)
    }
}
