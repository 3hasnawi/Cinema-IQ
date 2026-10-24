package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import okhttp3.Request

/** Uses public read endpoints only; no provider login/session headers or administrative calls. */
class DawwirliApi {
    private val client=Http.client(10,25,35)
    private val lock=Mutex()
    private val knownSeasons=mutableMapOf<String,Pair<Long,List<Season>>>()
    private suspend fun get(path: String,vararg q: Pair<String,String>): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(DawwirliMapper.url(path,*q)).get().build()).executeCancellable().use {
            if(!it.isSuccessful) throw SourceHttpException(it.code)
            JSONObject(it.body?.string() ?: error("استجابة دورلي فارغة"))
        }
    }
    suspend fun home(): List<Shelf> {
        val data=get("api/ar/home");val rows=data.optJSONArray("rows")
        val hero=DawwirliMapper.items(data.optJSONArray("hero"))
        return (if(hero.isEmpty()) emptyList() else listOf(Shelf("daw-featured","دورلي · مميز",hero))) +
            (0 until (rows?.length() ?: 0)).mapNotNull { n ->
                val row=rows?.optJSONObject(n) ?: return@mapNotNull null
                val films=DawwirliMapper.items(row.optJSONArray("items"),row.optString("kind")=="series")
                if(films.isEmpty()) null else Shelf("daw-home-$n",row.optString("title","دورلي"),films)
            }
    }
    suspend fun catalog(series: Boolean, query: String, page: Int): List<Film> {
        if(page < 1) return emptyList()
        if(query.isNotBlank()) {
            // The site's search response is a complete ranked result set, not a paginated endpoint.
            if(page != 1) return emptyList()
            return DawwirliMapper.items(get("api/search","q" to query,"kind" to if(series) "series" else "movie").optJSONArray("items"),series)
        }
        if(!series) return category("newest",false,page).filter { !it.series }
        // Series come from the home page's series row ("see all"), as "newest" holds only movies.
        val (id,kind)=seriesCategory()
        val found=category(id,true,page,kind).filter { it.series }
        if(found.isNotEmpty() || page != 1) return found
        return home().flatMap { it.films }.filter { it.series }.distinctBy { it.id }
    }
    private var seriesAll: Pair<String,String>? = null
    private suspend fun seriesCategory(): Pair<String,String> = seriesAll ?: run {
        val rows=get("api/ar/home").optJSONArray("rows")
        val row=(0 until (rows?.length() ?: 0)).mapNotNull { rows?.optJSONObject(it) }
            .firstOrNull { it.optString("kind") == "series" && it.optString("all").isNotBlank() && it.optString("all") != "null" }
        (row?.let { it.optString("all") to it.optString("allKind").ifBlank { "series" } } ?: ("sr-foreign" to "series")).also { seriesAll = it }
    }
    private suspend fun category(id: String,series: Boolean,page: Int,kind: String = if(series) "series" else "movies") =
        get("api/cat","id" to id,"kind" to kind,"page" to page.toString()).let {
            if(page > it.optInt("pages",Int.MAX_VALUE)) emptyList() else DawwirliMapper.items(it.optJSONArray("items"),series)
        }
    suspend fun sections(series: Boolean): List<Shelf> = coroutineScope {
        val groups=get("api/section","id" to if(series) "series" else "movies").optJSONArray("groups")
        val cards=(0 until (groups?.length() ?: 0)).flatMap { n ->
            val a=groups?.optJSONObject(n)?.optJSONArray("cards")
            (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
        }.distinctBy { it.optString("id") }.take(8)
        // Small batches keep category loading from flooding the public service.
        cards.chunked(3).flatMap { batch -> batch.map { card -> async {
            try { Shelf("genre-${if(series) "s" else "m"}-daw-${card.optString("id")}",card.optString("label","دورلي"),category(card.getString("id"),series,1)) }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { null }
        } }.awaitAll().filterNotNull().filter { it.films.isNotEmpty() } }
    }
    suspend fun seasons(id: String, fresh: Boolean = false): List<Season> = lock.withLock {
        val now=System.currentTimeMillis()
        knownSeasons[id]?.takeIf { !fresh && now-it.first in 0..300000L }?.let { return@withLock it.second }
        val k=DawwirliMapper.key(id)
        val result=DawwirliMapper.seasons(id,get("api/series_info","series_id" to k.raw))
        if(knownSeasons.size >= 30) knownSeasons.remove(knownSeasons.keys.first())
        knownSeasons[id]=now to result; result
    }
    suspend fun detail(film: Film): Film {
        val k=DawwirliMapper.key(film.id)
        val detailed=when(k.type) {
            "episode" -> seasons("daw:series:${k.parent}").flatMap { it.episodes }.firstOrNull { it.id==film.id } ?: error("لم يعثر دورلي على الحلقة")
            "series" -> DawwirliMapper.detail(film,get("api/series_info","series_id" to k.raw))
            else -> DawwirliMapper.detail(film,get("api/info","id" to k.raw))
        }
        if(k.type=="series") return detailed
        val subs=try { DawwirliMapper.subtitles(get("api/subtitles","id" to k.raw,"kind" to k.kind,"title" to detailed.title,"s" to detailed.season.toString(),"e" to detailed.episode.toString())) }
            catch(e: CancellationException) { throw e } catch(_: Exception) { emptyList() }
        return detailed.copy(subtitles=subs)
    }
    fun streams(id: String)=listOf(DawwirliMapper.stream(id))
}
