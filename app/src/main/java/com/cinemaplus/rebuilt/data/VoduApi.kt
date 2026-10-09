package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

/** Native read-only adapter; IDs include the provider and parent title for episodes. */
class VoduApi {
    private val client = Http.client(12, 20, 30)
    private val cache = LinkedHashMap<String, Pair<Long, Document>>()

    private suspend fun page(params: Map<String, String>, fresh: Boolean = false): Document = withContext(Dispatchers.IO) {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("movie.vodu.me")
            .addPathSegment("index.php").apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        synchronized(cache) { if (fresh) null else cache[url.toString()] }?.takeIf { it.first > System.nanoTime() }?.let { return@withContext it.second }
        val doc = NetworkErrors.retryOnce {
            client.newCall(Request.Builder().url(url).build()).executeCancellable().use { response ->
                if (!response.isSuccessful) throw SourceHttpException(response.code, "VODU")
                if (response.request.url.host != "movie.vodu.me") error("أعاد VODU توجيهاً غير متوقع")
                Jsoup.parse(response.body?.string() ?: error("استجابة VODU فارغة"), BASE)
            }
        }
        synchronized(cache) {
            if (cache.size >= 64) cache.remove(cache.keys.first())
            cache[url.toString()] = (System.nanoTime() + TimeUnit.MINUTES.toNanos(5)) to doc
        }
        doc
    }

    private suspend fun title(id: String, fresh: Boolean = false): Document = page(mapOf("do" to "view", "type" to "post", "id" to parentId(id)), fresh)

    suspend fun catalog(series: Boolean, query: String, pageNumber: Int): List<Film> {
        val params = mutableMapOf("do" to "list", "type" to if (series) "1" else "0", "page" to pageNumber.toString())
        if (query.isNotBlank()) params["title"] = query
        return VoduParser.catalog(page(params), series)
    }

    suspend fun detail(film: Film): Film = VoduParser.detail(title(film.id), film)
    suspend fun seasons(id: String, fresh: Boolean = false): List<Season> = VoduParser.seasons(title(id, fresh), parentId(id))
    suspend fun streams(id: String): List<Stream> {
        val doc = title(id)
        val anchor = VoduParser.playAnchor(doc, id) ?: return emptyList()
        return VoduParser.streams(anchor)
    }

    companion object {
        const val BASE = "https://movie.vodu.me/"
        fun owns(id: String) = id.startsWith("vodu:")
        fun parentId(id: String): String {
            require(Regex("vodu:[1-9]\\d*(?::[1-9]\\d*)?").matches(id)) { "معرّف VODU غير صالح" }
            return id.split(':')[1]
        }
    }
}

/** Parser kept independent of Android for fixture-based tests. */
object VoduParser {
    fun catalog(doc: Document, series: Boolean): List<Film> = doc.select(".mytitle a[href]").mapNotNull { link ->
        val id = Regex("[?&]id=(\\d+)").find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
        val card = link.parents().firstOrNull { it.hasClass("myitem") } ?: link.parent()
        val poster = card?.selectFirst("img[src]")?.absUrl("src").orEmpty()
        Film("vodu:$id", link.text(), poster = poster, backdrop = poster, series = series, enTitle = link.text())
    }.distinctBy { it.id }

    fun playAnchor(doc: Document, id: String): Element? {
        val parts = id.split(':')
        return if (parts.size == 3) doc.select(".episodeitem a.play").firstOrNull { it.attr("data-id") == parts[2] }
        else doc.select(".postcontent a.play").firstOrNull { anchor -> anchor.parents().none { it.hasClass("episodeitem") } }
    }

    fun streams(anchor: Element): List<Stream> = listOf("data-url" to "720p", "data-url1080" to "1080p",
        "data-url4k" to "2160p", "data-url360" to "360p").mapNotNull { (attr, quality) ->
        if (anchor.attr(attr).isBlank()) return@mapNotNull null
        anchor.absUrl(attr).takeIf { it.startsWith("https://") }?.let { Stream(it, quality) }
    }.distinctBy { it.url }

    fun subtitles(anchor: Element?): List<Subtitle> {
        if (anchor == null) return emptyList()
        if (anchor.attr("data-webvtt").isBlank()) return emptyList()
        // Prefer WebVTT because the existing player declares TEXT_VTT.
        val url = anchor.absUrl("data-webvtt")
        return if (url.startsWith("https://")) listOf(Subtitle(url, "ar", "العربية")) else emptyList()
    }

    fun detail(doc: Document, film: Film): Film {
        val content = doc.selectFirst(".postcontent") ?: error("تعذّر قراءة تفاصيل VODU")
        val name = content.selectFirst("h1")?.text() ?: error("تفاصيل VODU غير متاحة")
        val anchor = playAnchor(doc, film.id)
        if (film.id.count { it == ':' } == 2) {
            if (anchor == null) error("حلقة VODU غير متاحة")
            return film.copy(subtitles = subtitles(anchor))
        }
        val metadata = content.select("h3").mapNotNull { heading ->
            val span = heading.selectFirst("span") ?: return@mapNotNull null
            span.text().trim().trimEnd(':') to heading.text().removePrefix(span.text()).trim()
        }.toMap()
        val synopsis = content.select("h3").firstOrNull { it.text().trimEnd(':').equals("Synopsis", true) }
            ?.nextElementSiblings()?.firstOrNull { it.tagName() == "h4" }?.text().orEmpty()
        val poster = content.selectFirst("img[src]")?.absUrl("src").orEmpty().ifBlank { film.poster }
        return film.copy(title = name, enTitle = name, poster = poster, backdrop = poster, description = synopsis,
            originalTitle = metadata["Original Title"].orEmpty().ifBlank { film.originalTitle },
            contentQuality = metadata["Quality"].orEmpty().ifBlank { availableQuality(anchor?.let(::streams).orEmpty()) },
            edition = metadata["Version"].orEmpty().ifBlank { metadata["Source"].orEmpty() },
            year = metadata["Year"].orEmpty(), duration = metadata["Runtime"].orEmpty(),
            imdbRating = metadata["IMdB Rating"].orEmpty(), rating = metadata["IMdB Rating"].orEmpty(), series = film.series || content.select(".episodes").isNotEmpty(),
            subtitles = subtitles(anchor), trailer = content.selectFirst("iframe[src]")?.absUrl("src").orEmpty())
    }

    fun seasons(doc: Document, parent: String): List<Season> {
        val labels = doc.select(".customtabs .nav-tabs a[href]").associate { it.attr("href").removePrefix("#") to it.text() }
        val poster = doc.selectFirst(".postcontent img[src]")?.absUrl("src").orEmpty()
        return doc.select(".episodeitem a.play").mapNotNull { anchor ->
            val episodeId = anchor.attr("data-id").takeIf { it.matches(Regex("[1-9]\\d*")) } ?: return@mapNotNull null
            val title = anchor.attr("data-title")
            val numbers = Regex("S(\\d+)E(\\d+)", RegexOption.IGNORE_CASE).find(title)
            val tab = anchor.parents().firstOrNull { it.id().startsWith("tab-") }?.id()
            val season = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE).find(labels[tab.orEmpty()].orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull() ?: numbers?.groupValues?.get(1)?.toIntOrNull() ?: 1
            Film("vodu:$parent:$episodeId", title, poster, poster, series = true, season = season,
                episode = numbers?.groupValues?.get(2)?.toIntOrNull() ?: 0, subtitles = subtitles(anchor))
        }.distinctBy { it.id }.groupBy { it.season }.map { (number, episodes) ->
            Season(number, episodes.sortedBy { it.episode })
        }.sortedBy { it.number }
    }
}
