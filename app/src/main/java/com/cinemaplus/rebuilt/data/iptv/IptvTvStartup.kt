package com.cinemaplus.rebuilt.data.iptv

/** Resume only a channel that still belongs to the selected, freshly loaded source. */
object IptvTvStartup {
    fun channel(values: List<IptvChannel>, sourceId: String, recent: List<String>): IptvChannel? {
        val available = values.asSequence().filter { it.sourceId == sourceId }.associateBy { it.id }
        return recent.firstNotNullOfOrNull { available[it] } ?: available.values.firstOrNull()
    }
}
