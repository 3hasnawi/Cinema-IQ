package com.cinemaplus.rebuilt.ui

/** Logical destinations are independent of layout coordinates and Android key codes. */
internal object TvHomeNavigation {
    sealed interface Destination {
        data object Hero : Destination
        data object Rail : Destination
        data class Shelf(val index: Int) : Destination
        data object None : Destination
    }

    fun carouselIndex(current: Int, count: Int, right: Boolean, rtl: Boolean): Int {
        if (count <= 0) return 0
        val step = if (right == rtl) -1 else 1
        return Math.floorMod(current.coerceIn(0, count - 1) + step, count)
    }

    fun verticalTarget(currentShelf: Int?, down: Boolean, shelfCount: Int, hasHero: Boolean): Destination {
        if (currentShelf == null) return when {
            !down -> Destination.Rail
            shelfCount > 0 -> Destination.Shelf(0)
            else -> Destination.None
        }
        if (currentShelf !in 0 until shelfCount) return Destination.None
        return when {
            down && currentShelf + 1 < shelfCount -> Destination.Shelf(currentShelf + 1)
            down -> Destination.None
            currentShelf > 0 -> Destination.Shelf(currentShelf - 1)
            hasHero -> Destination.Hero
            else -> Destination.Rail
        }
    }
}

/** Open once on release, never on a held OK repeat or a key-up that arrived from another screen. */
internal class TvHomeSelectPress {
    var armed = false
        private set
    fun update(down: Boolean, repeat: Int): Boolean {
        if (down) { if (repeat == 0) armed = true; return false }
        val open = armed
        armed = false
        return open
    }
    fun cancel() { armed = false }
}
