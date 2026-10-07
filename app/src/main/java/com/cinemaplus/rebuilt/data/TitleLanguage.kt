package com.cinemaplus.rebuilt.data

enum class TitleLanguage(val label: String) {
    ARABIC("العربية"), ENGLISH("الإنجليزية"), ORIGINAL("اللغة الأصلية");
    companion object {
        fun restore(value: String?) = entries.firstOrNull { it.name == value } ?: ARABIC
    }
}

/** Display preferences never modify source titles or playback identifiers. */
fun Film.displayTitle(language: TitleLanguage): String = when (language) {
    TitleLanguage.ARABIC -> title
    TitleLanguage.ENGLISH -> enTitle.ifBlank { title }
    TitleLanguage.ORIGINAL -> originalTitle.ifBlank { title }
}

/** Selects actual available playback resolution, never the user's preferred resolution. */
fun availableQuality(streams: List<Stream>): String = streams.filter { it.resolution.isNotBlank() }
    .maxByOrNull { stream ->
        if (stream.resolution.contains("4K", ignoreCase = true)) 2160
        else Regex("\\d{3,4}").find(stream.resolution)?.value?.toIntOrNull() ?: 0
    }?.resolution.orEmpty()
