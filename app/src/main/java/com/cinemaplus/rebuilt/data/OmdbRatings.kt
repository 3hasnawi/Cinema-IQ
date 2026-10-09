package com.cinemaplus.rebuilt.data

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Exact IMDb IDs only; OMDb ratings must never be inferred from TMDB votes. */
class OmdbRatings(private val apiKey: String, private val file: File? = null) {
    val enabled: Boolean get() = apiKey.isNotBlank()
    private var unavailableUntil = 0L
    private val lock = Mutex()
    private val cache = mutableMapOf<String, Pair<Long, String>>()
    private var loaded = false
    private val client = Http.client(12, 20, 15)

    suspend fun rating(imdbId: String, season: Int? = null, episode: Int? = null): String = withContext(Dispatchers.IO) {
        if(apiKey.isBlank() || !imdbId.matches(Regex("tt\\d+"))) return@withContext ""
        if((season == null) != (episode == null) || (episode != null && (episode <= 0 || season!! < 0))) return@withContext ""
        lock.withLock {
            if(!loaded) {
                loaded = true
                runCatching {
                    val root = JSONObject(file?.takeIf { it.exists() }?.readText() ?: "{}")
                    root.keys().forEach { key -> root.optJSONObject(key)?.let { cache[key] = it.optLong("at") to it.optString("rating") } }
                }
            }
            val key = "$imdbId:${season ?: ""}:${episode ?: ""}"
            cache[key]?.takeIf { System.currentTimeMillis() - it.first <
                if(it.second.isBlank()) 6 * 60 * 60 * 1000L else 7 * 24 * 60 * 60 * 1000L }?.let { return@withLock it.second }
            if(System.currentTimeMillis() < unavailableUntil) return@withLock ""
            val url = HttpUrl.Builder().scheme("https").host("www.omdbapi.com")
                .addQueryParameter("apikey", apiKey).addQueryParameter("i", imdbId)
                .apply { if(episode != null) { addQueryParameter("Season", season.toString()); addQueryParameter("Episode", episode.toString()) } }.build()
            val data = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if(!response.isSuccessful) throw IOException("OMDb ${response.code}")
                JSONObject(response.body?.string() ?: throw IOException("OMDb empty response"))
            }
            if(data.optString("Response").equals("False", true) && !data.optString("Error").contains("not found", true)) {
                unavailableUntil = System.currentTimeMillis() + 60 * 60 * 1000L
                throw IOException("OMDb request unavailable")
            }
            val value = parseRating(data, imdbId, season, episode)
            cache[key] = System.currentTimeMillis() to value
            if(cache.size > 2000) cache.remove(cache.minBy { it.value.first }.key)
            runCatching {
                val root = JSONObject()
                cache.forEach { (id, item) -> root.put(id, JSONObject().put("at", item.first).put("rating", item.second)) }
                file?.writeText(root.toString())
            }
            value
        }
    }

    companion object {
        fun parseRating(data: JSONObject, imdbId: String, season: Int?, episode: Int?): String {
            if(!data.optString("Response").equals("True", true)) return ""
            if(episode == null) { if(data.optString("imdbID") != imdbId) return "" }
            else if(data.optString("seriesID") != imdbId || data.optString("Season").toIntOrNull() != season ||
                data.optString("Episode").toIntOrNull() != episode) return ""
            val score = data.optString("imdbRating").toDoubleOrNull() ?: return ""
            return if(score.isFinite() && score > 0 && score <= 10) String.format(Locale.US, "%.1f", score) else ""
        }
    }
}
