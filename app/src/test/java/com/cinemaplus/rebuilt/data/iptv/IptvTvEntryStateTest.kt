package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvTvEntryStateTest {
    private data class Entry(
        val storageReady: Boolean = false,
        val storageLoading: Boolean = true,
        val hasSources: Boolean = false,
        val busy: Boolean = false,
        val autoStartPending: Boolean = false,
        val waitingForPin: Boolean = false,
        val hasFailure: Boolean = false
    ) {
        fun state() = IptvTvEntryState.resolve(storageReady, storageLoading, hasSources, busy,
            autoStartPending, waitingForPin, hasFailure)
    }

    @Test fun coldEntryDoesNotFlashAddSourceBetweenStorageCatalogAndPlaybackCommit() {
        val restoringStorage = Entry()
        assertEquals(IptvTvEntryState.Loading, restoringStorage.state())

        // Sources may be published one composition before the catalogue loader marks itself busy.
        val sourcesPublished = restoringStorage.copy(storageReady = true, storageLoading = false,
            hasSources = true, autoStartPending = true)
        assertEquals(IptvTvEntryState.Loading, sourcesPublished.state())
        val downloadingCatalog = sourcesPublished.copy(busy = true)
        assertEquals(IptvTvEntryState.Loading, downloadingCatalog.state())

        // A cached or freshly loaded catalogue can be available before the screen commits its station.
        val catalogReady = downloadingCatalog.copy(busy = false)
        assertEquals(IptvTvEntryState.Loading, catalogReady.state())
    }

    @Test fun addSourceAppearsOnlyAfterSuccessfulEmptyStorageInitialization() {
        val restoring = Entry()
        assertEquals(IptvTvEntryState.Loading, restoring.state())
        assertEquals(IptvTvEntryState.AddSource, restoring.copy(storageReady = true, storageLoading = false).state())
        assertEquals(IptvTvEntryState.Recovery, restoring.copy(storageLoading = false).state())
    }

    @Test fun deletingTheFinalSourceDoesNotKeepAnObsoleteAutomaticEntrySpinner() {
        val previousSource = Entry(storageReady = true, storageLoading = false, hasSources = true, autoStartPending = true)
        assertEquals(IptvTvEntryState.Loading, previousSource.state())
        assertEquals(IptvTvEntryState.AddSource, previousSource.copy(hasSources = false).state())
    }

    @Test fun pinWaitLoadsButCancellingAStartedAttemptSettlesToRecovery() {
        val pending = Entry(storageReady = true, storageLoading = false, hasSources = true, autoStartPending = true)
        val awaitingPin = pending.copy(autoStartPending = false, waitingForPin = true)
        assertEquals(IptvTvEntryState.Loading, pending.state())
        assertEquals(IptvTvEntryState.Loading, awaitingPin.state())
        assertEquals(IptvTvEntryState.Recovery, awaitingPin.copy(waitingForPin = false).state())
    }

    @Test fun aRealCatalogFailureOverridesThePendingAutomaticStartAndCanBeRetried() {
        val loading = Entry(storageReady = true, storageLoading = false, hasSources = true,
            busy = true, autoStartPending = true)
        val failed = loading.copy(busy = false, hasFailure = true)
        assertEquals(IptvTvEntryState.Loading, loading.state())
        assertEquals(IptvTvEntryState.Recovery, failed.state())
        assertEquals(IptvTvEntryState.Loading, failed.copy(hasFailure = false, busy = true).state())
    }

    @Test fun storageFailureNeverPresentsEmptyStorageAsANewInstall() {
        val failed = Entry(storageLoading = false, hasFailure = true)
        assertEquals(IptvTvEntryState.Recovery, failed.state())
        assertEquals(IptvTvEntryState.Recovery, failed.copy(storageLoading = true, autoStartPending = true).state())
    }

    @Test fun addingTheFirstSourceShowsWorkInProgressWithoutAnotherAddPrompt() {
        val empty = Entry(storageReady = true, storageLoading = false)
        assertEquals(IptvTvEntryState.AddSource, empty.state())
        assertEquals(IptvTvEntryState.Loading, empty.copy(busy = true).state())
    }

    @Test fun settledNoPlayableStationIsRecoveryRatherThanAnEndlessSpinner() {
        val settled = Entry(storageReady = true, storageLoading = false, hasSources = true)
        assertEquals(IptvTvEntryState.Recovery, settled.state())
        assertEquals(IptvTvEntryState.Recovery, settled.copy(hasFailure = true, waitingForPin = true).state())
    }
}
