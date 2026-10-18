package com.cinemaplus.rebuilt.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Provider IDs include media type, parent series and container, and survive saved-list round trips. */
object DawwirliMapper {
    const val BASE = "https://premium.dawwirli.watch/"
    data class Key(val type: String, val raw: String, val parent: String = "", val ext: String = "mp4") {
        val id: String get() = when(type) { "series" -> "daw:series:$raw"; "episode" -> "daw:episode:$parent:$raw:$ext"; else -> "daw:movie:$raw:$ext" }
        val kind: String get() = if(type == "episode" || type == "series") "series" else "movie"
    }
    fun owns(id: String) = id.startsWith("daw:")
    fun key(id: String): Key {
        val p = id.split(':')
        require(p.firstOrNull() == "daw") { "معرّف دورلي غير صالح" }
        val key = when {
            p.size == 3 && p[1] == "series" -> Key("series",p[2])
            p.size == 4 && p[1] == "movie" -> Key("movie",p[2],ext=p[3])
            p.size == 5 && p[1] == "episode" -> Key("episode",p[3],p[2],p[4])
            else -> error("معرّف دورلي غير صالح")
        }
        require(Regex("[A-Za-z0-9_~-]+").matches(key.raw) && (key.parent.isEmpty() || Regex("[A-Za-z0-9_~-]+").matches(key.parent)))
        require(Regex("[a-zA-Z0-9]{1,8}").matches(key.ext))
        return key
    }
    fun url(path: String, vararg query: Pair<String,String>): String = BASE.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).apply {
        query.forEach { (k,v) -> addQueryParameter(k,v) }
    }.build().toString()
    fun stream(id: String): Stream {
        val k = key(id); require(k.type != "series")
        return Stream(url("vod", "kind" to k.kind, "id" to k.raw, "ext" to k.ext),"المصدر الأصلي")
    }
    private fun image(raw: String): String {
        if(raw.isBlank()) return ""
        val url=BASE.toHttpUrl().resolve(raw) ?: return ""
        return if(url.scheme == "https") url.toString() else ""
    }
    fun cleanTitle(value: String) = value.replace(Regex("^(?:AR|EN|TR|HI|KR|FR)\\s*[|:-]\\s*",RegexOption.IGNORE_CASE),"")
        .replace(Regex("\\s*\\[MULTI-SUB\\]\\s*$",RegexOption.IGNORE_CASE),"")
        .replace(Regex("\\s+-\\s+\\d{4}\\s*$"),"").trim()
    fun item(o: JSONObject, seriesHint: Boolean = false): Film? {
        val raw = JsonMapper.text(o,"id","stream_id","series_id")
        if(raw.isBlank()) return null
        val series = JsonMapper.text(o,"kind","_kind") == "series" || seriesHint
        val k = Key(if(series) "series" else "movie",raw,ext=JsonMapper.text(o,"ext","container_extension").ifBlank { "mp4" })
        if(runCatching { key(k.id) }.isFailure) return null
        val title = cleanTitle(JsonMapper.text(o,"name","title"))
        if(title.isBlank()) return null
        return Film(k.id,title,poster=image(JsonMapper.text(o,"poster","icon","cover")),
            backdrop=image(JsonMapper.text(o,"backdrop")),description=JsonMapper.text(o,"plot","description"),
            year=JsonMapper.text(o,"year").takeUnless { it == "0" }.orEmpty(),rating=JsonMapper.rating(o),series=series,
            enTitle=title,contentQuality=JsonMapper.text(o,"quality","resolution"))
    }
    fun items(a: JSONArray?, series: Boolean = false): List<Film> = if(a == null) emptyList() else
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { row -> item(row,series) } }.distinctBy { it.id }
    fun detail(film: Film, data: JSONObject): Film {
        val info = data.optJSONObject("info") ?: error("لم يُرجع دورلي تفاصيل صالحة")
        val name = cleanTitle(JsonMapper.text(info,"name","o_name"))
        val video = info.optJSONObject("video")
        val height = video?.optInt("height") ?: 0
        return film.copy(title=name.ifBlank { film.title },poster=image(JsonMapper.text(info,"cover_big","movie_image","cover")).ifBlank { film.poster },
            backdrop=image(info.optJSONArray("backdrop_path")?.optString(0).orEmpty()).ifBlank { film.backdrop },
            description=JsonMapper.text(info,"plot","description").ifBlank { film.description },
            year=JsonMapper.text(info,"releasedate","releaseDate").take(4).ifBlank { film.year },duration=JsonMapper.text(info,"duration","episode_run_time").ifBlank { film.duration },
            rating=JsonMapper.rating(info).ifBlank { film.rating },trailer=JsonMapper.text(info,"youtube_trailer"),
            contentQuality=if(height > 0) "${height}p" else film.contentQuality)
    }
    fun seasons(id: String, data: JSONObject): List<Season> {
        val k=key(id);require(k.type == "series")
        val info=data.optJSONObject("info") ?: JSONObject()
        val groups=data.optJSONObject("episodes") ?: error("لم يُرجع دورلي قائمة حلقات صالحة")
        return groups.keys().asSequence().mapNotNull { number ->
            val season=number.toIntOrNull() ?: return@mapNotNull null
            val a=groups.optJSONArray(number) ?: return@mapNotNull null
            val episodes=(0 until a.length()).mapNotNull episode@{ n ->
                val o=a.optJSONObject(n) ?: return@episode null
                val raw=JsonMapper.text(o,"id");if(raw.isBlank()) return@episode null
                val key=Key("episode",raw,k.raw,JsonMapper.text(o,"container_extension").ifBlank { "mp4" })
                if(runCatching { DawwirliMapper.key(key.id) }.isFailure) return@episode null
                val meta=o.optJSONObject("info") ?: JSONObject()
                val title=cleanTitle(JsonMapper.text(o,"title"))
                Film(key.id,title,poster=image(JsonMapper.text(meta,"movie_image")).ifBlank { image(JsonMapper.text(info,"cover")) },
                    backdrop=image(info.optJSONArray("backdrop_path")?.optString(0).orEmpty()),description=JsonMapper.text(meta,"plot"),
                    duration=JsonMapper.text(meta,"duration"),rating=JsonMapper.rating(meta),series=true,season=season,episode=o.optInt("episode_num",n+1),
                    seriesId=id,episodeTitle=title,airDate=JsonMapper.text(meta,"releasedate"))
            }
            Season(season,episodes)
        }.sortedBy { it.number }.toList()
    }
    fun subtitles(data: JSONObject): List<Subtitle> {
        val a=data.optJSONArray("tracks") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o=a.optJSONObject(i) ?: return@mapNotNull null
            val raw=JsonMapper.text(o,"url");if(raw.isBlank()) return@mapNotNull null
            val url=BASE.toHttpUrl().resolve(raw) ?: return@mapNotNull null
            if(url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) return@mapNotNull null
            val lang=JsonMapper.text(o,"lang","language").ifBlank { "und" }
            val mime=if(url.encodedPath.endsWith(".srt",true)) "application/x-subrip" else "text/vtt"
            Subtitle(url.toString(),lang,JsonMapper.text(o,"label","title").ifBlank { lang },mime)
        }.distinctBy { it.url }
    }
}
