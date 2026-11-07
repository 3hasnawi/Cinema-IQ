package com.cinemaplus.rebuilt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Mood
import com.cinemaplus.rebuilt.data.Moods

/** Browse by mood: a grid of moods, then the matching movies and series of the source's categories. */
@Composable
fun ExploreScreen(vm: CinemaViewModel) {
    val mood = vm.exploreMood
    // Back from a mood returns to the grid before it leaves the page.
    val requestEntry = LocalTvEntryRequest.current
    val fresh = LocalTvFreshEntry.current
    BackHandler(enabled = mood != null) { requestEntry(false); vm.chooseMood(null) }
    // Back from a mood puts focus on the card that was opened, not on the side rail.
    val listState = rememberLazyListState()
    val cardFocus = remember { mutableMapOf<String, FocusRequester>() }
    var openedMood by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mood) {
        val id = openedMood
        if (!fresh && mood == null && id != null) {
            withFrameNanos { }
            val index = Moods.all.indexOfFirst { it.id == id }
            if(index >= 0) {
                listState.animateScrollToItem(1 + index / 4)
                if(awaitTvFocus { cardFocus[id] }) openedMood = null
            }
        }
    }
    if (mood != null) {
        TvShelfPage(vm, Moods.shelves(mood, vm.shelves), header = {
            PageHeader(mood.title, mood.subtitle, Modifier.padding(horizontal = TvMetrics.EdgeInset))
        }, footer = {
            Column(Modifier.fillMaxWidth().padding(horizontal = TvMetrics.EdgeInset), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (vm.busy) CircularProgressIndicator(color = Accent)
                vm.error?.let { Message(it, "أعد المحاولة", { vm.reload() }) }
                if (!vm.busy && vm.error == null && Moods.shelves(mood, vm.shelves).isEmpty())
                    Label("لا توجد تصنيفات مطابقة لهذا المزاج في المصدر", 17, Muted)
            }
        })
        return
    }
    var focusedMood by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val footerFocus = remember { FocusRequester() }
    var footerFocused by remember { mutableStateOf(false) }
    var motion by remember { mutableStateOf<Job?>(null) }
    val enterRail = LocalTvHomeRailEntry.current
    RegisterPageEntry(cardFocus.getOrPut(Moods.all.first().id) { FocusRequester() }) {
        openedMood = null; listState.scrollToItem(0)
    }
    WithoutFocusScroll {
    LazyColumn(Modifier.fillMaxSize().onFocusChanged { if (!it.hasFocus) motion?.cancel() }.tvRailFocusReturn().onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val down = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN
        if (!down && native.keyCode != AndroidKeyEvent.KEYCODE_DPAD_UP) return@onPreviewKeyEvent false
        val current = Moods.all.indexOfFirst { it.id == focusedMood }
        val next = if (footerFocused && !down) (Moods.all.lastIndex / 4) * 4
            else TvBrowseFocusPolicy.gridRowFirst(current, Moods.all.size, 4, down)
        if (current < 0 && !footerFocused) return@onPreviewKeyEvent false
        if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) {
            motion?.cancel()
            when {
                next != null -> motion = scope.launch {
                    listState.animateScrollToItem(1 + next / 4)
                    awaitTvFocus { cardFocus[Moods.all[next].id] }
                }
                down && vm.error != null && !footerFocused -> motion = scope.launch {
                    listState.animateScrollToItem(1 + (Moods.all.size + 3) / 4)
                    awaitTvFocus { footerFocus }
                }
                !down && current >= 0 -> enterRail?.invoke()
            }
        }
        true
    }, state = listState, contentPadding = PaddingValues(top = TvMetrics.TabBarSpace, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap)) {
        item(key = "title") {
            PageHeader("استكشاف", "اختر مزاجًا وتصفّح أعمالًا تشبهه",
                Modifier.padding(horizontal = TvMetrics.EdgeInset))
        }
        items(Moods.all.chunked(4), key = { it.first().id }) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = TvMetrics.EdgeInset), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { m -> MoodCard(m, Modifier.weight(1f).focusRequester(cardFocus.getOrPut(m.id) { FocusRequester() })
                    .onFocusChanged { if (!it.hasFocus && focusedMood == m.id) focusedMood = null },
                    onFocused = { focusedMood = m.id }) {
                    openedMood = m.id; vm.chooseMood(m); requestEntry(true)
                } }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item(key = "status") {
            Column(Modifier.fillMaxWidth().padding(horizontal = TvMetrics.EdgeInset).focusRequester(footerFocus)
                .onFocusChanged { footerFocused = it.hasFocus }, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (vm.busy) CircularProgressIndicator(color = Accent)
                vm.error?.let { Message(it, "أعد المحاولة", { vm.reload() }) }
            }
        }
    }
    }
}

@Composable
private fun MoodCard(mood: Mood, modifier: Modifier, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, spring(stiffness = 400f), label = "Mood focus")
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.height(118.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .clip(shape).background(Brush.linearGradient(listOf(Color(mood.color), Panel)))
        .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
        .clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.Bottom) {
        Label(mood.title, 19, Color.White, true, lines = 1)
        Label(mood.subtitle, 12, Color.White.copy(alpha = .75f), lines = 2)
    }
}
