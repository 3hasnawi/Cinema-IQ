package com.cinemaplus.rebuilt.data

/** Decides which continue-watching titles belong in the Android TV home "Watch Next" row. Pure, so it is unit tested. */
object WatchNextPlan {
    const val LIMIT = 10

    /** One row entry per series (or per movie), so an episode replaces the previous one of the same show. */
    fun key(film: Film) = if (film.seriesId.isNotBlank()) "series:${film.seriesId}" else film.id

    class Plan(val upsert: List<Film>, val deleteKeys: Set<String>)

    fun plan(recent: List<Film>, existingKeys: Set<String>): Plan {
        val wanted = recent.distinctBy(::key).take(LIMIT)
        return Plan(wanted, existingKeys - wanted.map(::key).toSet())
    }
}
