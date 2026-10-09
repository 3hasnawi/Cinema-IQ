package com.cinemaplus.rebuilt.data

enum class KindFilter(val label: String) { All("الكل"), Movies("أفلام"), Series("مسلسلات") }
enum class SortMode(val label: String) { Default("الافتراضي"), Rating("الأعلى تقييمًا"), Year("الأحدث"), Title("الاسم") }

/** Type filter and sort order applied to search results and the saved list. Pure, so it is unit tested. */
data class FilmFilter(val kind: KindFilter = KindFilter.All, val sort: SortMode = SortMode.Default) {
    val active: Boolean get() = kind != KindFilter.All || sort != SortMode.Default

    fun nextKind() = copy(kind = KindFilter.entries[(kind.ordinal + 1) % KindFilter.entries.size])
    fun nextSort() = copy(sort = SortMode.entries[(sort.ordinal + 1) % SortMode.entries.size])

    fun apply(films: List<Film>): List<Film> {
        val kept = when (kind) {
            KindFilter.All -> films
            KindFilter.Movies -> films.filterNot { it.series }
            KindFilter.Series -> films.filter { it.series }
        }
        return when (sort) {
            SortMode.Default -> kept
            SortMode.Rating -> kept.sortedByDescending { ratingOf(it) }
            SortMode.Year -> kept.sortedByDescending { yearOf(it) }
            SortMode.Title -> kept.sortedBy { it.title }
        }
    }

    companion object {
        /** Leading number of a rating such as "7.4" or "7.4/10"; unrated titles sort last. */
        fun ratingOf(film: Film): Float =
            Regex("""\d+(\.\d+)?""").find(film.rating)?.value?.toFloatOrNull() ?: -1f

        /** First four-digit year in the text, or 0 when the year is unknown. */
        fun yearOf(film: Film): Int =
            Regex("""\d{4}""").find(film.year)?.value?.toIntOrNull() ?: 0
    }
}

/** Recent searches: newest first, no duplicates, short list. */
object SearchHistory {
    const val LIMIT = 8

    fun add(history: List<String>, query: String): List<String> {
        val text = query.trim()
        if (text.length < 2) return history
        return (listOf(text) + history.filterNot { it.equals(text, ignoreCase = true) }).take(LIMIT)
    }

    fun remove(history: List<String>, query: String): List<String> = history.filterNot { it == query }
}
