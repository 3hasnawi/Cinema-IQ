package com.cinemaplus.rebuilt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Film

/** Non-focusable pause information; the existing player retains all remote navigation. */
@Composable
internal fun TvPausedInfo(vm: CinemaViewModel, film: Film, durationMs: Long, modifier: Modifier = Modifier) {
    // Existing source lookup is cached and cancellation follows this overlay's lifecycle.
    val description by produceState(film.description, film.id) {
        value = vm.describe(film)
    }
    val details = vm.withArtwork(film)
    val title = if (film.episode > 0) {
        listOf("الموسم ${film.season} · الحلقة ${film.episode}", film.episodeTitle)
            .filter(String::isNotBlank).joinToString(" — ")
    } else mediaTitle(film)
    val runtime = formatContentDuration((durationMs / 1000).toString())
        .ifBlank { formatContentDuration(film.duration) }
    val facts = listOf(film.year, runtime, film.contentQuality, film.edition)
        .filter(String::isNotBlank).distinct().joinToString(" · ")
    Column(modifier, horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(10.mx)) {
        Label(title, 19, Brand.TextPrimary, true, Modifier.fillMaxWidth(), lines = 2)
        if (facts.isNotBlank()) Label(facts, 12, Brand.TextSecondary, modifier = Modifier.fillMaxWidth(), lines = 1)
        val overview = description.ifBlank { details.description }
        if (overview.isNotBlank()) Label(overview, 13, Brand.TextSecondary,
            modifier = Modifier.fillMaxWidth(), lines = 3)
    }
}
