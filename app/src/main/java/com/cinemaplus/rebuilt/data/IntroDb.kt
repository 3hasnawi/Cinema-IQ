package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Read-only, anonymous timing fallback. It never receives a provider token or media URL. */
class IntroDb(private val cacheFile: File? = null) {
    private val client = Http.client(3, 4, 6)
    private val lock = Mutex()
    private var cache: JSONObject? = null
    suspend fun lookup(imdbId: String, season: Int, episode: Int, movie: Boolean = false): SkipMarkers = withContext(Dispatchers.IO) {
        if(!imdbId.matches(Regex("tt\\d+")) || !movie && (season <= 0 || episode <= 0)) return@withContext SkipMarkers()
        lock.withLock {
            val key = "$imdbId:$movie:$season:$episode"
            val data = cache ?: runCatching { JSONObject(cacheFile?.takeIf { it.exists() }?.readText() ?: "{}") }.getOrDefault(JSONObject()).also { cache = it }
            val existing = data.optJSONObject(key)
            val now = System.currentTimeMillis()
            if(existing?.optJSONObject("data") != null && now - existing.optLong("at") in 0 until existing.optLong("ttl"))
                return@withLock parse(existing.getJSONObject("data"), imdbId, season, episode, movie)
            val url = HttpUrl.Builder().scheme("https").host("api.introdb.app").addPathSegment("segments").addQueryParameter("imdb_id", imdbId).apply {
                if(movie) addQueryParameter("is_movie", "true") else { addQueryParameter("season", season.toString()); addQueryParameter("episode", episode.toString()) }
            }.build()
            val response = client.newCall(Request.Builder().url(url).header("User-Agent", "Cinema-iQ/IntroDB").build()).executeCancellable().use { r ->
                if(r.code == 404) JSONObject() else {
                    if(!r.isSuccessful) throw IOException("IntroDB ${r.code}")
                    JSONObject(r.body?.string() ?: throw IOException("IntroDB empty response"))
                }
            }
            val result = parse(response, imdbId, season, episode, movie)
            data.put(key, JSONObject().put("at", now).put("ttl", if(result == SkipMarkers()) 6*60*60*1000L else 7*24*60*60*1000L).put("data", response))
            if(data.length() > 200) data.remove(data.keys().asSequence().minBy { data.optJSONObject(it)?.optLong("at") ?: 0 })
            runCatching { cacheFile?.writeText(data.toString()) }
            result
        }
    }
    companion object {
        fun parse(o: JSONObject, imdbId: String, season: Int, episode: Int, movie: Boolean = false): SkipMarkers {
            if(o.optString("imdb_id") != imdbId || o.opt("is_movie") != movie ||
                !movie && (o.optInt("season") != season || o.optInt("episode") != episode)) return SkipMarkers()
            fun segment(type: String): Pair<Long, Long>? {
                val segment = o.optJSONObject(type) ?: return null
                fun edge(name: String): Long? = if(segment.has(name + "_ms")) SkipMarkers.time(segment.opt(name + "_ms"), true)
                    else SkipMarkers.time(segment.opt(name + "_sec"), false)
                val start = edge("start") ?: return null
                val end = edge("end") ?: return null
                return (start to end).takeIf { end > start }
            }
            val intro = segment("intro")
            return SkipMarkers(intro?.first ?: 0, intro?.second ?: 0, segment("outro")?.first ?: 0)
        }
    }
}
