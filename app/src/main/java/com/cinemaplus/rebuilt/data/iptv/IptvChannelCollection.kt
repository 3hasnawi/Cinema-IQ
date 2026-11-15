package com.cinemaplus.rebuilt.data.iptv

sealed interface IptvChannelSection {
    data object All : IptvChannelSection
    data object Recent : IptvChannelSection
    data object Favorites : IptvChannelSection
    data class Group(val name: String) : IptvChannelSection
}

/** Stores channel IDs only, separate from movie history and encrypted subscription credentials. */
data class IptvChannelCollection(val favorites: Set<String> = emptySet(), val recent: List<String> = emptyList()) {
    fun watched(id: String): IptvChannelCollection = if (id.isBlank() || id.length > 512) this else copy(recent = (listOf(id) + recent.filterNot { it == id }).take(100))
    fun toggle(id: String): IptvChannelCollection = when {
        id.isBlank() || id.length > 512 -> this
        id in favorites -> copy(favorites = favorites - id)
        favorites.size < 2000 -> copy(favorites = favorites + id)
        else -> this
    }
    fun channels(values: List<IptvChannel>, sourceId: String, section: IptvChannelSection, query: String = ""): List<IptvChannel> {
        val source = IptvNavigation.channels(values, sourceId, null)
        val chosen = when (section) {
            IptvChannelSection.All -> source
            IptvChannelSection.Favorites -> source.filter { it.id in favorites }
            IptvChannelSection.Recent -> source.associateBy { it.id }.let { index -> recent.mapNotNull { index[it] } }
            is IptvChannelSection.Group -> source.filter { it.group == section.name }
        }
        return IptvNavigation.channels(chosen, sourceId, null, query)
    }
}
