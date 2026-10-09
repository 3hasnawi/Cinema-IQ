package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
data class ArtworkMatch(val backdrop: String, val poster: String, val overview: String, val rating: String, val originalTitle: String = "", val genreIds: Set<Int> = emptySet(), val tmdbId: Int = 0, val originalLanguage: String = "")

data class CastMember(val id: Int, val name: String, val image: String)
data class WorkCredits(val cast: List<CastMember>, val directors: List<String>, val creators: List<String>, val year: String)

/**
 * Looks titles up on TMDB (themoviedb.org) to replace weak source artwork with sharp backdrops and posters, and
 * to fill a missing synopsis. A title is only accepted when its name (and year, when known) really match, so a
 * wrong film is never shown; otherwise the source data stays. Results, including "no match", are cached on disk.
 * With a blank API key nothing is requested and [enabled] is false.
 */
class TmdbMetadata(private val apiKey: String, private val cacheFile: File? = null) {
    val enabled: Boolean get() = apiKey.isNotBlank()
    private val client = Http.client(10, 15, 25)
    private class Entry(val at: Long, val match: ArtworkMatch?)
    private val known = HashMap<String, Entry>()
    private var loaded = false
    private val creditsLock = Mutex()
    private val creditsCache = mutableMapOf<String, Pair<Long, JSONObject>>()
    private val creditsFile = cacheFile?.let { File(it.parentFile, "tmdb-credits.json") }
    private var creditsLoaded = false

    /** Cast and directors belong to the exact matched title, not a previous details page. */
    suspend fun credits(film: Film): WorkCredits? {
        val match = lookup(film) ?: return null
        if(match.tmdbId <= 0) return null
        return withContext(Dispatchers.IO) { creditsLock.withLock {
            if(!creditsLoaded) {
                creditsLoaded = true
                runCatching {
                    val root = JSONObject(creditsFile?.takeIf { it.exists() }?.readText() ?: "{}")
                    root.keys().forEach { key -> root.optJSONObject(key)?.let { entry ->
                        entry.optJSONObject("data")?.let { creditsCache[key] = entry.optLong("at") to it }
                    } }
                }
            }
            val key = "${film.series}:${match.tmdbId}"
            val cached = creditsCache[key]?.takeIf { System.currentTimeMillis() - it.first < 7 * 24 * 60 * 60 * 1000L }
            val data = cached?.second ?: run {
                val url = HttpUrl.Builder().scheme("https").host("api.themoviedb.org")
                    .addPathSegments("3/${if(film.series) "tv" else "movie"}/${match.tmdbId}")
                    .addQueryParameter("api_key", apiKey).addQueryParameter("language", "ar")
                    .addQueryParameter("append_to_response", "credits").build()
                val responseData = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if(!response.isSuccessful) throw IOException("TMDB credits ${response.code}")
                    JSONObject(response.body?.string() ?: throw IOException("TMDB empty credits"))
                }
                creditsCache[key] = System.currentTimeMillis() to responseData
                if(creditsCache.size > 100) creditsCache.remove(creditsCache.minBy { it.value.first }.key)
                runCatching {
                    val root = JSONObject()
                    creditsCache.forEach { (id, entry) -> root.put(id, JSONObject().put("at", entry.first).put("data", entry.second)) }
                    creditsFile?.writeText(root.toString())
                }
                responseData
            }
            parseCredits(data, film.series)
        } }
    }

    private val imdbLock = Mutex()
    private val imdbIds = mutableMapOf<String, String>()

    /** Resolve the trusted TMDB match to its official external IMDb identifier. */
    suspend fun imdbId(film: Film): String = withContext(Dispatchers.IO) {
        imdbLock.withLock {
            imdbIds[film.id]?.let { return@withLock it }
            val match = lookup(film) ?: return@withLock ""
            if(match.tmdbId <= 0) return@withLock ""
            val path = if(film.series) "3/tv/${match.tmdbId}/external_ids" else "3/movie/${match.tmdbId}"
            val url = HttpUrl.Builder().scheme("https").host("api.themoviedb.org").addPathSegments(path)
                .addQueryParameter("api_key", apiKey).build()
            val id = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if(!response.isSuccessful) throw IOException("TMDB external IDs ${response.code}")
                JsonMapper.text(JSONObject(response.body?.string() ?: throw IOException("TMDB empty")), "imdb_id")
                    .takeIf { it.matches(Regex("tt\\d+")) }.orEmpty()
            }
            imdbIds[film.id] = id
            id
        }
    }

    private val seasonLock = Mutex()
    private val seasonFile = cacheFile?.let { File(it.parentFile, "tmdb-seasons.json") }
    private val seasonCache = mutableMapOf<String, Pair<Long, JSONObject>>()
    private var seasonsLoaded = false

    /** Resolve the parent first, then request the exact source season. Cache successful responses for six hours. */
    suspend fun season(show: Film, number: Int): Season? {
        if(!enabled || !show.series || number < 0) return null
        val match = lookup(show) ?: return null
        if(match.tmdbId <= 0) return null
        return withContext(Dispatchers.IO) {
            seasonLock.withLock {
                if(!seasonsLoaded) {
                    seasonsLoaded = true
                    runCatching {
                        val root = JSONObject(seasonFile?.takeIf { it.exists() }?.readText() ?: "{}")
                        root.keys().forEach { key -> root.optJSONObject(key)?.let { entry ->
                            entry.optJSONObject("data")?.let { seasonCache[key] = entry.optLong("at") to it }
                        } }
                    }
                }
                val key = "${match.tmdbId}:$number"
                val cached = seasonCache[key]?.takeIf { System.currentTimeMillis() - it.first < 6 * 60 * 60 * 1000L }
                val data = cached?.second ?: run {
                    fun request(language: String): JSONObject {
                        val url = HttpUrl.Builder().scheme("https").host("api.themoviedb.org")
                            .addPathSegments("3/tv/${match.tmdbId}/season/$number")
                            .addQueryParameter("api_key", apiKey).addQueryParameter("language", language).build()
                        return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            if(!response.isSuccessful) throw IOException("TMDB season ${response.code}")
                            JSONObject(response.body?.string() ?: throw IOException("TMDB empty season"))
                        }
                    }
                    val arabic = request("ar")
                    val episodes = arabic.optJSONArray("episodes") ?: JSONArray()
                    val needsFallback = JsonMapper.text(arabic, "overview").isBlank() ||
                        (0 until episodes.length()).any { JsonMapper.text(episodes.getJSONObject(it), "overview").isBlank() }
                    if(needsFallback) {
                        val english = runCatching { request("en") }.getOrDefault(JSONObject())
                        if(JsonMapper.text(arabic, "overview").isBlank()) arabic.put("overview", JsonMapper.text(english, "overview"))
                        val fallback = english.optJSONArray("episodes") ?: JSONArray()
                        for(i in 0 until episodes.length()) {
                            val episode = episodes.getJSONObject(i)
                            val other = (0 until fallback.length()).map { fallback.getJSONObject(it) }
                                .firstOrNull { it.optInt("episode_number") == episode.optInt("episode_number") } ?: continue
                            for(field in listOf("name", "overview")) if(JsonMapper.text(episode, field).isBlank()) episode.put(field, JsonMapper.text(other, field))
                        }
                    }
                    seasonCache[key] = System.currentTimeMillis() to arabic
                    if(seasonCache.size > 100) seasonCache.remove(seasonCache.minBy { it.value.first }.key)
                    runCatching {
                        val root = JSONObject()
                        seasonCache.forEach { (id, entry) -> root.put(id, JSONObject().put("at", entry.first).put("data", entry.second)) }
                        seasonFile?.writeText(root.toString())
                    }
                    arabic
                }
                parseSeason(data)
            }
        }
    }

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
                known[id] = Entry(if (!o.optBoolean("none") && (!o.has("original") || !o.has("genres") || !o.has("tmdbId") || !o.has("language"))) 0L else o.optLong("t"), if (o.optBoolean("none")) null
                    else ArtworkMatch(o.optString("b"), o.optString("p"), o.optString("o"), o.optString("r"), o.optString("original"), genreIds(o.optJSONArray("genres")), o.optInt("tmdbId"), o.optString("language")))
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
                    if (m == null) put("none", true) else put("b", m.backdrop).put("p", m.poster).put("o", m.overview).put("r", m.rating).put("original", m.originalTitle).put("genres", JSONArray(m.genreIds.toList())).put("tmdbId", m.tmdbId).put("language", m.originalLanguage)
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

        fun parseCredits(data: JSONObject, series: Boolean): WorkCredits {
            val credits = data.optJSONObject("credits") ?: JSONObject()
            val cast = JsonMapper.array(credits.opt("cast")).mapNotNull { actor ->
                val id = actor.optInt("id")
                val name = JsonMapper.text(actor, "name", "original_name")
                if(id <= 0 || name.isBlank()) return@mapNotNull null
                val path = JsonMapper.text(actor, "profile_path")
                CastMember(id, name, if(path.isBlank()) "" else ImageBase + "w185" + path)
            }.distinctBy { it.id }.take(30)
            val directors = JsonMapper.array(credits.opt("crew")).filter { JsonMapper.text(it, "job") == "Director" }
                .map { JsonMapper.text(it, "name", "original_name") }.filter { it.isNotBlank() }.distinct()
            val creators = if(series) JsonMapper.array(data.opt("created_by"))
                .map { JsonMapper.text(it, "name", "original_name") }.filter { it.isNotBlank() }.distinct() else emptyList()
            val year = JsonMapper.text(data, if(series) "first_air_date" else "release_date").take(4)
                .takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()
            return WorkCredits(cast, directors, creators, year)
        }

        /** Parse episode-specific metadata; TMDB scores are deliberately separate from IMDb. */
        fun parseSeason(data: JSONObject): Season {
            val number = data.optInt("season_number")
            val episodes = data.optJSONArray("episodes") ?: JSONArray()
            return Season(number, (0 until episodes.length()).mapNotNull { index ->
                val episode = episodes.optJSONObject(index) ?: return@mapNotNull null
                val n = episode.optInt("episode_number")
                if(n <= 0) return@mapNotNull null
                val still = JsonMapper.text(episode, "still_path")
                val score = episode.optDouble("vote_average", 0.0)
                val runtime = episode.optInt("runtime")
                Film("tmdb-episode:${episode.optInt("id")}", JsonMapper.text(episode, "name"),
                    backdrop = if(still.isBlank()) "" else ImageBase + "w780" + still,
                    description = JsonMapper.text(episode, "overview"), season = number, episode = n,
                    duration = if(runtime > 0) (runtime.toLong() * 60).toString() else "",
                    airDate = JsonMapper.text(episode, "air_date"),
                    tmdbRating = if(episode.optInt("vote_count") > 0 && score.isFinite() && score > 0 && score <= 10)
                        String.format(Locale.US, "%.1f", score) else "")
            }, title = JsonMapper.text(data, "name"), description = JsonMapper.text(data, "overview"),
                poster = JsonMapper.text(data, "poster_path").let { if(it.isBlank()) "" else ImageBase + "w780" + it },
                airDate = JsonMapper.text(data, "air_date"))
        }

        /** Enrich only episodes available on the source; never create playable TMDB-only entries. */
        fun mergeSeason(source: Season, metadata: Season): Season {
            if(source.number != metadata.number) return source
            return source.copy(title = metadata.title, description = metadata.description, poster = metadata.poster,
                airDate = metadata.airDate, episodes = source.episodes.map { original ->
                    val match = metadata.episodes.singleOrNull { it.episode == original.episode } ?: return@map original
                    original.copy(description = match.description.ifBlank { original.description },
                        backdrop = match.backdrop.ifBlank { original.backdrop },
                        duration = original.duration.ifBlank { match.duration },
                        airDate = match.airDate, tmdbRating = match.tmdbRating)
                })
        }

        private fun genreIds(array: JSONArray?): Set<Int> = if (array == null) emptySet() else
            (0 until array.length()).map { array.optInt(it) }.filter { it > 0 }.toSet()

        fun toMatch(r: JSONObject): ArtworkMatch {
            val backdrop = JsonMapper.text(r, "backdrop_path")
            val poster = JsonMapper.text(r, "poster_path")
            val votes = r.optInt("vote_count", 0)
            val average = r.optDouble("vote_average", 0.0)
            return ArtworkMatch(
                tmdbId = r.optInt("id"), originalLanguage = JsonMapper.text(r, "original_language"),
                backdrop = if (backdrop.isBlank()) "" else ImageBase + "w1280" + backdrop,
                poster = if (poster.isBlank()) "" else ImageBase + "w780" + poster,
                overview = JsonMapper.text(r, "overview"),
                originalTitle = JsonMapper.text(r, "original_title", "original_name"),
                genreIds = genreIds(r.optJSONArray("genre_ids")),
                rating = if (votes >= 10 && average > 0) String.format(Locale.US, "%.1f", average) else "")
        }
    }
}
