package com.cinemaplus.rebuilt.data.iptv

import java.util.Collections

/** One immutable guide page. Row identities and indexes share the same provider/collection order. */
internal class IptvTvGuidePage internal constructor(rows: List<IptvChannel>) {
    val rows: List<IptvChannel> = Collections.unmodifiableList(ArrayList(rows))
    val indexById: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap<String, Int>().apply {
        this@IptvTvGuidePage.rows.forEachIndexed { index, channel -> put(channel.id, index) }
    })

    internal companion object {
        val Empty = IptvTvGuidePage(emptyList())
        fun of(rows: List<IptvChannel>): IptvTvGuidePage = if (rows.isEmpty()) Empty else IptvTvGuidePage(rows)
    }
}

/** Build off-main from the current authorized projection, never the raw catalogue or decoder lease. */
internal class IptvTvGuideCatalog private constructor(
    val sourceId: String,
    internal val all: IptvTvGuidePage,
    private val groups: Map<String, IptvTvGuidePage>,
    internal val byId: Map<String, IptvChannel>,
    internal val searchFields: Map<String, IptvChannelSearch.PreparedChannel>
) {
    internal fun group(name: String): IptvTvGuidePage = groups[name] ?: IptvTvGuidePage.Empty

    fun snapshot(collection: IptvChannelCollection): IptvTvGuideSnapshot = IptvTvGuideSnapshot(this, collection)

    companion object {
        fun build(
            authorizedChannels: List<IptvChannel>, sourceId: String,
            canonicalNumbers: Map<String, Int>, hiddenGroups: Set<String> = emptySet()
        ): IptvTvGuideCatalog {
            val byId = LinkedHashMap<String, IptvChannel>()
            val grouped = LinkedHashMap<String, MutableList<IptvChannel>>()
            val fields = LinkedHashMap<String, IptvChannelSearch.PreparedChannel>()
            for (channel in authorizedChannels) {
                if (channel.sourceId != sourceId || channel.group in hiddenGroups || byId.containsKey(channel.id)) continue
                byId[channel.id] = channel
                grouped.getOrPut(channel.group) { ArrayList() }.add(channel)
                fields[channel.id] = IptvChannelSearch.prepareChannel(channel, canonicalNumbers[channel.id] ?: 0)
            }
            val groups = grouped.mapValuesTo(LinkedHashMap()) { (_, channels) -> IptvTvGuidePage.of(channels) }
            return IptvTvGuideCatalog(sourceId, IptvTvGuidePage.of(byId.values.toList()),
                Collections.unmodifiableMap(groups), Collections.unmodifiableMap(byId), Collections.unmodifiableMap(fields))
        }
    }
}

/** Collection changes reuse the catalogue's All/Group pages and prepared search fields. */
internal class IptvTvGuideSnapshot internal constructor(
    private val catalog: IptvTvGuideCatalog, collection: IptvChannelCollection
) {
    private val favorites = collection.favorites.toHashSet().let { ids ->
        if (ids.isEmpty()) IptvTvGuidePage.Empty else IptvTvGuidePage.of(catalog.all.rows.filter { it.id in ids })
    }
    private val recent = collection.recent.toList().distinct().let { ids ->
        IptvTvGuidePage.of(ids.mapNotNull { catalog.byId[it] })
    }

    fun page(section: IptvChannelSection): IptvTvGuidePage = when (section) {
        IptvChannelSection.All -> catalog.all
        IptvChannelSection.Favorites -> favorites
        IptvChannelSection.Recent -> recent
        is IptvChannelSection.Group -> catalog.group(section.name)
    }

    fun search(section: IptvChannelSection, query: String): IptvTvGuidePage {
        val page = page(section)
        val matcher = IptvChannelSearch.prepareQuery(query)
        if (matcher.isBlank || page.rows.isEmpty()) return page
        return IptvTvGuidePage.of(page.rows.filter { channel ->
            catalog.searchFields[channel.id]?.let(matcher::matches) == true
        })
    }
}
