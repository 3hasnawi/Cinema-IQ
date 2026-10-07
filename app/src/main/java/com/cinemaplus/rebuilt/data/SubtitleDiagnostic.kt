package com.cinemaplus.rebuilt.data

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Diagnostic identifiers never expose signed subtitle URLs or query parameters. */
object SubtitleDiagnostic {
    fun reference(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }

    fun extension(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension.takeIf { it in setOf("vtt", "srt", "ass", "ssa", "ttml", "xml") } ?: "unknown"
    }
}
