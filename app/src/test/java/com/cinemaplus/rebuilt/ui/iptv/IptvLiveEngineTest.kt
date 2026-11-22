@file:Suppress("UnsafeOptInUsageError")
package com.cinemaplus.rebuilt.ui.iptv

import android.net.Uri
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackGroup
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class IptvLiveEngineTest {
    private val uri = Uri.parse("https://media.example/live.m3u8")
    private val timeline = SinglePeriodTimeline(60_000_000L, true, true, true, null, MediaItem.fromUri(uri))
    private val period = MediaPeriodId(timeline.getUidOfPeriod(0))

    @Test fun hlsSelectsUhdThenAdaptsDownAndBackUpWithoutSelectingUnsupported8k() {
        val playlist = """#EXTM3U
            |#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS="hvc1.1.6.L93.B0"
            |720.m3u8
            |#EXT-X-STREAM-INF:BANDWIDTH=16000000,RESOLUTION=3840x2160,CODECS="hvc1.1.6.L153.B0"
            |2160.m3u8
            |#EXT-X-STREAM-INF:BANDWIDTH=48000000,RESOLUTION=7680x4320,CODECS="hvc1.1.6.L183.B0"
            |4320.m3u8
        """.trimMargin()
        val parsed = HlsPlaylistParser().parse(uri, playlist.byteInputStream()) as HlsMultivariantPlaylist
        val group = TrackGroup("video", *parsed.variants.map { it.format }.toTypedArray())
        val meter = TestBandwidth()
        val selector = iptvLiveTrackSelector(RuntimeEnvironment.getApplication())
        // Give the selector an 8K viewport and a 4K decoder, independently of the test display.
        selector.setParameters(selector.buildUponParameters().setViewportSize(7680, 4320, false))
        selector.init({ selector.onParametersActivated(it) }, meter)
        try {
            val selection = selector.selectTracks(arrayOf(VideoRenderer), TrackGroupArray(group), period, timeline).selections.single()!!
            assertEquals(2, selection.length())
            selection.enable()
            fun resolution(bitrate: Long, bufferUs: Long): Int {
                meter.estimate = bitrate
                selection.updateSelectedTrack(0L, bufferUs, C.TIME_UNSET, emptyList(), Array(selection.length()) { MediaChunkIterator.EMPTY })
                return selection.selectedFormat.height
            }
            assertEquals(2160, resolution(100_000_000, 15_000_000))
            assertEquals(720, resolution(4_000_000, 1_000_000))
            assertEquals(2160, resolution(100_000_000, 20_000_000))
            selection.disable()
        } finally { selector.release() }
    }

    @Test fun liveLoadControlUsesFastStartAndADeeperBufferAfterStarvation() {
        val control = IptvTvLiveLoadControl()
        val player = PlayerId("test-live")
        control.onPrepared(player)
        try {
            fun ready(bufferUs: Long, rebuffer: Boolean) = control.shouldStartPlayback(
                LoadControl.Parameters(player, timeline, period, 0, bufferUs, 1f, true, rebuffer, C.TIME_UNSET, C.TIME_UNSET)
            )
            assertFalse(ready(749_000, false))
            assertTrue(ready(750_000, false))
            repeat(3) { control.noteDisruption() }
            assertFalse(ready(5_999_000, true))
            assertTrue(ready(6_000_000, true))
            control.resetStability()
            assertTrue(ready(2_000_000, true))
        } finally { control.onReleased(player) }
    }

    private object VideoRenderer : RendererCapabilities {
        override fun getName() = "Test UHD decoder"
        override fun getTrackType() = C.TRACK_TYPE_VIDEO
        override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
            if (format.height <= 2160) C.FORMAT_HANDLED else C.FORMAT_EXCEEDS_CAPABILITIES,
            RendererCapabilities.ADAPTIVE_SEAMLESS, RendererCapabilities.TUNNELING_NOT_SUPPORTED)
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_SEAMLESS
    }

    private class TestBandwidth : BandwidthMeter {
        var estimate = 100_000_000L
        override fun getBitrateEstimate() = estimate
        override fun getTransferListener(): TransferListener? = null
        override fun addEventListener(handler: Handler, listener: BandwidthMeter.EventListener) = Unit
        override fun removeEventListener(listener: BandwidthMeter.EventListener) = Unit
    }
}
