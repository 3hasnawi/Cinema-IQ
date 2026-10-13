package com.cinemaplus.rebuilt.data

import org.json.JSONArray
import org.json.JSONObject

/** Completed works persist independently of saved titles and continue watching. */
object WatchedHistory {
    const val LIMIT = 500
    fun add(raw: String?, film: Film, at: Long): String {
        val old = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        val entry = FilmJson.encode(film).put("watchedAt", at)
        val others = (0 until old.length()).mapNotNull { old.optJSONObject(it) }.filter { it.optString("nb") != film.id }
        return JSONArray((listOf(entry) + others).take(LIMIT)).toString()
    }
}

enum class LibrarySection(val label: String, val description: String) {
    Saved("للمشاهدة لاحقاً", "أعمال أضفتها لتشاهدها لاحقاً"),
    Watched("سجل المشاهدة", "الأفلام والحلقات التي أكملت مشاهدتها")
}
