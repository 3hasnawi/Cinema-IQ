package com.cinemaplus.rebuilt.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Explicit vertical grid navigation; spatial search must not retain a middle column in the next row. */
internal class TvGridFocus(private val scope: CoroutineScope, private val state: LazyGridState, val first: FocusRequester) {
    private var ids: List<String> = emptyList()
    private var itemOffset = 0
    private var focused: String? = null
    private var footerFocused = false
    private var motion: Job? = null
    private val targets = mutableMapOf<String, FocusRequester>()
    fun update(values: List<String>, offset: Int) {
        if (ids != values) { motion?.cancel(); if (focused !in values) focused = null }
        ids = values; itemOffset = offset
    }
    fun target(id: String): FocusRequester = if (id == ids.firstOrNull()) first else targets.getOrPut(id) { FocusRequester() }
    fun focused(id: String) { focused = id; footerFocused = false }
    fun footerModifier(): Modifier = Modifier.onFocusChanged { footerFocused = it.hasFocus }
    fun cardModifier(id: String): Modifier = Modifier.onFocusChanged { if (!it.hasFocus && focused == id) focused = null }
    suspend fun prepareFirst() {
        motion?.cancel()
        state.scrollToItem(0)
        withFrameNanos { }
        if (ids.isNotEmpty() && state.layoutInfo.visibleItemsInfo.none { it.index == itemOffset }) state.scrollToItem(itemOffset)
    }
    suspend fun restore(id: String): Boolean {
        val index = ids.indexOf(id)
        return index >= 0 && focus(index, animate = false)
    }
    private suspend fun focus(index: Int, animate: Boolean = true): Boolean {
        val id = ids.getOrNull(index) ?: return false
        withFrameNanos { }
        val currentIndex = ids.indexOf(id)
        if (currentIndex < 0) return false
        val itemIndex = itemOffset + currentIndex
        val layout = state.layoutInfo
        val visible = layout.visibleItemsInfo.firstOrNull { it.index == itemIndex }
        if (visible == null || visible.offset.y < layout.viewportStartOffset || visible.offset.y + visible.size.height > layout.viewportEndOffset) {
            if (animate) state.animateScrollToItem(itemIndex) else state.scrollToItem(itemIndex)
        }
        return awaitTvFocus { target(id) }
    }
    fun navigate(block: suspend () -> Unit) { motion?.cancel(); motion = scope.launch { block() } }
    fun enterFirst() { navigate { focus(0) } }
    fun modifier(onTop: (() -> Unit)? = null, onBottom: (() -> Unit)? = null): Modifier = Modifier.onFocusChanged { if (!it.hasFocus) motion?.cancel() }.onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        val down = key.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN
        if (!down && key.keyCode != AndroidKeyEvent.KEYCODE_DPAD_UP) return@onPreviewKeyEvent false
        val current = ids.indexOf(focused)
        // Headers belong to the grid too: Down enters its first card, not the nearest column.
        if (current < 0) {
            if (footerFocused) {
                if (!down && key.action == AndroidKeyEvent.ACTION_DOWN && key.repeatCount == 0 && ids.isNotEmpty()) {
                    val columns = state.layoutInfo.maxSpan.coerceAtLeast(1)
                    val lastRow = (ids.lastIndex / columns) * columns
                    motion?.cancel(); motion = scope.launch { focus(lastRow) }
                }
                return@onPreviewKeyEvent true
            }
            if (!down || ids.isEmpty()) return@onPreviewKeyEvent false
            if (key.action == AndroidKeyEvent.ACTION_DOWN && key.repeatCount == 0) enterFirst()
            return@onPreviewKeyEvent true
        }
        val target = TvBrowseFocusPolicy.gridRowFirst(current, ids.size, state.layoutInfo.maxSpan, down)
        if (target == null) {
            if (down) {
                if (key.action == AndroidKeyEvent.ACTION_DOWN && key.repeatCount == 0) { motion?.cancel(); onBottom?.invoke() }
                return@onPreviewKeyEvent true
            }
            if (onTop == null) return@onPreviewKeyEvent false
            if (key.action == AndroidKeyEvent.ACTION_DOWN && key.repeatCount == 0) { motion?.cancel(); onTop() }
            return@onPreviewKeyEvent true
        }
        if (key.action == AndroidKeyEvent.ACTION_DOWN && key.repeatCount == 0) {
            motion?.cancel(); motion = scope.launch { focus(target) }
        }
        true
    }
}

@Composable
internal fun rememberTvGridFocus(ids: List<String>, state: LazyGridState, first: FocusRequester, offset: Int): TvGridFocus {
    val scope = rememberCoroutineScope()
    val navigation = remember(state, first, scope) { TvGridFocus(scope, state, first) }
    SideEffect { navigation.update(ids, offset) }
    return navigation
}
