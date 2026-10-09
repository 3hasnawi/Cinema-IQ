package com.cinemaplus.rebuilt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
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
    BackHandler(enabled = mood != null) { vm.chooseMood(null) }
    // Back from a mood puts focus on the card that was opened, not on the side rail.
    val cardFocus = remember { mutableMapOf<String, FocusRequester>() }
    var openedMood by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mood) {
        val id = openedMood
        if (mood == null && id != null) {
            delay(80)
            if (runCatching { cardFocus[id]?.requestFocus() }.getOrNull() == null) { delay(200); runCatching { cardFocus[id]?.requestFocus() } }
            openedMood = null
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = TvMetrics.TabBarSpace, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap)) {
        item(key = "title") {
            PageHeader(mood?.title ?: "استكشاف", mood?.subtitle ?: "اختر مزاجًا وتصفّح أعمالًا تشبهه",
                Modifier.padding(horizontal = TvMetrics.EdgeInset))
        }
        if (mood == null) {
            items(Moods.all.chunked(4), key = { it.first().id }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = TvMetrics.EdgeInset), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { m -> MoodCard(m, Modifier.weight(1f).focusRequester(cardFocus.getOrPut(m.id) { FocusRequester() })) { openedMood = m.id; vm.chooseMood(m) } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            items(Moods.shelves(mood, vm.shelves), key = { it.id }) { shelf -> TvShelfRow(shelf, vm, wide = false) }
        }
        item(key = "status") {
            Column(Modifier.fillMaxWidth().padding(horizontal = TvMetrics.EdgeInset), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (vm.busy) CircularProgressIndicator(color = Accent)
                vm.error?.let { Message(it, "أعد المحاولة", { vm.reload() }) }
                if (mood != null && !vm.busy && vm.error == null && Moods.shelves(mood, vm.shelves).isEmpty())
                    Label("لا توجد تصنيفات مطابقة لهذا المزاج في المصدر", 17, Muted)
            }
        }
    }
}

@Composable
private fun MoodCard(mood: Mood, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, spring(stiffness = 400f), label = "Mood focus")
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.height(118.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.isFocused }
        .clip(shape).background(Brush.linearGradient(listOf(Color(mood.color), Panel)))
        .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
        .clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.Bottom) {
        Label(mood.title, 19, Color.White, true, lines = 1)
        Label(mood.subtitle, 12, Color.White.copy(alpha = .75f), lines = 2)
    }
}
