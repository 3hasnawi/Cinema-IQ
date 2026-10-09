package com.cinemaplus.rebuilt.data

import org.json.JSONArray
import org.json.JSONObject

/** Film <-> JSON in the same shape the saved lists use, shared so both stay compatible. */
object FilmJson {
    fun encode(f: Film): JSONObject = JSONObject().put("nb", f.id).put("ar_title", f.title)
        .put("imgMediumThumbObjUrl", f.poster).put("imgObjUrl", f.backdrop).put("year", f.year).put("stars", f.rating).put("imdbRating", f.imdbRating).put("originalLanguage", f.originalLanguage).put("en_title", f.enTitle).put("trailer", f.trailer)
        .put("originalTitle", f.originalTitle).put("contentQuality", f.contentQuality).put("edition", f.edition).put("seriesId", f.seriesId).put("kind", if (f.series) 2 else 1).put("season", f.season).put("episodeNummer", f.episode)
}

/** The last page shown, stored on disk so the next launch has something to show before the network answers. */
object ShelfSnapshot {
    private const val MAX_SHELVES = 12
    private const val MAX_FILMS = 30

    fun encode(shelves: List<Shelf>): String = JSONArray(shelves.take(MAX_SHELVES).map { shelf ->
        JSONObject().put("id", shelf.id).put("title", shelf.title)
            .put("films", JSONArray(shelf.films.take(MAX_FILMS).map(FilmJson::encode)))
    }).toString()

    /** Null when the text is damaged; an empty list when it is valid but empty. */
    fun decode(raw: String?): List<Shelf>? = runCatching {
        val array = JSONArray(raw ?: return null)
        (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val films = JsonMapper.films(item.optJSONArray("films") ?: JSONArray())
            if (films.isEmpty()) null else Shelf(item.getString("id"), item.optString("title"), films)
        }
    }.getOrNull()
}
