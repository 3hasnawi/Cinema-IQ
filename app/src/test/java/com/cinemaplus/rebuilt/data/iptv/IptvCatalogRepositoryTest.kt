package com.cinemaplus.rebuilt.data.iptv

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class IptvCatalogRepositoryTest {
    private val source = IptvSource("one", "Fixture", IptvKind.M3U, "https://provider.example/list.m3u")
    private val cached = IptvCatalogSnapshot(1_000, IptvCatalog(listOf(IptvChannel("one:a", "one", "Saved", "News", "https://media.example/old.m3u8"))))
    private val playlist = "#EXTM3U\n#EXTINF:-1,Fresh\nhttps://media.example/new.m3u8\n".toByteArray()
    private class Store(var snapshot: IptvCatalogSnapshot?) : IptvCatalogCache {
        override fun read(source: IptvSource) = snapshot
        override fun write(source: IptvSource, snapshot: IptvCatalogSnapshot) { this.snapshot = snapshot }
        override fun remove(sourceId: String) { snapshot = null }
    }

    @Test fun freshCacheReopenDoesNotContactTheProvider() = runBlocking {
        val calls = AtomicInteger(); val store = Store(cached)
        val client = IptvClient(IptvTransport { _, _ -> calls.incrementAndGet(); error("Unexpected network") })
        repeat(2) {
            val values = IptvCatalogRepository(store, client) { 2_000 }.load(source).toList()
            assertEquals(listOf(IptvCatalogUpdate(cached.catalog, false)), values)
        }
        assertEquals(0, calls.get())
    }
    @Test fun staleChannelsAreEmittedBeforeASlowNetworkRequestCompletes() = runBlocking {
        val release = CompletableDeferred<Unit>(); val first = CompletableDeferred<IptvCatalogUpdate>()
        val store = Store(cached)
        val client = IptvClient(IptvTransport { _, _ -> release.await(); playlist })
        val results = mutableListOf<IptvCatalogUpdate>()
        val task = launch {
            IptvCatalogRepository(store, client) { 2_000_000 }.load(source).collect { results += it; first.complete(it) }
        }
        val immediate = withTimeout(2_000) { first.await() }
        assertEquals(cached.catalog, immediate.catalog); assertTrue(immediate.refreshing)
        assertFalse(release.isCompleted)
        release.complete(Unit); task.join()
        assertEquals(2, results.size); assertEquals("Fresh", results.last().catalog.channels.single().name)
        assertFalse(results.last().refreshing); assertEquals(2_000_000L, store.snapshot!!.savedAt)
    }
    @Test fun failedRefreshKeepsTheSavedCatalogue() = runBlocking {
        val store = Store(cached); val seen = mutableListOf<IptvCatalogUpdate>()
        val calls = AtomicInteger()
        val client = IptvClient(IptvTransport { _, _ -> calls.incrementAndGet(); throw IptvException(IptvException.Reason.Network) })
        try { IptvCatalogRepository(store, client) { 2_000_000 }.load(source).collect { seen += it }; fail() }
        catch (e: IptvException) { assertEquals(IptvException.Reason.Network, e.reason) }
        assertEquals(cached.catalog, seen.single().catalog)
        assertEquals(cached.catalog, store.snapshot!!.catalog); assertEquals(cached.savedAt, store.snapshot!!.savedAt)
        assertEquals(cached.catalog, IptvCatalogRepository(store, client) { 2_001_000 }.load(source).single().catalog)
        assertEquals(1, calls.get())
    }
    @Test fun forcedRefreshBypassesFreshnessAndCacheFailureDoesNotBreakPlayback() = runBlocking {
        val calls = AtomicInteger(); val client = IptvClient(IptvTransport { _, _ -> calls.incrementAndGet(); playlist })
        val store = Store(cached)
        assertEquals(2, IptvCatalogRepository(store, client) { 2_000 }.load(source, true).toList().size)
        assertEquals(1, calls.get())
        val broken = object : IptvCatalogCache {
            override fun read(source: IptvSource): IptvCatalogSnapshot? = error("Disk unavailable")
            override fun write(source: IptvSource, snapshot: IptvCatalogSnapshot) { error("Disk full") }
            override fun remove(sourceId: String) = Unit
        }
        assertEquals("Fresh", IptvCatalogRepository(broken, client).load(source).single().catalog.channels.single().name)
    }
    @Test fun leavingDuringRefreshCancelsTheRequestAndDoesNotReplaceCachedData() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>(); val store = Store(cached)
        val client = IptvClient(IptvTransport { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        val task = launch { IptvCatalogRepository(store, client) { 2_000_000 }.load(source).collect() }
        withTimeout(2_000) { started.await() }; task.cancelAndJoin()
        assertTrue(cancelled.isCompleted); assertEquals(cached, store.snapshot)
    }
}
