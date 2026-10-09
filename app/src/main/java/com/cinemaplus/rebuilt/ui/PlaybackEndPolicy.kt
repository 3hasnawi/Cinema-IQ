package com.cinemaplus.rebuilt.ui

import androidx.media3.common.Player

/** End behavior shared by the touch and remote players. */
object PlaybackEndPolicy {
    /** Without credits time from the source the prompt shows for the last 15 seconds. */
    const val FALLBACK_WINDOW_MS = 15_000L

    fun offerNextEpisode(position: Long, duration: Long, hasNext: Boolean, creditsStartMs: Long = 0): Boolean {
        if (!hasNext || duration <= 0 || position < 0 || position > duration) return false
        // A credits time is only trusted when it falls in the second half of the episode.
        if (creditsStartMs > duration / 2 && creditsStartMs < duration) return position >= creditsStartMs
        return duration - position <= minOf(FALLBACK_WINDOW_MS, duration / 4)
    }

    /** Where "skip intro" jumps to, or null when the source gave no intro or playback is outside it. */
    fun skipIntroTarget(position: Long, duration: Long, markers: com.cinemaplus.rebuilt.data.SkipMarkers): Long? {
        val end = markers.introEndMs
        if (end <= 0 || position < 0 || (duration > 0 && end >= duration)) return null
        return end.takeIf { position >= markers.introStartMs && position < end - 1_000 }
    }

    fun toggle(player: Player) {
        when {
            player.playbackState == Player.STATE_ENDED -> Unit
            player.playWhenReady -> player.pause()
            player.currentMediaItem != null -> player.play()
        }
    }
}
