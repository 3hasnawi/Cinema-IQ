package com.cinemaplus.rebuilt.data

import java.util.Locale

/** Only hide a repeated parent title; the separate S/E label never depends on this result. */
object EpisodeLabels {
    fun title(title: String, seriesTitles: Set<String>): String {
        fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        val candidate = normalized(title)
        return title.trim().takeUnless { candidate.isBlank() || seriesTitles.any { normalized(it) == candidate } }.orEmpty()
    }
}
