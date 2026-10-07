package com.cinemaplus.rebuilt.data

/** Detect actual subtitle content rather than trusting a file extension or text/plain header. */
object SubtitleFormat {
    private val timing = Regex("(?m)^\\s*(?:\\d{1,2}:)?\\d{2}:\\d{2}[.,]\\d{3}\\s+-->\\s+(?:\\d{1,2}:)?\\d{2}:\\d{2}[.,]\\d{3}[^\\r\\n]*$")
    fun mimeType(content: String): String? {
        val text = content.removePrefix("\uFEFF").trimStart()
        if (!timing.containsMatchIn(text)) return null
        if (text.startsWith("WEBVTT") && (text.length == 6 || text[6].isWhitespace())) return "text/vtt"
        if (Regex("(?m)^\\s*\\d+\\s*\\r?\\n\\s*\\d{1,2}:\\d{2}:\\d{2},\\d{3}\\s+-->").containsMatchIn(text)) return "application/x-subrip"
        return null
    }
    fun groupKey(subtitle: Subtitle): String {
        val language = subtitle.language.lowercase(java.util.Locale.ROOT).replace('_', '-')
        return if (language.isBlank() || language == "und") "unknown:${subtitle.title}:${subtitle.url}" else language
    }
    fun displayLabel(subtitle: Subtitle): String =
        if (subtitle.language == "und") subtitle.title else subtitle.title.substringBefore(" · ")
}
