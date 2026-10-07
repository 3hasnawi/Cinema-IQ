package com.cinemaplus.rebuilt.ui.mobile

import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.Shelf
import kotlin.math.abs

/** Prefer real genre overlap, then rating/year proximity within the same content format. */
fun phoneRelatedFilms(film: Film, shelves: List<Shelf>): List<Film> {
    val categories = shelves.filter { it.id.startsWith("genre-") && it.films.any { other -> other.id == film.id } }
    val categoryPeers = categories.flatMap { it.films }.map { it.id }.toSet()
    fun overlap(other: Film) = film.genreIds.intersect(other.genreIds).size + if (other.id in categoryPeers) 1 else 0
    val candidates = shelves.flatMap { it.films }.distinctBy { it.id }
        .filter { it.id != film.id && it.series == film.series && it.episode == 0 && it.seriesId.isBlank() }
    // Known unrelated genres are never labelled as similar; missing metadata may serve as a fallback.
    val matches = candidates.filter { overlap(it) > 0 }
    val pool = matches.ifEmpty { candidates.filter { film.genreIds.isEmpty() || it.genreIds.isEmpty() } }
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
