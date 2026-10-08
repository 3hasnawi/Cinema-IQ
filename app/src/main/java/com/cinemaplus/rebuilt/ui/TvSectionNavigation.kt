package com.cinemaplus.rebuilt.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/** Compose lazy targets before requesting focus; off-screen requesters may not be attached yet. */
internal suspend fun scrollToTvFocus(state: LazyListState, index: Int, requester: FocusRequester): Boolean {
    if(index !in 0 until state.layoutInfo.totalItemsCount) return false
    state.animateScrollToItem(index)
    repeat(12) {
        withFrameNanos { }
        if(runCatching { requester.requestFocus() }.getOrDefault(false)) return true
    }
    return false
}

/** Never wrap vertical navigation; loading or empty sections do not invent a target. */
internal fun adjacentTvSection(current: Int, count: Int, down: Boolean): Int? =
    (current + if(down) 1 else -1).takeIf { current in 0 until count && it in 0 until count }
