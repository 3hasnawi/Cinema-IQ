@file:Suppress("UnsafeOptInUsageError")
package com.cinemaplus.rebuilt.ui.iptv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.HttpDataSource
import com.cinemaplus.rebuilt.data.iptv.IptvLivePlaybackPolicy
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal fun iptvLiveTrackSelector(context: Context) = DefaultTrackSelector(context).apply {
    setParameters(buildUponParameters().setExceedRendererCapabilitiesIfNecessary(false))
}

/** Fast channel entry; repeated starvation earns a larger resume buffer without replacing the decoder. */
internal class IptvTvLiveLoadControl(
    private val targetBytes: Int = IptvLivePlaybackPolicy.targetBufferBytes(Runtime.getRuntime().maxMemory())
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    IptvLivePlaybackPolicy.MIN_BUFFER_MS, DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
    IptvLivePlaybackPolicy.MAX_BUFFER_MS, DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
    IptvLivePlaybackPolicy.START_BUFFER_MS, DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
    IptvLivePlaybackPolicy.BASE_REBUFFER_MS, DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS,
    targetBytes, false, DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK,
    0, false, emptyMap()
) {
    private val disruptions = AtomicInteger(0)

    fun noteDisruption() { disruptions.updateAndGet { (it + 1).coerceAtMost(3) } }
    fun resetStability() { disruptions.set(0) }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!super.shouldStartPlayback(parameters)) return false
        if (!parameters.rebuffering) return true
        // The memory limit must still release playback for unusually high-bitrate streams.
        if (getAllocator(parameters.playerId).totalBytesAllocated >= targetBytes) return true
        val buffered = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return buffered >= IptvLivePlaybackPolicy.startThresholdUs(true, disruptions.get(), parameters.targetLiveOffsetUs)
    }
}

/** Let the app handle authorization and Retry-After once, without hidden loader retries. */
internal class IptvTvLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    private fun guardedStatus(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .any { it.responseCode == 401 || it.responseCode == 403 || it.responseCode == 429 }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (guardedStatus(loadErrorInfo.exception)) return C.TIME_UNSET
        val delay = super.getRetryDelayMsFor(loadErrorInfo)
        return if (delay == C.TIME_UNSET) delay else maxOf(1_000, delay)
    }

    override fun isEligibleForFallback(exception: IOException): Boolean =
        !guardedStatus(exception) && super.isEligibleForFallback(exception)
}
