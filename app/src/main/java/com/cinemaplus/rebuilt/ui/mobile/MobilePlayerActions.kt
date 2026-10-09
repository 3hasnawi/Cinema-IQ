package com.cinemaplus.rebuilt.ui.mobile

import androidx.media3.common.Player

/** Transport actions shared by the phone buttons and scrubber. */
object MobilePlayerActions {
    fun canSeek(player: Player): Boolean = player.isCurrentMediaItemSeekable &&
        player.duration > 0 && player.playbackState == Player.STATE_READY

    fun offerNextEpisode(position: Long, duration: Long, hasNext: Boolean, creditsStartMs: Long = 0): Boolean =
        com.cinemaplus.rebuilt.ui.PlaybackEndPolicy.offerNextEpisode(position, duration, hasNext, creditsStartMs)

    fun toggle(player: Player) = com.cinemaplus.rebuilt.ui.PlaybackEndPolicy.toggle(player)
}
