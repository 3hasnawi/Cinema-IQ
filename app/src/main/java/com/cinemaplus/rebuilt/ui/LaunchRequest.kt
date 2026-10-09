package com.cinemaplus.rebuilt.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A title the home screen asked to open (Watch Next row); consumed once the active profile is ready. */
object LaunchRequest {
    var filmId by mutableStateOf<String?>(null)
}
