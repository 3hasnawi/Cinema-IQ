package com.cinemaplus.rebuilt.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.net.URLDecoder

data class Film(
    val id: String, val title: String, val poster: String = "", val backdrop: String = "",
    val description: String = "", val year: String = "", val duration: String = "",
    val rating: String = "", val series: Boolean = false, val season: Int = 1, val episode: Int = 0,
    val subtitles: List<Subtitle> = emptyList(),
    // Optional extras: English title (better for trailer search) and a trailer link/id if the source provides one.
    val enTitle: String = "", val trailer: String = "", val seriesId: String = "",
    val originalTitle: String = "", val contentQuality: String = "", val edition: String = "",
    val genreIds: Set<Int> = emptySet()
)
data class Subtitle(val url: String, val language: String = "und", val title: String = "ترجمة", val mimeType: String = "text/vtt")
data class Stream(val url: String, val resolution: String)
data class Shelf(val id: String, val title: String, val films: List<Film>)
data class Season(val number: Int, val episodes: List<Film>)
/** Content category (genre) as listed by the source. */
data class Category(val id: String, val title: String)

object JsonMapper {
    fun text(o: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull {
        o.opt(it)?.takeUnless { v -> v == JSONObject.NULL || v is JSONObject || v is JSONArray }
            ?.toString()?.takeIf(String::isNotBlank)
    }.orEmpty()
    fun image(raw: String): String = when {
        raw.startsWith("file:///android_asset/") -> raw
        raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.isBlank() || raw.startsWith("http://") -> ""
        else -> "https://cnth2.shabakaty.cc/" + raw.trimStart('/')
    }
    fun film(o: JSONObject, seriesHint: Boolean = false): Film? {
        val id = text(o, "nb", "videoId", "videoID", "id")
        if (id.isBlank()) return null
        val title = text(o, "ar_title", "en_title", "title", "name").ifBlank { "عمل $id" }
        return Film(id, title,
            image(text(o, "imgMediumThumbObjUrl", "imgThumbObjUrl", "imgObjUrl", "poster")),
            image(text(o, "imgObjUrl", "imgLargeThumbObjUrl", "imgMediumThumbObjUrl")),
            text(o, "ar_content", "ar_description", "description", "content", "en_content"),
            text(o, "year"), text(o, "duration"), rating(o),
            seriesHint || text(o, "kind", "videoKind") == "2", text(o, "season").toIntOrNull() ?: 1,
            text(o, "episodeNummer", "episodeNumber", "episode").toIntOrNull() ?: 0,
            subtitles(o.opt("translations")),
            text(o, "en_title", "title_en", "englishTitle"),
            text(o, "trailer", "trailerUrl", "trailer_url", "youtube", "youtubeUrl", "youtube_url", "youtubeTrailer", "yt"), text(o, "seriesId"), text(o, "original_title", "original_name", "originalTitle"),
            text(o, "videoQuality", "quality", "resolution", "contentQuality"),
            text(o, "videoVersion", "videoEdition", "edition", "releaseType", "release_type"))
    }
    /** First usable rating among the known keys, normalized to a one-decimal 0-10 string; blank when missing or zero. */
    fun rating(o: JSONObject): String {
        for (key in listOf("stars", "rate", "imdbRate", "imdb_rate", "imdbRating", "rating", "tmdbRate")) {
            val value = text(o, key).replace(',', '.').toDoubleOrNull() ?: continue
            val scaled = if (value > 10.0) value / 10.0 else value
            if (scaled > 0.0 && scaled <= 10.0) return String.format(Locale.US, "%.1f", scaled)
        }
        return ""
    }
    fun subtitles(value: Any?): List<Subtitle> = array(value).mapIndexedNotNull { index, t ->
        val url = image(text(t, "file", "url"))
        if(url.isBlank()) return@mapIndexedNotNull null
        val nested = t.optJSONObject("language") ?: t.optJSONObject("lang")
        val values = listOf(text(t, "languageCode", "language_code", "langCode", "locale"),
            nested?.let { text(it, "code", "iso", "name", "ar_name", "en_name") }.orEmpty(),
            text(t, "language", "lang"), text(t, "languageName", "language_name", "language_title", "langName", "lang_name", "ar_name", "en_name", "ar_title", "en_title", "name", "title", "label"))
        fun code(raw: String): String? {
            val s = raw.trim().lowercase(Locale.ROOT)
            return when {
                s in listOf("ar", "ara", "arabic", "العربية", "عربي", "عربية") -> "ar"
                s in listOf("en", "eng", "english", "الإنجليزية", "الانجليزية", "إنجليزي", "انجليزي", "الانكليزية", "الإنكليزية", "انكليزي", "إنكليزي") -> "en"
                s in listOf("fr", "fra", "fre", "french", "الفرنسية") -> "fr"
                s in listOf("tr", "tur", "turkish", "التركية") -> "tr"
                s in listOf("es", "spa", "spanish", "الإسبانية") -> "es"
                s in listOf("de", "deu", "ger", "german", "الألمانية") -> "de"
                s in listOf("ku", "kur", "kurdish", "الكردية") -> "ku"
                Regex("[a-z]{2,3}[-_][a-zA-Z]{2,4}([-_][a-zA-Z0-9]+)*").matches(s) -> s.replace('_', '-')
                Regex("[a-z]{2}").matches(s) && Locale.getISOLanguages().contains(s) -> s
                else -> null
            }
        }
        val filename = runCatching { URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8") }.getOrDefault("")
        val language = values.firstNotNullOfOrNull(::code) ?: filename.split(Regex("[._\\-\\s]+"))
            .firstNotNullOfOrNull(::code) ?: "und"
        val name = when(language.substringBefore('-')) {
            "ar" -> "العربية"; "en" -> "الإنجليزية"; "fr" -> "الفرنسية"; "tr" -> "التركية"
            "es" -> "الإسبانية"; "de" -> "الألمانية"; "ku" -> "الكردية"
            "und" -> values.last().takeIf { it.isNotBlank() && !it.all(Char::isDigit) } ?: "ترجمة ${index + 1}"
            else -> Locale.forLanguageTag(language).getDisplayLanguage(Locale.forLanguageTag("ar")).ifBlank { "ترجمة ${index + 1}" }
        }
        Subtitle(url, language, name)
    }.groupBy { it.title }.values.flatMap { sameName ->
        if(sameName.size == 1) sameName else sameName.mapIndexed { index, sub -> sub.copy(title = "${sub.title} · ${index + 1}") }
    }
    fun array(value: Any?): List<JSONObject> = when(value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is JSONObject -> listOf("data", "result", "videos", "items", "list", "groups", "content").firstNotNullOfOrNull {
            value.opt(it)?.takeIf { v -> v is JSONArray || v is JSONObject }?.let(::array)
        } ?: listOf(value)
        else -> emptyList()
    }
    /** Tolerant parse of the source category list; entries without a usable id (or the "all" id 0) are skipped. */
    fun categories(value: Any?): List<Category> = array(value).mapNotNull { o ->
        val id = text(o, "nb", "id", "categoryNb", "categoryID")
        val title = text(o, "ar_title", "ar_name", "title", "name", "en_title", "en_name")
        if (id.isBlank() || id == "0" || title.isBlank()) null else Category(id, title)
    }.distinctBy(Category::id)
    fun films(value: Any?, series: Boolean = false) = array(value).mapNotNull { film(it, series) }.distinctBy(Film::id)
    fun streams(value: Any?) = array(value).mapNotNull {
        val url = text(it, "videoUrl", "url")
        url.takeIf { u -> u.startsWith("https://") }?.let { u -> Stream(u, text(it, "resolution", "quality").ifBlank { "تلقائي" }) }
    }
    fun seasons(value: Any?): List<Season> {
        // The original client indexes a season-number object. Also accept a flat episode array.
        if (value is JSONObject) {
            val grouped = value.keys().asSequence().mapNotNull { key ->
                key.toIntOrNull()?.let { number -> Season(number, films(value.opt(key))) }
            }.filter { it.episodes.isNotEmpty() }.sortedBy { it.number }.toList()
            if (grouped.isNotEmpty()) return grouped
            for (key in listOf("data", "result", "seasons")) {
                value.opt(key)?.let { nested -> seasons(nested).takeIf(List<Season>::isNotEmpty)?.let { return it } }
            }
        }
        return films(value).groupBy { it.season }.map { (n, f) -> Season(n, f.sortedBy { it.episode }) }.sortedBy { it.number }
    }
}
