package com.cinemaplus.rebuilt.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Finds scenes to skip when the player's scissors filter is on.
 *
 * It cannot see the picture. Two sources are combined:
 *  1. Subtitle analysis: cues containing flagged words (see [Flagged]) become skip ranges, padded and merged.
 *     Scenes without flagged dialogue are not detected.
 *  2. Optional manual ranges in `assets/scene_filter.json`: {"<film id>": [[startSeconds, endSeconds], ...]}.
 * Ranges are in milliseconds.
 */
object SceneFilter {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private const val PadBeforeMs = 1_500L
    private const val PadAfterMs = 2_000L
    private const val MergeGapMs = 6_000L
    private const val MaxSubtitleBytes = 2_000_000L

    suspend fun ranges(context: Context, film: Film): List<LongRange> = withContext(Dispatchers.IO) {
        val found = mutableListOf<LongRange>()
        found += manual(context, film.id)
        // Arabic and English tracks together catch more than either alone.
        val tracks = film.subtitles.filter { it.language.substringBefore('-') in setOf("ar", "en") }.ifEmpty { film.subtitles.take(1) }
        tracks.take(2).forEach { track -> found += fromSubtitle(track.url) }
        merge(found)
    }

    private fun manual(context: Context, id: String): List<LongRange> = runCatching {
        val text = context.applicationContext.assets.open("scene_filter.json").bufferedReader().use { it.readText() }
        val array = JSONObject(text).optJSONArray(id) ?: return@runCatching emptyList<LongRange>()
        (0 until array.length()).mapNotNull { i ->
            val pair = array.optJSONArray(i) ?: return@mapNotNull null
            val start = (pair.optDouble(0) * 1000).toLong(); val end = (pair.optDouble(1) * 1000).toLong()
            if (end > start) start..end else null
        }
    }.getOrDefault(emptyList())

    private fun fromSubtitle(url: String): List<LongRange> = runCatching {
        val body = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use ""
            val source = response.body ?: return@use ""
            if (source.contentLength() > MaxSubtitleBytes) "" else source.string()
        }
        parse(body)
    }.getOrDefault(emptyList())

    /** Parses WebVTT/SRT text and returns padded ranges of cues that contain flagged words. */
    internal fun parse(text: String): List<LongRange> {
        val result = mutableListOf<LongRange>()
        val lines = text.replace("\r", "").lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("-->")) {
                val start = time(line.substringBefore("-->")); val end = time(line.substringAfter("-->"))
                val cue = StringBuilder()
                i++
                while (i < lines.size && lines[i].isNotBlank()) { cue.append(lines[i]).append(' '); i++ }
                if (start != null && end != null && isFlagged(cue.toString()))
                    result += (start - PadBeforeMs).coerceAtLeast(0L)..(end + PadAfterMs)
            } else i++
        }
        return result
    }

    private fun time(raw: String): Long? {
        val parts = raw.trim().substringBefore(' ').replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        return runCatching {
            val seconds = parts.last().toDouble()
            val minutes = parts[parts.size - 2].toLong()
            val hours = if (parts.size == 3) parts[0].toLong() else 0L
            ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toLong()
        }.getOrNull()
    }

    private fun merge(ranges: List<LongRange>): List<LongRange> {
        val sorted = ranges.sortedBy { it.first }
        val out = mutableListOf<LongRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.first - last.last <= MergeGapMs) out[out.lastIndex] = last.first..maxOf(last.last, r.last)
            else out += r
        }
        return out
    }

    private val tagRegex = Regex("<[^>]+>")
    private val splitRegex = Regex("[^\\p{L}]+")

    private fun isFlagged(cue: String): Boolean {
        val tokens = normalize(cue.replace(tagRegex, " ")).split(splitRegex).filter { it.isNotEmpty() }
        return tokens.any { it in Flagged }
    }

    /** Lowercases and strips Arabic diacritics, tatweel and alef/yaa variants so spellings match. */
    private fun normalize(text: String): String = buildString {
        for (c in text.lowercase()) when {
            c in 'ً'..'ٟ' || c == 'ـ' -> Unit
            c == 'أ' || c == 'إ' || c == 'آ' -> append('ا')
            c == 'ى' -> append('ي')
            c == 'ة' -> append('ه')
            else -> append(c)
        }
    }

    /** Whole-word matches only, so words that merely contain a short token (e.g. "essex") are not flagged. */
    private val Flagged: Set<String> = setOf(
        "sex", "sexy", "sexual", "naked", "nude", "nudity", "orgasm", "horny", "porn", "blowjob", "rape", "raped",
        "slut", "whore", "pussy", "dick", "cum", "fuck", "fucking", "fucked", "motherfucker", "bitch",
        "جنس", "جنسي", "جنسيه", "سكس", "عاري", "عاريه", "عراه", "نيك", "ينيك", "منيوك", "شرموطه", "شرموطة",
        "قحبه", "عاهره", "زب", "كس", "طيز", "اغتصاب", "يغتصب", "اغتصبها", "اباحي", "اباحيه"
    ).map { normalize(it) }.toSet()
}
