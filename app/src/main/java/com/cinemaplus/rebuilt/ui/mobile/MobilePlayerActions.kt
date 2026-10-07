package com.cinemaplus.rebuilt.ui.mobile

import androidx.media3.common.Player

/** Transport actions shared by the phone buttons and scrubber. */
object MobilePlayerActions {
    fun canSeek(player: Player): Boolean = player.isCurrentMediaItemSeekable &&
        player.duration > 0 && player.playbackState == Player.STATE_READY

    fun toggle(player: Player) {
        when {
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
            player.playWhenReady -> player.pause()
            player.currentMediaItem != null -> player.play()
        }
    }
}
