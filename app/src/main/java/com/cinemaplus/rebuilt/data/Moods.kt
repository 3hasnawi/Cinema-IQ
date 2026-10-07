package com.cinemaplus.rebuilt.data

import java.util.Locale

/** A way to browse by feeling instead of by catalogue category. */
data class Mood(val id: String, val title: String, val subtitle: String, val color: Long, val keywords: List<String>)

/**
 * Moods are matched against the source's own category titles (for example "رعب" or "كوميدي"), so only
 * categories the source really has are ever shown.
 */
object Moods {
    val all = listOf(
        Mood("crime", "جريمة وغموض", "من فعل ماذا وبأي ثمن", 0xFF7A2E3E, listOf("جريم", "غموض", "لغز", "بوليس")),
        Mood("laugh", "ضحك", "كوميديا خفيفة تغيّر المزاج", 0xFF8A5A1E, listOf("كوميد", "ضحك")),
        Mood("fear", "رعب وتشويق", "خوف ينبع من الأجواء", 0xFF4A2A6A, listOf("رعب", "اثار", "تشويق")),
        Mood("space", "خيال وفضاء", "عوالم أبعد من المألوف", 0xFF2A4E7A, listOf("خيال", "فضاء", "فانتاز", "فنتاز")),
        Mood("love", "حب وعلاقات", "قلوب تتقاطع وتتشابك", 0xFF8A2E5A, listOf("رومانس", "حب", "عاطف")),
        Mood("action", "حركة ومغامرة", "إيقاع سريع وأنفاس محبوسة", 0xFF8A3A1A, listOf("اكشن", "حركه", "مغامر")),
        Mood("family", "للعائلة", "مناسب لجلسة الجميع", 0xFF2A6A5A, listOf("عائل", "رسوم", "انمي", "اطفال")),
        Mood("real", "وقائع وتاريخ", "قصص من الواقع والزمن", 0xFF5A5A2A, listOf("وثائق", "تاريخ", "حرب", "سير", "دراما"))
    )

    /** Lower-case, alef/ya/ta-marbuta variants unified, punctuation and spacing dropped. */
    fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
        .filter(Char::isLetterOrDigit)

    fun matches(mood: Mood, categoryTitle: String): Boolean {
        val title = normalize(categoryTitle)
        return mood.keywords.any { title.contains(normalize(it)) }
    }

    /** Movies and series of every category that fits the mood, merged and de-duplicated; empty shelves dropped. */
    fun shelves(mood: Mood, source: List<Shelf>): List<Shelf> {
        val hits = source.filter { it.id.startsWith("genre-") && matches(mood, it.title) }
        fun merged(prefix: String, id: String, title: String) =
            Shelf(id, title, hits.filter { it.id.startsWith(prefix) }.flatMap { it.films }.distinctBy { it.id })
        return listOf(merged("genre-m-", "explore-movies", "أفلام"), merged("genre-s-", "explore-series", "مسلسلات"))
            .filter { it.films.isNotEmpty() }
    }
}
