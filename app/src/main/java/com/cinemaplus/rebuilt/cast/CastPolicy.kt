package com.cinemaplus.rebuilt.cast

import java.net.URI

/** Receiver URLs must be directly reachable; no sender cookies or authentication are forwarded. */
object CastPolicy {
    fun valid(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
    fun mime(raw: String): String? {
        if (!valid(raw)) return null
        val uri = URI(raw)
        val ext = uri.path.substringAfterLast('.', "").lowercase()
        val queryExt = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("ext=") }?.substringAfter('=')
        return when ((queryExt ?: ext).lowercase()) {
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "mkv", "avi", "mov" -> null
            else -> "video/mp4"
        }
    }
    fun subtitle(raw: String, mime: String): Boolean = valid(raw) &&
        (mime.equals("text/vtt", true) || URI(raw).path.endsWith(".vtt", true))
}
