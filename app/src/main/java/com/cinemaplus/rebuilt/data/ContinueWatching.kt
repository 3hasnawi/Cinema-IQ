package com.cinemaplus.rebuilt.data

/** Selects the following episode across season boundaries without crossing shows. */
object ContinueWatching {
    fun isLater(candidate: Film, checkpoint: Film): Boolean =
        candidate.season > checkpoint.season ||
            (candidate.season == checkpoint.season && candidate.episode > checkpoint.episode)

    fun replace(recent: List<Film>, completed: Film, next: Film?, episodeIds: Set<String>): List<Film> {
        val remaining = recent.filterNot {
            it.id == completed.id || it.id in episodeIds ||
                (completed.seriesId.isNotBlank() && it.seriesId == completed.seriesId)
        }
        return ((if (next == null) emptyList() else listOf(next)) + remaining).take(30)
    }

    fun canOffer(checkpoint: Film, next: Film, recent: List<Film>, playing: Film?): Boolean {
        if (checkpoint.seriesId.isBlank() || next.seriesId != checkpoint.seriesId || next.id == checkpoint.id) return false
        if (playing?.seriesId == checkpoint.seriesId) return false
        return recent.none { it.seriesId == checkpoint.seriesId || it.id == next.id }
    }

    fun next(seasons: List<Season>, currentId: String): Film? {
        val ordered = seasons.sortedBy { it.number }.flatMap { season ->
            season.episodes.sortedBy { it.episode }.map { it.copy(season = season.number) }
        }
        val index = ordered.indexOfFirst { it.id == currentId }
        return if (index >= 0) ordered.getOrNull(index + 1) else null
    }

    /**
     * The episode a Play press on a series should start: the one in progress, otherwise the first one after the
     * last finished episode, otherwise the very first. When everything is finished it starts again from the top.
     */
    fun resume(seasons: List<Season>, completed: (String) -> Boolean, progress: (String) -> Long): Film? {
        val ordered = seasons.sortedBy { it.number }.flatMap { season ->
            season.episodes.sortedBy { it.episode }.map { it.copy(season = season.number) }
        }
        if (ordered.isEmpty()) return null
        ordered.lastOrNull { progress(it.id) > 0 && !completed(it.id) }?.let { return it }
        val lastFinished = ordered.indexOfLast { completed(it.id) }
        return if (lastFinished < 0) ordered.first() else ordered.getOrNull(lastFinished + 1) ?: ordered.first()
    }

    /** Continue-watching list without this title; removing an episode removes its whole series. */
    fun remove(recent: List<Film>, film: Film): List<Film> = recent.filterNot { sameEntry(it, film) }

    /** Followed-series checkpoints without this title's series, so a removed show is not offered again. */
    fun unfollow(followed: List<Film>, film: Film): List<Film> =
        if (film.seriesId.isBlank()) followed else followed.filterNot { it.seriesId == film.seriesId }

    private fun sameEntry(entry: Film, film: Film) =
        entry.id == film.id || (film.seriesId.isNotBlank() && entry.seriesId == film.seriesId)
}
