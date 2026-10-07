package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Artwork and synopsis for one title as published by TMDB. Any field may be blank. */
data class ArtworkMatch(val backdrop: String, val poster: String, val overview: String, val rating: String, val originalTitle: String = "")

/**
 * Looks titles up on TMDB (themoviedb.org) to replace weak source artwork with sharp backdrops and posters, and
 * to fill a missing synopsis. A title is only accepted when its name (and year, when known) really match, so a
 * wrong film is never shown; otherwise the source data stays. Results, including "no match", are cached on disk.
 * With a blank API key nothing is requested and [enabled] is false.
 */
class TmdbMetadata(private val apiKey: String, private val cacheFile: File? = null) {
    val enabled: Boolean get() = apiKey.isNotBlank()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private class Entry(val at: Long, val match: ArtworkMatch?)
    private val known = HashMap<String, Entry>()
    private var loaded = false

    /** Returns the match, null when TMDB has no reliable match. Network failures throw and are not cached. */
    suspend fun lookup(film: Film): ArtworkMatch? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        synchronized(known) { load(); known[film.id] }?.takeIf(::fresh)?.let { return@withContext it.match }
        val match = fetch(film)
        synchronized(known) { known[film.id] = Entry(System.currentTimeMillis(), match); save() }
        match
    }

    private fun fresh(entry: Entry): Boolean =
        System.currentTimeMillis() - entry.at < if (entry.match != null) FoundTtl else MissingTtl

    private fun fetch(film: Film): ArtworkMatch? {
        val year = film.year.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }
        val titles = listOf(film.enTitle, film.title).filter(String::isNotBlank).distinct()
        for (title in titles) for (withYear in listOf(true, false)) {
            if (withYear && year == null) continue
            val url = HttpUrl.Builder().scheme("https").host("api.themoviedb.org")
                .addPathSegments(if (film.series) "3/search/tv" else "3/search/movie")
                .addQueryParameter("api_key", apiKey).addQueryParameter("query", title)
                .addQueryParameter("language", "ar").addQueryParameter("include_adult", "false")
                .apply { if (withYear) addQueryParameter(if (film.series) "first_air_date_year" else "year", year) }.build()
            pick(results(url), title, year)?.let { return toMatch(it) }
        }
        return null
    }

    private fun results(url: HttpUrl): JSONArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TMDB ${response.code}")
            JSONObject(response.body?.string() ?: throw IOException("TMDB empty")).optJSONArray("results") ?: JSONArray()
        }

    private fun load() {
        if (loaded) return
        loaded = true
        val file = cacheFile?.takeIf { it.exists() } ?: return
        runCatching {
            val root = JSONObject(file.readText())
            root.keys().forEach { id ->
                val o = root.getJSONObject(id)
                known[id] = Entry(if (!o.optBoolean("none") && !o.has("original")) 0L else o.optLong("t"), if (o.optBoolean("none")) null
                    else ArtworkMatch(o.optString("b"), o.optString("p"), o.optString("o"), o.optString("r"), o.optString("original")))
            }
        }
    }

    private fun save() {
        val file = cacheFile ?: return
        runCatching {
            val root = JSONObject()
            known.forEach { (id, entry) ->
                root.put(id, JSONObject().put("t", entry.at).apply {
                    val m = entry.match
                    if (m == null) put("none", true) else put("b", m.backdrop).put("p", m.poster).put("o", m.overview).put("r", m.rating).put("original", m.originalTitle)
                })
            }
            file.writeText(root.toString())
        }
    }

    companion object {
        private const val FoundTtl = 30L * 24 * 60 * 60 * 1000
        private const val MissingTtl = 3L * 24 * 60 * 60 * 1000
        private const val ImageBase = "https://image.tmdb.org/t/p/"

        /** Lower-case letters and digits only, so punctuation and spacing differences do not matter. */
        fun normalize(text: String): String = text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

        /** The first result whose title matches and whose year is within one year of [year]; null when none does. */
        fun pick(results: JSONArray, title: String, year: String?): JSONObject? {
            val wanted = normalize(title)
            if (wanted.isEmpty()) return null
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                if (JsonMapper.text(r, "backdrop_path").isBlank() && JsonMapper.text(r, "poster_path").isBlank()) continue
                val resultYear = JsonMapper.text(r, "release_date", "first_air_date").take(4).toIntOrNull()
                val wantedYear = year?.toIntOrNull()
                val yearOk = wantedYear == null || resultYear == null || kotlin.math.abs(resultYear - wantedYear) <= 1
                if (!yearOk) continue
                val names = listOf(JsonMapper.text(r, "title", "name"), JsonMapper.text(r, "original_title", "original_name"))
                    .map(::normalize).filter(String::isNotEmpty)
                val exact = names.any { it == wanted }
                // Containment is only trusted for longer titles and only when a year confirmed it.
                val loose = wantedYear != null && resultYear != null && wanted.length >= 6 &&
                    names.any { it.contains(wanted) || wanted.contains(it) && it.length >= 6 }
                if (exact || loose) return r
            }
            return null
        }

        fun toMatch(r: JSONObject): ArtworkMatch {
            val backdrop = JsonMapper.text(r, "backdrop_path")
            val poster = JsonMapper.text(r, "poster_path")
            val votes = r.optInt("vote_count", 0)
            val average = r.optDouble("vote_average", 0.0)
            return ArtworkMatch(
                backdrop = if (backdrop.isBlank()) "" else ImageBase + "w1280" + backdrop,
                poster = if (poster.isBlank()) "" else ImageBase + "w780" + poster,
                overview = JsonMapper.text(r, "overview"),
                originalTitle = JsonMapper.text(r, "original_title", "original_name"),
                rating = if (votes >= 10 && average > 0) String.format(Locale.US, "%.1f", average) else "")
        }
    }
}
