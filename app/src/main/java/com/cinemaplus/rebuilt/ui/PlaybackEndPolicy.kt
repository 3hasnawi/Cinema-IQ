package com.cinemaplus.rebuilt.ui

import androidx.media3.common.Player

/** End behavior shared by the touch and remote players. */
object PlaybackEndPolicy {
    fun offerNextEpisode(position: Long, duration: Long, hasNext: Boolean): Boolean {
        if (!hasNext || duration <= 0 || position < 0) return false
        val window = minOf(90_000L, duration / 10)
        return duration - position <= window && position <= duration
    }

    fun toggle(player: Player) {
        when {
            player.playbackState == Player.STATE_ENDED -> Unit
            player.playWhenReady -> player.pause()
            player.currentMediaItem != null -> player.play()
        }
    }
}
