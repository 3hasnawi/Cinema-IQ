package com.cinemaplus.rebuilt.ui

import com.cinemaplus.rebuilt.data.ProfileList
import kotlin.math.floor

/** Shared dimensions keep column calculation consistent with the actual profile tiles. */
internal object ProfileGridLayout {
    const val TileWidth = 144f
    const val Gap = 24f
    const val EdgePadding = 24f

    fun columns(screenWidth: Float): Int {
        val available = (screenWidth - 2 * EdgePadding).coerceAtLeast(0f)
        return floor((available + Gap) / (TileWidth + Gap)).toInt().coerceIn(1, ProfileList.MAX)
    }
}
