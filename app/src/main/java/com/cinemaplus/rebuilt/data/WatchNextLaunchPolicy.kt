package com.cinemaplus.rebuilt.data

/** Do not start another profile's title or silently replace an obsolete episode. */
object WatchNextLaunchPolicy {
    sealed interface Result {
        data class Play(val film: Film) : Result
        data object WrongProfile : Result
        data object Missing : Result
    }

    fun resolve(filmId: String, requestedProfile: String?, activeProfile: String, recent: List<Film>): Result {
        if(!requestedProfile.isNullOrBlank() && requestedProfile != activeProfile) return Result.WrongProfile
        return recent.firstOrNull { it.id == filmId }?.let(Result::Play) ?: Result.Missing
    }
}
