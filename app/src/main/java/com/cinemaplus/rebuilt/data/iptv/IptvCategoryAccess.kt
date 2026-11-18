package com.cinemaplus.rebuilt.data.iptv

/** In-memory authorization for one category visit and, separately, one already-started decoder. */
internal data class IptvCategoryAccess(
    val visitKey: String? = null,
    val continuation: IptvCategoryContinuation? = null
) {
    val unlockedCategories: Set<String> get() = visitKey?.let(::setOf) ?: emptySet()

    /** Authorizing another category replaces the old browse grant without interrupting the decoder. */
    fun authorizeVisit(key: String): IptvCategoryAccess = copy(visitKey = key)

    fun leaveVisit(): IptvCategoryAccess = copy(visitKey = null)

    fun canStart(channel: IptvChannel, policy: IptvCategoryPolicy): Boolean =
        policy.gate(channel, unlockedCategories) == IptvCategoryGate.Allowed

    /** A continuation can renew the same stream; it never grants permission to start another one. */
    fun canContinue(channel: IptvChannel, policy: IptvCategoryPolicy): Boolean = when (policy.gate(channel, unlockedCategories)) {
        IptvCategoryGate.Hidden -> false
        IptvCategoryGate.Allowed -> true
        IptvCategoryGate.PinRequired -> continuation?.matches(channel) == true
    }

    fun start(channel: IptvChannel, policy: IptvCategoryPolicy): IptvCategoryAccess {
        if (!canStart(channel, policy)) return this
        val key = IptvCategoryPolicy.key(channel)
        return copy(continuation = if (key in policy.locked) IptvCategoryContinuation(channel.sourceId, channel.id, key) else null)
    }

    /** Locking/hiding or explicitly revoking a category removes both forms of authorization. */
    fun revoke(key: String): IptvCategoryAccess = copy(
        visitKey = visitKey?.takeUnless { it == key },
        continuation = continuation?.takeUnless { it.categoryKey == key }
    )
}

/** No URLs, credentials or persisted state. Provider regrouping invalidates an old channel lease. */
internal data class IptvCategoryContinuation(val sourceId: String, val channelId: String, val categoryKey: String) {
    fun matches(channel: IptvChannel): Boolean = sourceId == channel.sourceId && channelId == channel.id &&
        categoryKey == IptvCategoryPolicy.key(channel)
}
