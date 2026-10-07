package com.cinemaplus.rebuilt.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream

/** Resolve one readable external subtitle per language; leave the VODU adapter unchanged. */
class CinemanaSubtitles {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val cache = LinkedHashMap<String, Subtitle>()

    suspend fun resolve(subtitles: List<Subtitle>): List<Subtitle> = coroutineScope {
        subtitles.distinctBy { it.url }.groupBy(SubtitleFormat::groupKey).values.map { candidates -> async {
            // Known VTT files are tried first, but their content still has to validate.
            val ordered = candidates.sortedBy { if (SubtitleDiagnostic.extension(it.url) == "vtt") 0 else 1 }
            var selected: Subtitle? = null
            for (candidate in ordered) {
                selected = probe(candidate)
                if (selected != null) break
            }
            selected
        } }.awaitAll().filterNotNull()
    }

    private suspend fun probe(subtitle: Subtitle): Subtitle? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[subtitle.url] }?.let {
            return@withContext subtitle.copy(mimeType = it.mimeType, title = SubtitleFormat.displayLabel(subtitle))
        }
        try {
            val mime = client.newCall(Request.Builder().url(subtitle.url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                // Bound memory and reject non-subtitle responses before passing them to Media3.
                val bytes = body.byteStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 2 * 1024 * 1024) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 2 * 1024 * 1024 + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 2 * 1024 * 1024) return@withContext null
                SubtitleFormat.mimeType(bytes.toString(Charsets.UTF_8)) ?: return@withContext null
            }
            val resolved = subtitle.copy(mimeType = mime, title = SubtitleFormat.displayLabel(subtitle))
            synchronized(cache) {
                if (cache.size >= 64) cache.remove(cache.keys.first())
                cache[subtitle.url] = resolved
            }
            resolved
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
}
