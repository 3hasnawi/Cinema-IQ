package com.cinemaplus.rebuilt.data

import kotlin.math.abs

/** Require actual genre similarity and compatible original language within the same format. */
fun relatedFilms(film: Film, shelves: List<Shelf>): List<Film> {
    val categories = shelves.filter { it.id.startsWith("genre-") && it.films.any { other -> other.id == film.id } }
    val categoryPeers = categories.flatMap { it.films }.map { it.id }.toSet()
    fun overlap(other: Film) = film.genreIds.intersect(other.genreIds).size + if (other.id in categoryPeers) 1 else 0
    val candidates = shelves.flatMap { it.films }.distinctBy { it.id }
        .filter { it.id != film.id && it.series == film.series && it.episode == 0 && it.seriesId.isBlank() }
    // Missing metadata is not evidence of similarity; do not fill this row with arbitrary titles.
    val pool = candidates.filter { overlap(it) > 0 &&
        (film.originalLanguage.isBlank() || it.originalLanguage.equals(film.originalLanguage, ignoreCase = true)) }
    fun ratingDistance(other: Film): Double {
        val original = film.rating.toDoubleOrNull() ?: return Double.MAX_VALUE
        return other.rating.toDoubleOrNull()?.let { abs(it - original) } ?: Double.MAX_VALUE
    }
    fun yearDistance(other: Film): Int {
        val original = film.year.toIntOrNull() ?: return Int.MAX_VALUE
        return other.year.toIntOrNull()?.let { abs(it - original) } ?: Int.MAX_VALUE
    }
    return pool.sortedWith(compareByDescending<Film> { overlap(it) }
        .thenBy { ratingDistance(it) }.thenBy { yearDistance(it) }.thenBy { it.id }).take(24)
}
