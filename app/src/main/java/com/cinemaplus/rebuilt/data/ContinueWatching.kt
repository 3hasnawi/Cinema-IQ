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
}
