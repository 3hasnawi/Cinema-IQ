package com.cinemaplus.rebuilt.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.KindFilter
import kotlinx.coroutines.delay

/** Letter sets of the on-screen keyboard; the toggle key cycles Arabic → digits → Latin. */
private enum class KeyboardMode(val keys: List<String>, val toggle: String) {
    Arabic(listOf("ا", "ب", "ت", "ث", "ج", "ح", "خ", "د", "ذ", "ر", "ز", "س", "ش", "ص", "ض", "ط",
        "ظ", "ع", "غ", "ف", "ق", "ك", "ل", "م", "ن", "ه", "و", "ي", "ة", "ى", "ء", "أ"), "١٢٣"),
    // Western digits: the sources match titles and years written with them.
    Digits(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "إ", "آ", "ؤ", "ئ", "-", ":"), "ABC"),
    // Many titles in the catalogue are English, so a Latin set is one press away.
    Latin(("abcdefghijklmnopqrstuvwxyz").map { it.toString() } + listOf("'", "-", "&", ".", ":", "!"), "أبت")
}

/**
 * TV search (05-search.png). Right half: the query field (display only, so the system keyboard never opens), an
 * on-screen keyboard of 8 columns, and recent searches. Left half: the result count, type filters and a 5-column
 * poster grid. The search runs 400 ms after the last key. Initial focus: the first key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvSearchScreen(vm: CinemaViewModel) {
    var typed by rememberSaveable { mutableStateOf(vm.query) }
    var mode by rememberSaveable { mutableStateOf(KeyboardMode.Arabic) }
    val firstKey = remember { FocusRequester() }
    // Coming back from a result's page lands on that result, not on the keyboard.
    var returnId by rememberSaveable { mutableStateOf<String?>(null) }
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (returnId != null && !awaitTvFocus { returnFocus }) returnId = null }
    RegisterPageEntry(if (returnId != null) returnFocus else firstKey)
    LaunchedEffect(typed) {
        delay(400)
        if (typed.trim() != vm.query) vm.search(typed)
    }
    val films = vm.filter.apply(vm.shelves.flatMap { it.films }.distinctBy { it.id })

    Row(Modifier.fillMaxSize().padding(start = 56.mx, end = 58.mx, top = 72.mx), horizontalArrangement = Arrangement.spacedBy(94.mx)) {
        Column(Modifier.width(640.mx), verticalArrangement = Arrangement.spacedBy(22.mx)) {
            Label("ابحث عن فيلم أو مسلسل", 12, Brand.TextTertiary, lines = 1)
            QueryField(typed)
            KeyGrid(mode.keys, firstKey) { typed += it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.mx)) {
                KeyboardKey("مسافة", Modifier.weight(4f), small = true) { if (typed.isNotEmpty() && !typed.endsWith(" ")) typed += " " }
                KeyboardKey("", Modifier.weight(2f), small = true, description = "حذف حرف، اضغط مطولًا لمسح الكل",
                    onLongClick = { typed = "" }, glyph = { BackspaceGlyph(it) }) { typed = typed.dropLast(1) }
                KeyboardKey(mode.toggle, Modifier.weight(2f), small = true) {
                    mode = KeyboardMode.entries[(mode.ordinal + 1) % KeyboardMode.entries.size]
                }
            }
            if (vm.searchHistory.isNotEmpty()) Column(Modifier.padding(top = 10.mx), verticalArrangement = Arrangement.spacedBy(14.mx)) {
                Label("عمليات البحث الأخيرة", 13, Brand.TextTertiary, true, lines = 1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.mx), verticalArrangement = Arrangement.spacedBy(12.mx)) {
                    vm.searchHistory.take(6).forEach { text -> SearchChip(text) { typed = text; vm.search(text) } }
                    SearchChip("مسح السجل", muted = true) { vm.clearSearchHistory() }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.mx)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.mx)) {
                    Label("النتائج", 19, Color.White, true, lines = 1)
                    if (vm.query.isNotBlank() && !vm.busy) Label("· ${films.size} عملاً", 13, Brand.TextTertiary, lines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.mx)) {
                    KindFilter.entries.forEach { kind ->
                        FilterPill(kind.label, vm.filter.kind == kind) { vm.filter = vm.filter.copy(kind = kind) }
                    }
                    FilterPill("الترتيب: ${vm.filter.sort.label}", false) { vm.cycleSort() }
                }
            }
            when {
                vm.busy && films.isEmpty() -> Label("جارٍ البحث…", 14, Brand.TextTertiary)
                vm.error != null -> Label(vm.error!!, 13, Brand.TextTertiary, lines = 2)
                vm.query.isBlank() -> Label("اكتب اسم العمل بلوحة المفاتيح، وتظهر النتائج هنا.", 14, Brand.TextTertiary, lines = 2)
                films.isEmpty() -> Label("لا توجد نتائج", 14, Brand.TextTertiary)
                else -> {
                    val cardWidth = 174.mx
                    LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.mx, bottom = 96.mx),
                        verticalArrangement = Arrangement.spacedBy(24.mx), horizontalArrangement = Arrangement.spacedBy(22.mx)) {
                        items(films, key = { it.id }) { film ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                FilmCard(film, vm.library.fraction(film.id), width = cardWidth, titleOnly = true,
                                    requester = if (film.id == returnId) returnFocus else null) { returnId = film.id; vm.open(film) }
                            }
                        }
                        if (!vm.demo) item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                            Box(Modifier.fillMaxWidth().padding(top = 12.mx), contentAlignment = Alignment.Center) {
                                SearchChip(if (vm.busy) "جارٍ التحميل…" else "عرض المزيد") { if (!vm.busy) vm.more() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Display-only query field: surface, radius 16, the typed text and an accent caret. */
@Composable
private fun QueryField(text: String) {
    val shape = RoundedCornerShape(16.mx)
    Row(Modifier.fillMaxWidth().height(84.mx).background(Brand.Surface, shape).border(3.mx, Color.White.copy(alpha = .12f), shape)
        .padding(horizontal = 24.mx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.mx)) {
        Icon(Icons.Rounded.Search, null, Modifier.size(32.mx), tint = Brand.TextTertiary)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (text.isEmpty()) Label("اسم الفيلم أو المسلسل", 17, Brand.TextMuted, lines = 1, modifier = Modifier.weight(1f, fill = false))
            else Label(text, 19, Color.White, true, lines = 1, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.padding(start = 4.mx).size(3.mx, 38.mx).background(Brand.Accent))
        }
    }
}

@Composable
private fun KeyGrid(keys: List<String>, firstKey: FocusRequester, onKey: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.mx)) {
        keys.chunked(8).forEachIndexed { row, line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.mx)) {
                line.forEachIndexed { column, key ->
                    KeyboardKey(key, Modifier.weight(1f).then(if (row == 0 && column == 0) Modifier.focusRequester(firstKey) else Modifier)) { onKey(key) }
                }
                // Short last rows keep the 8-column grid.
                repeat(8 - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Keyboard key: raised slate with a white glyph; focused it is white, scales 1.08 and gets the accent ring. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyboardKey(
    text: String, modifier: Modifier, small: Boolean = false, description: String? = null,
    onLongClick: (() -> Unit)? = null, glyph: (@Composable (Color) -> Unit)? = null, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(120), label = "Key focus")
    val radius = 12.mx
    val content = if (focused) Brand.Background else if (small) Brand.TextSecondary else Color.White
    Box(modifier.zIndex(if (focused) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale }
        .focusRingBehind(focused, radius, gap = 3.mx, ring = 3.mx)
        .height(68.mx).background(if (focused) Color.White else Brand.Raised, RoundedCornerShape(radius))
        .onFocusChanged { focused = it.isFocused }
        .combinedClickable(remember { MutableInteractionSource() }, null, onLongClick = onLongClick, onClick = onClick)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center) {
        if (glyph != null) glyph(content)
        else Label(text, if (small) 13 else 16, content, focused, lines = 1)
    }
}

/** Backspace glyph drawn in code: a left-pointing tag with an x. */
@Composable
private fun BackspaceGlyph(tint: Color) {
    Canvas(Modifier.size(36.mx, 26.mx)) {
        val w = size.width; val h = size.height; val s = 2.5f * density
        val tag = Path().apply {
            moveTo(w * .30f, s); lineTo(w - s, s); lineTo(w - s, h - s); lineTo(w * .30f, h - s); lineTo(s, h / 2); close()
        }
        drawPath(tag, tint, style = Stroke(s, join = StrokeJoin.Round))
        val cx = w * .62f; val cy = h / 2; val d = h * .2f
        drawLine(tint, Offset(cx - d, cy - d), Offset(cx + d, cy + d), s, StrokeCap.Round)
        drawLine(tint, Offset(cx - d, cy + d), Offset(cx + d, cy - d), s, StrokeCap.Round)
    }
}

/** Recent-search chip: 8 % white with a hairline; white with dark text when focused. */
@Composable
private fun SearchChip(text: String, muted: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.mx)
    Box(Modifier.height(48.mx).background(if (focused) Color.White else Color.White.copy(alpha = .08f), shape)
        .then(if (focused) Modifier else Modifier.border(1.dp, Brand.Divider, shape))
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
        .padding(horizontal = 20.mx), contentAlignment = Alignment.Center) {
        Label(text, 12, if (focused) Brand.Background else if (muted) Brand.TextTertiary else Brand.TextSecondary, lines = 1)
    }
}

/** Type filter: the selected one is a 14 % white pill; focused it is white with dark text. */
@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.mx)
    Box(Modifier.height(44.mx)
        .background(when { focused -> Color.White; selected -> Color.White.copy(alpha = .14f); else -> Color.Transparent }, shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
        .padding(horizontal = 20.mx), contentAlignment = Alignment.Center) {
        Label(text, 12, when { focused -> Brand.Background; selected -> Color.White; else -> Brand.TextTertiary }, selected || focused, lines = 1)
    }
}
