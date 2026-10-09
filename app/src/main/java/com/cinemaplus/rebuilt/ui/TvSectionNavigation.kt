package com.cinemaplus.rebuilt.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/** Compose lazy targets before requesting focus; off-screen requesters may not be attached yet. */
internal suspend fun scrollToTvFocus(state: LazyListState, index: Int, requester: FocusRequester, horizontalState: LazyListState? = null): Boolean {
    if(index !in 0 until state.layoutInfo.totalItemsCount) return false
    // Reset the destination rail before the vertical reveal, avoiding a second sideways animation.
    horizontalState?.requestScrollToItem(0)
    state.animateScrollToItem(index)
    return awaitTvFocus { requester }

}

/** Never wrap vertical navigation; loading or empty sections do not invent a target. */
internal fun adjacentTvSection(current: Int, count: Int, down: Boolean): Int? =
    (current + if(down) 1 else -1).takeIf { current in 0 until count && it in 0 until count }

/** Retry on frame boundaries, including a false result; null means the target is not composed yet. */
internal suspend fun awaitTvFocus(target: () -> FocusRequester?): Boolean {
    repeat(30) {
        withFrameNanos { }
        if(runCatching { target()?.requestFocus() == true }.getOrDefault(false)) return true
    }
    return false
}
