package com.cinemaplus.rebuilt.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A title the home screen asked to open (Watch Next row); consumed once the active profile is ready. */
data class WatchNextLaunch(val filmId: String, val profileId: String?)

object LaunchRequest {
    var pending by mutableStateOf<WatchNextLaunch?>(null)
}
