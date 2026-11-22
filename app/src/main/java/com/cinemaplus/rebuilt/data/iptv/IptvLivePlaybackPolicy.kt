package com.cinemaplus.rebuilt.data.iptv

/** Fast first playback, with a bounded recovery buffer after repeated disruptions. */
object IptvLivePlaybackPolicy {
    const val MIN_BUFFER_MS = 8_000
    const val MAX_BUFFER_MS = 30_000
    const val START_BUFFER_MS = 750
    const val BASE_REBUFFER_MS = 2_000
    private const val MIB = 1024 * 1024

    // Reserve more compressed media for UHD while leaving most of the heap to the app and decoder.
    fun targetBufferBytes(heapBytes: Long): Int =
        (heapBytes / 8).coerceIn(8L * MIB, 96L * MIB).toInt()

    fun rebufferMs(disruptions: Int): Int = when {
        disruptions <= 0 -> BASE_REBUFFER_MS
        disruptions == 1 -> 3_000
        disruptions == 2 -> 4_000
        else -> 6_000
    }

    fun startThresholdUs(rebuffering: Boolean, disruptions: Int, targetLiveOffsetUs: Long): Long {
        val thresholdUs = (if (rebuffering) rebufferMs(disruptions) else START_BUFFER_MS).toLong() * 1_000
        // Negative Media3 sentinels mean unknown. A zero-length live target must not block startup.
        return if (targetLiveOffsetUs >= 0) minOf(thresholdUs, targetLiveOffsetUs / 2) else thresholdUs
    }
}

/** Main-thread sampler for READY playback whose position has stopped advancing. */
class IptvLiveStallWatchdog {
    private var channelKey: String? = null
    private var lastSampleMs = 0L
    private var lastPositionMs = 0L
    private var progressTimeMs = 0L
    private var progressPositionMs = 0L
    private var fired = false

    fun reset() {
        channelKey = null
        fired = false
    }

    fun sample(
        key: String,
        nowMs: Long,
        positionMs: Long,
        ready: Boolean,
        shouldPlay: Boolean,
        suppressed: Boolean = false,
    ): Boolean {
        if (!ready || !shouldPlay || suppressed || nowMs < 0 || positionMs < 0) {
            reset()
            return false
        }
        if (channelKey != key || nowMs < lastSampleMs || positionMs < lastPositionMs) {
            channelKey = key
            lastSampleMs = nowMs
            lastPositionMs = positionMs
            progressTimeMs = nowMs
            progressPositionMs = positionMs
            fired = false
            return false
        }
        lastSampleMs = nowMs
        lastPositionMs = positionMs
        // Accumulate small position increments rather than requiring 100 ms in one sample.
        if (positionMs - progressPositionMs >= 100) {
            progressPositionMs = positionMs
            progressTimeMs = nowMs
            fired = false
            return false
        }
        if (fired || nowMs - progressTimeMs < 15_000) return false
        fired = true
        return true
    }
}
