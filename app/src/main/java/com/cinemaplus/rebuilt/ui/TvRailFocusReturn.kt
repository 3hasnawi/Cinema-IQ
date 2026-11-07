package com.cinemaplus.rebuilt.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester

internal val LocalTvRailReturnScope = staticCompositionLocalOf { TvRailReturnScope() }

/**
 * Remembers a group's focused child without auto-restoring on normal row entry. Apply immediately before the
 * group's focusGroup/scroll modifier; nested scroll groups keep their own child so restoration reaches the leaf.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.tvRailFocusReturn(
    requester: FocusRequester? = null,
    fallback: (() -> FocusRequester?)? = null,
    saveOnExit: Boolean = true
): Modifier {
    val localRequester = remember { FocusRequester() }
    val target = requester ?: localRequester
    val scope = LocalTvRailReturnScope.current
    return focusRequester(target).focusProperties {
        onEnter = {
            val restored = scope.restoring && runCatching { target.restoreFocusedChild() }.getOrDefault(false)
            if (!restored) fallback?.invoke()?.let { runCatching { it.requestFocus() } }
        }
        if (saveOnExit) onExit = { runCatching { target.saveFocusedChild() }; Unit }
    }
}
