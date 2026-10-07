package com.cinemaplus.rebuilt.ui.mobile

import com.cinemaplus.rebuilt.data.Stream

/** Resolution preferences apply once per title; manual quality choices retain their position. */
object MobilePlaybackPolicy {
    fun preferredIndex(streams: List<Stream>, quality: String): Int = streams.indexOfFirst {
        it.resolution.removeSuffix("p").equals(quality.removeSuffix("p"), ignoreCase = true)
    }.coerceAtLeast(0)
    fun resumePosition(sameMedia: Boolean, currentPosition: Long, savedPosition: Long): Long =
        (if (sameMedia) currentPosition else savedPosition).coerceAtLeast(0)
}
