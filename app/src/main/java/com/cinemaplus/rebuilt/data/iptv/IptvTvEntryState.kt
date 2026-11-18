package com.cinemaplus.rebuilt.data.iptv

/** Landing content used only while no station decoder is visible. It does not start playback itself. */
internal enum class IptvTvEntryState {
    Loading, AddSource, Recovery;

    companion object {
        fun resolve(
            storageReady: Boolean,
            storageLoading: Boolean,
            hasSources: Boolean,
            busy: Boolean,
            autoStartPending: Boolean,
            waitingForPin: Boolean,
            hasFailure: Boolean
        ): IptvTvEntryState = when {
            // A completed failure must not be masked indefinitely by an unconsumed automatic-entry intent.
            hasFailure -> Recovery
            storageLoading -> Loading
            !storageReady -> Recovery
            busy || waitingForPin -> Loading
            // Only successfully opened empty storage is evidence that a new source is needed.
            !hasSources -> AddSource
            autoStartPending -> Loading
            // Includes a cancelled PIN or a finished attempt that did not produce a playable station.
            else -> Recovery
        }
    }
}
