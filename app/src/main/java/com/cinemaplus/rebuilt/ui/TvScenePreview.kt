package com.cinemaplus.rebuilt.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cinemaplus.rebuilt.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/** MediaMetadataRetriever can seek into progressive streams without loading a whole movie into memory. */
private class PreviewByteRanges(private val url: String, private val job: Job) : MediaDataSource() {
    private var length = -1L
    private val blocks = LruCache<Long, ByteArray>(8)
    private var requests = 0
    private val deadline = SystemClock.elapsedRealtime() + 8_000

    private fun block(start: Long): ByteArray {
        job.ensureActive()
        blocks.get(start)?.let { return it }
        if (++requests > 48 || SystemClock.elapsedRealtime() > deadline) throw IOException("Preview budget reached")
        val end = start + BLOCK_SIZE - 1
        val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity").build()
        client.newCall(request).execute().use { response ->
            val range = contentRange.matchEntire(response.header("Content-Range").orEmpty())
            if (response.code != 206 || range == null || range.groupValues[1].toLongOrNull() != start) {
                throw IOException("Resource does not support byte ranges")
            }
            val last = range.groupValues[2].toLong()
            val size = range.groupValues[3].toLong()
            if (last !in start..end || last >= size || (length >= 0 && size != length)) throw IOException("Invalid range")
            length = size
            val bytes = response.body?.source()?.readByteArray(last - start + 1) ?: throw IOException("No data")
            blocks.put(start, bytes)
            return bytes
        }
    }
    override fun getSize(): Long { if (length < 0) block(0); return length }
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0 || position >= getSize()) return -1
        val start = position / BLOCK_SIZE * BLOCK_SIZE
        val bytes = block(start)
        val from = (position - start).toInt()
        val count = minOf(size, bytes.size - from)
        if (count <= 0) return -1
        bytes.copyInto(buffer, offset, from, from + count)
        return count
    }
    override fun close() { blocks.evictAll() }

    companion object {
        private const val BLOCK_SIZE = 128 * 1024L
        private val contentRange = Regex("""bytes (\d+)-(\d+)/(\d+)""")
        private val client = Http.client(connect = 2, read = 2, call = 3).newBuilder().followSslRedirects(false).build()
    }
}

private suspend fun loadSceneFrames(context: Context, uri: Uri, times: List<Long>, cache: LruCache<Long, Bitmap>) {
    if (Build.VERSION.SDK_INT < 27 || times.all { cache.get(it) != null }) return
    if (uri.lastPathSegment.orEmpty().let { it.endsWith(".m3u8", true) || it.endsWith(".mpd", true) }) return
    val job = currentCoroutineContext()[Job] ?: return
    withContext(Dispatchers.IO) {
        val frames = MediaMetadataRetriever()
        var bytes: PreviewByteRanges? = null
        try {
            when (uri.scheme) {
                "file", "content", "android.resource" -> frames.setDataSource(context, uri)
                "http", "https" -> {
                    val source = PreviewByteRanges(uri.toString(), job)
                    bytes = source
                    frames.setDataSource(source)
                }
                else -> return@withContext
            }
            for (time in times.distinct()) {
                ensureActive()
                if (cache.get(time) == null) {
                    frames.getScaledFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 244, 138)
                        ?.let { cache.put(time, it) }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Time-only preview remains available when the source has no usable frames. */ }
        finally { try { frames.release() } finally { bytes?.close() } }
    }
}

@Composable
internal fun TvScenePreview(url: String?, position: Long, duration: Long) {
    val context = LocalContext.current
    val cache = remember(url) { LruCache<Long, Bitmap>(20) }
    val center = (position / 1000) * 1000
    val times = remember(center, duration) { (-2..2).map { center + it * 10_000 }.map { it.takeIf { t -> t in 0 until duration } } }
    val frames by produceState<Map<Long, Bitmap>>(emptyMap(), url, times) {
        val present = times.filterNotNull()
        fun cached() = present.mapNotNull { t -> cache.get(t)?.let { t to it } }.toMap()
        value = cached()
        if (!url.isNullOrBlank()) {
            delay(160)
            loadSceneFrames(context, Uri.parse(url), listOfNotNull(times[2]) + present, cache)
            value = cached()
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.mx)) {
        if (times.any { frames[it] != null }) CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.mx), verticalAlignment = Alignment.CenterVertically) {
                times.forEachIndexed { index, time ->
                    val size = if (index == 2) 250 else 208
                    Box(Modifier.width(size.mx).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.mx))) {
                        frames[time]?.let { frame ->
                            Image(frame.asImageBitmap(), null, Modifier.fillMaxSize().semantics { contentDescription = "معاينة المشهد" }, contentScale = ContentScale.Crop)
                            if (index == 2) Box(Modifier.fillMaxSize().border(2.dp, Brand.Accent, RoundedCornerShape(10.mx)))
                        }
                    }
                }
            }
        }
        Label(formatPlayerTime(position), 18, Color.White, true, lines = 1)
        Label("OK  الانتقال إلى المشهد     ·     رجوع  إلغاء", 12, Brand.TextSecondary, lines = 1)
    }
}
