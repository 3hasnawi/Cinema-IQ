package com.cinemaplus.rebuilt.ui.mobile

import androidx.media3.common.Player

/** Transport actions shared by the phone buttons and scrubber. */
object MobilePlayerActions {
    fun canSeek(player: Player): Boolean = player.isCurrentMediaItemSeekable &&
        player.duration > 0 && player.playbackState == Player.STATE_READY

    /** Sources have no credit markers; use a bounded end window rather than claim credit detection. */
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
