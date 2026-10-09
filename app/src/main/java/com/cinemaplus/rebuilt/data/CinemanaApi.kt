package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class CinemanaApi(private val tokenProvider: () -> String? = { null }) {
    private val vodu = VoduApi()
    suspend fun voduCatalog(series: Boolean, query: String, page: Int) = vodu.catalog(series, query, page)
    private val client = Http.client(12, 20, 30)
    private val base = "https://cinemana.shabakaty.cc/api/android/"
    private suspend fun get(path: String): Any = withContext(Dispatchers.IO) {
        // Idempotent read: one retry for brief network hiccups or a 5xx answer.
        NetworkErrors.retryOnce {
            client.newCall(Request.Builder().url(base + path).apply { tokenProvider()?.let { header("Authorization", it) } }.get().build()).executeCancellable().use {
                if (!it.isSuccessful) throw SourceHttpException(it.code)
                val body = it.body?.string() ?: error("استجابة فارغة من المصدر")
                runCatching { JSONTokener(body).nextValue() }.getOrElse { error("استجابة غير صالحة من المصدر") }
            }
        }
    }
    suspend fun home(): List<Shelf> {
        val groups = JsonMapper.array(get("videoGroups/lang/ar/level/1"))
        val shelves = groups.take(8).mapNotNull { group ->
            val id = JsonMapper.text(group, "groupsID", "groupID", "nb", "id")
            if (id.isBlank()) null else Shelf(id,
                JsonMapper.text(group, "ar_title", "ar_name", "title", "name").ifBlank { "اكتشف المزيد" },
                JsonMapper.films(group.opt("content")).ifEmpty { JsonMapper.films(get("videoListPagination/groupID/$id/level/1/itemsPerPage/12/page/1")) })
        }.filter { it.films.isNotEmpty() }
        return shelves.ifEmpty { listOf(Shelf("latest", "أضيف حديثًا", catalog(false, "", 1))) }
    }
    suspend fun catalog(series: Boolean, query: String, page: Int, category: String = "0"): List<Film> {
        // Original client applies base64 to the UTF-8 query, then URI-encodes it.
        val encoded = if (query.isBlank()) "-" else URLEncoder.encode(
            android.util.Base64.encodeToString(query.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP), "UTF-8")
        val response = get(CatalogRequest.path(series, encoded, page, category))
        val rows = JsonMapper.array(response)
        val films = JsonMapper.films(response, series)
        if(rows.isNotEmpty() && films.isEmpty())
            error("أعاد المصدر استجابة بحث غير معروفة. لا يمكن عرضها كقائمة نتائج فارغة.")
        return films
    }
    /** Source categories (genres). The endpoint name follows the source API's category list. */
    suspend fun categories(): List<Category> = JsonMapper.categories(get("categories"))
    /**
     * One shelf per category with its newest titles, loaded in parallel. A category that fails or is empty
     * is skipped, so one bad request never blanks the page.
     */
    suspend fun genreShelves(series: Boolean, maxGenres: Int = 12): List<Shelf> = coroutineScope {
        categories().take(maxGenres).map { category ->
            async { runCatching { Shelf("genre-${if (series) "s" else "m"}-${category.id}", category.title, catalog(series, "", 1, category.id)) }.getOrNull() }
        }.awaitAll().filterNotNull().filter { it.films.isNotEmpty() }
    }
    suspend fun detail(film: Film): Film {
        if (VoduApi.owns(film.id)) return vodu.detail(film)
        val obj = JsonMapper.array(get("allVideoInfo/id/${film.id}")).firstOrNull()
            ?: error("لم تُرجع الخدمة تفاصيل العمل")
        if (JsonMapper.text(obj, "nb", "videoId", "videoID", "id").isBlank()) obj.put("nb", film.id)
        val parsed = JsonMapper.film(obj, film.series) ?: error("تعذّر قراءة تفاصيل العمل")
        return parsed.copy(poster = parsed.poster.ifBlank { film.poster }, backdrop = parsed.backdrop.ifBlank { film.backdrop },
            title = parsed.title.takeUnless { it == "عمل ${film.id}" } ?: film.title,
            year = parsed.year.ifBlank { film.year }, duration = parsed.duration.ifBlank { film.duration },
            originalLanguage = parsed.originalLanguage.ifBlank { film.originalLanguage }, imdbRating = parsed.imdbRating.ifBlank { film.imdbRating }, rating = parsed.rating.ifBlank { film.rating }, description = parsed.description.ifBlank { film.description },
            enTitle = parsed.enTitle.ifBlank { film.enTitle }, originalTitle = parsed.originalTitle.ifBlank { film.originalTitle },
            contentQuality = parsed.contentQuality.ifBlank { film.contentQuality }, edition = parsed.edition.ifBlank { film.edition })
    }
    suspend fun seasons(id: String, fresh: Boolean = false) = if (VoduApi.owns(id)) vodu.seasons(id, fresh) else JsonMapper.seasons(get("videoSeason/id/$id"))
    suspend fun streams(id: String) = if (VoduApi.owns(id)) vodu.streams(id) else JsonMapper.streams(get("transcoddedFiles/id/$id"))
}
