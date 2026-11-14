package com.cinemaplus.rebuilt.data.iptv

/** Guide searches do not change the source-ordered channel-surfing queue. */
object IptvNavigation {
    fun channels(values: List<IptvChannel>, sourceId: String, group: String?, query: String = ""): List<IptvChannel> {
        val text = query.trim()
        return values.filter { it.sourceId == sourceId && (group == null || it.group == group) &&
            (text.isEmpty() || it.name.contains(text, true) || it.group.contains(text, true)) }
    }
    fun neighbor(values: List<IptvChannel>, currentId: String, step: Int): IptvChannel? {
        if (values.isEmpty()) return null
        val current = values.indexOfFirst { it.id == currentId }
        if (current < 0) return values.first()
        return values[(current + step % values.size + values.size) % values.size]
    }
    fun entry(values: List<IptvChannel>, currentId: String): String? = values.firstOrNull { it.id == currentId }?.id ?: values.firstOrNull()?.id
}
