package com.cinemaplus.rebuilt.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Shelf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Genre/mood shelves share explicit entry and row transitions instead of nearest-card spatial search. */
@Composable
internal fun TvShelfPage(vm: CinemaViewModel, shelves: List<Shelf>,
    header: @Composable () -> Unit, footer: @Composable () -> Unit) {
    val rows = shelves.filter { it.films.isNotEmpty() }
    val ids = rows.map { it.id }
    val first = remember(ids) { ids.associateWith { FocusRequester() } }
    val horizontal = rows.map { shelf -> key(shelf.id) { rememberLazyListState() } }
    val list = rememberLazyListState()
    var returnKey by rememberSaveable { mutableStateOf<String?>(null) }
    val returningFocus = remember { FocusRequester() }
    var focusedRow by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var motion by remember { mutableStateOf<Job?>(null) }
    val fresh = LocalTvFreshEntry.current
    val rail = LocalTabBarState.current
    val enterRail = LocalTvHomeRailEntry.current
    val footerFocus = remember { FocusRequester() }
    var footerFocused by remember { mutableStateOf(false) }
    suspend fun prepareFirst() {
        motion?.cancel()
        returnKey = null
        list.scrollToItem(0)
        withFrameNanos { }
        if (rows.isNotEmpty() && list.layoutInfo.visibleItemsInfo.none { it.index == 1 }) list.scrollToItem(1)
        horizontal.firstOrNull()?.scrollToItem(0)
    }
    RegisterPageEntry(ids.firstOrNull()?.let(first::get)) { prepareFirst() }
    LaunchedEffect(Unit) {
        if (!fresh && !rail.hasFocus) {
            val row = rows.indexOfFirst { shelf -> shelf.films.any { "${shelf.id}/${it.id}" == returnKey } }
            if (row >= 0) {
                list.scrollToItem(row + 1)
                withFrameNanos { }
                val card = rows[row].films.indexOfFirst { "${rows[row].id}/${it.id}" == returnKey }
                if (card >= 0 && horizontal[row].layoutInfo.visibleItemsInfo.none { it.index == card }) horizontal[row].scrollToItem(card)
                if (!awaitTvFocus { returningFocus }) returnKey = null
            } else if (rows.isNotEmpty()) { prepareFirst(); awaitTvFocus { first[ids[0]] } }
        }
    }
    WithoutFocusScroll {
        LazyColumn(Modifier.fillMaxSize().onFocusChanged { if (!it.hasFocus) motion?.cancel() }.tvRailFocusReturn().onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val down = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN
            if (!down && native.keyCode != AndroidKeyEvent.KEYCODE_DPAD_UP) return@onPreviewKeyEvent false
            val current = ids.indexOf(focusedRow)
            if (current < 0) {
                if (!footerFocused || rows.isEmpty()) return@onPreviewKeyEvent false
                if (!down && native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                    motion?.cancel()
                    motion = scope.launch { scrollToTvFocus(list, rows.size, first.getValue(ids.last()), horizontal.last()) }
                }
                return@onPreviewKeyEvent true
            }
            val next = adjacentTvSection(current, rows.size, down)
            if (next == null) {
                if (down) {
                    if (vm.error != null && native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                        motion?.cancel(); motion = scope.launch { list.animateScrollToItem(rows.size + 1); awaitTvFocus { footerFocus } }
                    }
                    return@onPreviewKeyEvent true
                }
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) enterRail?.invoke()
                return@onPreviewKeyEvent true
            }
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                motion?.cancel()
                motion = scope.launch { scrollToTvFocus(list, next + 1, first.getValue(ids[next]), horizontal[next]) }
            }
            true
        }, state = list, contentPadding = PaddingValues(top = TvMetrics.TabBarSpace, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap)) {
            item(key = "title") { header() }
            itemsIndexed(rows, key = { _, shelf -> shelf.id }) { index, shelf ->
                val restoreIndex = shelf.films.indexOfFirst { "${shelf.id}/${it.id}" == returnKey }
                Box(Modifier.onFocusChanged { if (!it.hasFocus && focusedRow == shelf.id) focusedRow = null }) {
                    TvShelfRow(shelf, vm, firstRequester = first[shelf.id], suppliedRowState = horizontal[index],
                        cardModifier = { if (it == restoreIndex) Modifier.focusRequester(returningFocus) else Modifier },
                        onFilmFocused = { focusedRow = shelf.id },
                        onCardClick = { returnKey = "${shelf.id}/${it.id}"; vm.open(it) })
                }
            }
            item(key = "status") {
                Box(Modifier.focusRequester(footerFocus).onFocusChanged { footerFocused = it.hasFocus }) { footer() }
            }
        }
    }
}
