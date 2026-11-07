package com.cinemaplus.rebuilt.ui

/** Fresh entry never uses spatial search while a real first-card target is still being prepared. */
internal object TvBrowseFocusPolicy {
    enum class Entry { Explicit, Wait, EmptyPage }
    fun entry(hasTarget: Boolean, loading: Boolean): Entry = when {
        hasTarget -> Entry.Explicit
        loading -> Entry.Wait
        else -> Entry.EmptyPage
    }
    fun gridRowFirst(current: Int, count: Int, columns: Int, down: Boolean): Int? {
        if (current !in 0 until count || columns <= 0) return null
        val row = current / columns + if (down) 1 else -1
        return (row * columns).takeIf { row >= 0 && it in 0 until count }
    }
}
