package com.cinemaplus.rebuilt.ui

/** Consume the whole Left press even after focus moves from the rail back into the page. */
internal class TvRailReturnPress {
    data class Result(val consumed: Boolean, val restore: Boolean = false)
    private var awaitingRelease = false
    fun route(left: Boolean, down: Boolean, repeat: Int, railFocused: Boolean): Result {
        if (!left || !railFocused && !awaitingRelease) return Result(false)
        if (!down) { awaitingRelease = false; return Result(true) }
        val restore = !awaitingRelease && repeat == 0 && railFocused
        awaitingRelease = true
        return Result(true, restore)
    }
    fun cancel() { awaitingRelease = false }
}

/** Restore saved children only for an explicit rail return, never for ordinary shelf Up/Down navigation. */
internal class TvRailReturnScope {
    var restoring = false
        private set
    fun <T> restore(block: () -> T): T {
        val previous = restoring
        restoring = true
        return try { block() } finally { restoring = previous }
    }
}
