package com.cinemaplus.rebuilt.ui

/** Accept clock strings and numeric seconds. Treat values exceeding one day as milliseconds.
 * This compatibility heuristic is limited to ordinary movie/episode runtimes.
 */
internal fun formatContentDuration(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty()) return ""
    val parsed = if (':' in value) {
        val parts = value.split(':').map { it.toLongOrNull() ?: return "" }
        if (parts.size !in 2..3 || parts.any { it < 0 }) return ""
        parts.fold(0L) { total, part -> total * 60 + part }
    } else value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it < 1e9 }?.toLong() ?: return ""
    val seconds = if (':' !in value && parsed >= 86_400) parsed / 1_000 else parsed
    if (seconds <= 0) return ""
    val minutes = (seconds / 60).coerceAtLeast(1)
    val hours = minutes / 60
    val remainder = minutes % 60
    return if (hours == 0L) "$remainder دقيقة" else "${hours.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
}
