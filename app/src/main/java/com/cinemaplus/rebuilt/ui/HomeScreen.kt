package com.cinemaplus.rebuilt.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.Shelf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults

/*
 * Home. Performance rules that keep D-pad navigation smooth on weak devices:
 *  - the hero rotation index is a State object read only inside the hero composables, so a swap never
 *    recomposes the shelves;
 *  - the next backdrop is prefetched at the exact size the screen requests, so a swap never waits on I/O;
 *  - rotation pauses while the list scrolls or the user is below the hero;
 *  - the remembered card of the first shelf is a plain holder (no state), so focus moves recompose nothing.
 */

/** Backdrops are requested at one fixed size so the prefetched bitmap is the one the screen shows. */
private const val HeroImageWidth = 1280
private const val HeroImageHeight = 720
private const val HeroRotationMillis = 8_000L

private fun heroRequest(context: android.content.Context, url: String): ImageRequest =
    ImageRequest.Builder(context).data(url).size(HeroImageWidth, HeroImageHeight).scale(coil.size.Scale.FIT).crossfade(false).build()

private fun Film.heroArt(): String = backdrop.ifBlank { poster }

/** Not observable on purpose: remembering the card never triggers composition. */
private class ReturnMemory(var id: String? = null)
private val ReturnMemorySaver = listSaver<ReturnMemory, String>(
    save = { listOf(it.id.orEmpty()) },
    restore = { ReturnMemory(it[0].ifEmpty { null }) })

@Composable
fun HomeScreen(vm: CinemaViewModel) {
    val revision = vm.libraryRevision
    // Parsing the saved list on every recomposition made focus moves stutter; recompute only when it changes.
    val recent = remember(revision, vm.demo) { vm.library.recent().filter { it.id.startsWith("demo-") == vm.demo } }
    val rows = remember(vm.shelves, recent) {
        (if (recent.isNotEmpty()) listOf(Shelf("recent", "تابع المشاهدة", recent)) else emptyList()) +
            vm.shelves.filter { it.films.isNotEmpty() }
    }
    val featured = remember(vm.shelves) { featuredFilms(vm.shelves) }
    val heroIndex = remember { mutableIntStateOf(0) }
    // Bumped when the user changes the title by hand, which restarts the automatic timer.
    val nudge = remember { mutableIntStateOf(0) }
    val returnMemory = rememberSaveable(saver = ReturnMemorySaver) { ReturnMemory() }
    val heroFocus = remember { FocusRequester() }
    val firstCardFocus = remember { FocusRequester() }
    val rowEntries = remember { mutableMapOf<String, FocusRequester>() }
    val rowFocus = rows.mapIndexed { index, row -> if(index == 0) firstCardFocus else rowEntries.getOrPut(row.id) { FocusRequester() } }
    val railStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val rowStates = rows.map { railStates.getOrPut(it.id) { androidx.compose.foundation.lazy.LazyListState() } }
    val navigationJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val firstRowId = rows.firstOrNull()?.id
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The row the list is currently anchored on (-1 = the hero page). A plain holder: it never recomposes anything.
    val anchor = remember { intArrayOf(-1) }
    LaunchedEffect(featured) { vm.enrich(featured) }
    HeroTicker(featured, heroIndex, nudge, art = { vm.withArtwork(it).heroArt() }) { anchor[0] == -1 && !listState.isScrollInProgress }
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val density = LocalDensity.current
        // Every shelf rests near the top of the screen; the hero page is a full screen of its own above them.
        val otherRowGap = with(density) { (maxHeight * .14f).roundToPx() }
        val headItems = 1 + (if (vm.error != null) 1 else 0)
        // The artwork belongs to the hero page only: it fades away as the list scrolls to the shelves.
        Box(Modifier.fillMaxSize().graphicsLayer {
            alpha = if (listState.firstVisibleItemIndex > 0) 0f
                else (1f - listState.firstVisibleItemScrollOffset / (size.height * .7f)).coerceIn(0f, 1f)
        }) { HeroBackdropLayer(vm, featured, heroIndex, Modifier.fillMaxSize()) }
        WithoutFocusScroll {
        LazyColumn(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            val down = event.key == Key.DirectionDown
            if(!down && event.key != Key.DirectionUp) return@onPreviewKeyEvent false
            val destination = adjacentTvSection(anchor[0] + 1, rows.size + 1, down) ?: return@onPreviewKeyEvent false
            if(event.type == KeyEventType.KeyDown && navigationJob[0]?.isActive != true) {
                navigationJob[0] = scope.launch {
                    val previous = anchor[0]
                    anchor[0] = destination - 1
                    val success = scrollToTvFocus(listState, if(destination == 0) 0 else headItems + destination - 1,
                        if(destination == 0) heroFocus else rowFocus[destination - 1],
                        if(destination == 0) null else rowStates[destination - 1])
                    if(!success) anchor[0] = previous
                }
            }
            true
        }, state = listState, contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap)) {
            item(key = "hero", contentType = "hero") {
                // The whole page is one focus target: Left / Right change the title, OK opens its page, Down reaches the shelves.
                Box(Modifier.fillParentMaxSize()
                    .focusRequester(heroFocus)
                    .focusProperties { if (rows.isNotEmpty()) down = firstCardFocus }
                    .onPreviewKeyEvent { e ->
                        val left = e.key == Key.DirectionLeft
                        val right = e.key == Key.DirectionRight
                        val count = featured.size
                        when {
                            !(left || right) || count < 2 -> false
                            // Right means "previous" in this right-to-left layout; at the first title it leads on to the side rail.
                            right && heroIndex.intValue == 0 -> false
                            else -> {
                                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                                    heroIndex.intValue = (heroIndex.intValue + (if (left) 1 else -1) + count) % count
                                    nudge.intValue++
                                }
                                true
                            }
                        }
                    }
                    .onFocusChanged {
                        if (it.isFocused && anchor[0] != -1) {
                            anchor[0] = -1
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        featured.getOrNull(heroIndex.intValue)?.let(vm::open)
                    }) {
                    HeroInfoBlock(vm, featured, heroIndex,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .padding(start = TvMetrics.EdgeInset, end = TvMetrics.EdgeInset, bottom = 30.dp))
                }
            }
            if (vm.busy && featured.isEmpty() && rows.isEmpty()) item(contentType = "skeleton") { ShelfSkeleton() }
            vm.error?.let { error -> item(contentType = "error") {
                Column(Modifier.padding(horizontal = TvMetrics.EdgeInset), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Label(error, 14, Muted, lines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action("إعادة المحاولة", true) { vm.reload() }
                        Action("تجربة التصميم") { vm.changeDemo(true) }
                    }
                }
            } }
            itemsIndexed(rows, key = { _, shelf -> shelf.id }, contentType = { _, _ -> "shelf" }) { index, shelf ->
                // Continue watching uses landscape cards; the other shelves are posters with their details.
                ContentRailRow(shelf, vm, wide = shelf.id == "recent",
                    firstRequester = rowFocus[index], rowState = rowStates[index],
                    heroFocus = if (shelf.id == firstRowId) heroFocus else null,
                    returnId = returnMemory.id,
                    onFocused = {
                        if (shelf.id == firstRowId) returnMemory.id = it.id
                        if (anchor[0] != index) {
                            anchor[0] = index
                            scope.launch { listState.animateScrollToItem(headItems + index, -otherRowGap) }
                        }
                    })
            }
            if (!vm.busy && vm.error == null && featured.isEmpty() && rows.isEmpty()) item(contentType = "empty") {
                Label("لا يوجد محتوى متاح حاليًا", 17, Muted, modifier = Modifier.padding(horizontal = TvMetrics.EdgeInset))
            }
        }
        }
    }
}

/**
 * Hero titles: the most popular (top rated) interleaved with the newest, all with a real backdrop.
 * Posters are only used when the source offers too few backdrops.
 */
private fun featuredFilms(shelves: List<Shelf>): List<Film> {
    val all = shelves.flatMap { it.films }.distinctBy { it.id }
    val withBackdrop = all.filter { it.backdrop.isNotBlank() }
    val pool = if (withBackdrop.size >= 3) withBackdrop else all.filter { it.heroArt().isNotBlank() }
    val popular = pool.sortedByDescending { it.rating.toFloatOrNull() ?: 0f }.take(6)
    val newest = pool.filter { it !in popular }.sortedByDescending { it.year.toIntOrNull() ?: 0 }.take(4)
    val mixed = popular.flatMapIndexed { i, film -> listOfNotNull(film, newest.getOrNull(i)) }
    return mixed + newest.drop(popular.size)
}

/**
 * Advances the hero on a timer; prefetches the neighbouring images first and never swaps while the user is
 * on a shelf or scrolling. A manual change restarts the timer.
 */
@Composable
private fun HeroTicker(featured: List<Film>, heroIndex: MutableIntState, nudge: IntState, art: (Film) -> String,
    isActive: () -> Boolean) {
    val context = LocalContext.current
    val latestActive by rememberUpdatedState(isActive)
    LaunchedEffect(featured) {
        heroIndex.intValue = 0
        if (featured.size < 2) return@LaunchedEffect
        snapshotFlow { nudge.intValue }.collectLatest {
            while (true) {
                val count = featured.size
                for (step in intArrayOf(1, count - 1)) {
                    val url = art(featured[(heroIndex.intValue + step) % count])
                    if (url.isNotBlank()) context.imageLoader.enqueue(heroRequest(context, url))
                }
                delay(HeroRotationMillis)
                while (!latestActive()) delay(500)
                heroIndex.intValue = (heroIndex.intValue + 1) % count
            }
        }
    }
}

@Composable
private fun HeroBackdropLayer(vm: CinemaViewModel, featured: List<Film>, heroIndex: IntState, modifier: Modifier) {
    HeroBackdrop(featured.getOrNull(heroIndex.intValue)?.let(vm::withArtwork), modifier)
}

/**
 * Wide artwork fills the screen. Artwork that is not wide (a poster) is shown whole at the full height of the
 * screen, centred, with a dimmed enlarged copy filling the sides, so nothing is cropped away or stretched.
 */
@Composable
private fun HeroArt(request: ImageRequest) {
    var ratio by remember(request) { mutableFloatStateOf(0f) }
    val framed = ratio in 0.1f..1.55f
    Box(Modifier.fillMaxSize().then(if (framed) Modifier.background(Ink) else Modifier)) {
        // The dimmed side fill only needs the small, already cached decode.
        if (framed) AsyncImage(request, null, Modifier.fillMaxSize().graphicsLayer { alpha = .30f }, contentScale = ContentScale.Crop)
        // A poster is shown at the full screen height, so it is decoded at a larger size than a wide backdrop.
        val sharp = remember(request, framed) {
            if (framed) request.newBuilder().size(1080, 1620).scale(coil.size.Scale.FIT).build() else request
        }
        AsyncImage(sharp, null,
            if (framed) Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(ratio)
            else Modifier.fillMaxSize(),
            contentScale = if (framed) ContentScale.Fit else ContentScale.Crop,
            onSuccess = { state ->
                val art = state.result.drawable
                ratio = art.intrinsicWidth.toFloat() / art.intrinsicHeight.coerceAtLeast(1)
            })
    }
}

@Composable
fun HeroBackdrop(film: Film?, modifier: Modifier = Modifier, sideScrim: Boolean = true, fadeFrom: Float = .45f) {
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier) {
        Crossfade(targetState = film?.heroArt().orEmpty(), animationSpec = tween(600), label = "Hero artwork") { url ->
            if (url.isNotBlank()) HeroArt(heroRequest(context, url))
        }
        // One cached draw pass for both scrims: a shade on the text side and a fade into the page below.
        Box(Modifier.fillMaxSize().drawWithCache {
            val edge = Color.Black.copy(alpha = .78f)
            val side = Brush.horizontalGradient(
                if (rtl) listOf(Color.Transparent, Color.Black.copy(alpha = .15f), edge)
                else listOf(edge, Color.Black.copy(alpha = .15f), Color.Transparent))
            val shade = if (sideScrim) 1f else 0f
            val tint = AccentDeep.copy(alpha = .55f).compositeOver(Ink.copy(alpha = .85f))
            val bottom = Brush.verticalGradient(0f to Color.Transparent, fadeFrom to Color.Transparent,
                (fadeFrom + .35f).coerceAtMost(.95f) to tint, 1f to Ink)
            onDrawBehind { if (shade > 0f) drawRect(side); drawRect(bottom) }
        })
    }
}

/** Details of the current hero title. Reads the rotation index here, so only this block updates on a swap. */
@Composable
private fun HeroInfoBlock(vm: CinemaViewModel, featured: List<Film>, heroIndex: IntState, modifier: Modifier) {
    val hero = featured.getOrNull(heroIndex.intValue)?.let(vm::withArtwork)
    var about by remember(hero?.id) { mutableStateOf(hero?.description.orEmpty()) }
    LaunchedEffect(hero?.id) {
        if (hero != null && about.isBlank()) about = runCatching { vm.describe(hero) }.getOrDefault("")
    }
    if (hero == null) {
        if (vm.busy) HeroSkeleton(modifier)
        return
    }
    Crossfade(hero, modifier, animationSpec = tween(300), label = "Hero info") { film ->
        HeroPosterInfo(film, if (film.id == hero.id) about else film.description,
            featured.indexOfFirst { it.id == film.id } + 1, featured.size)
    }
}

/** Title, badges and "open" cue on one side; rating, facts and story beside them. No frame: it floats over the artwork. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroPosterInfo(film: Film, about: String, position: Int, total: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.width(470.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KindBadge(film.series, size = 12)
            Label(mediaTitle(film), 38, Color.White, true, lines = 2)
            if (film.enTitle.isNotBlank() && film.enTitle != mediaTitle(film)) Label(film.enTitle, 15, Muted, lines = 1)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.clip(CircleShape).background(Accent).padding(horizontal = 22.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(22.dp), tint = OnAccent)
                Label(if (film.series) "افتح المسلسل" else "افتح الفيلم", 16, OnAccent, true, lines = 1)
            }
            if (total > 1) Label("$position / $total  •  يمين ويسار للتنقل بين الأعمال", 12, Muted, lines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Only facts present in the model are shown; nothing is invented.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RatingChip(film.rating, size = 13)
                InfoChip(film.year, size = 13); InfoChip(formatContentDuration(film.duration), size = 13)
            }
            if (about.isNotBlank()) Text(about, color = Color.White.copy(alpha = .88f), fontFamily = Cairo,
                fontSize = 15.sp, lineHeight = 23.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            val languages = film.subtitles.map { it.title }.distinct().take(3)
            if (languages.isNotEmpty()) Label("الترجمة: " + languages.joinToString(" · "), 12, Muted, lines = 1)
        }
    }
}

/** Grey placeholder blocks shown instead of a loading message. */
@Composable
private fun skeletonColor(): Color {
    val pulse by rememberInfiniteTransition(label = "Skeleton").animateFloat(.07f, .17f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "Skeleton alpha")
    return Color.White.copy(alpha = pulse)
}

@Composable
private fun HeroSkeleton(modifier: Modifier) {
    val block = skeletonColor()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(220.dp).height(34.dp).clip(RoundedCornerShape(8.dp)).background(block))
        Box(Modifier.fillMaxWidth(.8f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
        Box(Modifier.fillMaxWidth(.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
        Box(Modifier.width(130.dp).height(40.dp).clip(CircleShape).background(block))
    }
}

@Composable
private fun ShelfSkeleton() {
    val block = skeletonColor()
    Row(Modifier.padding(horizontal = TvMetrics.EdgeInset, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap)) {
        repeat(4) { Box(Modifier.width(TvMetrics.ContinueWidth).aspectRatio(TvMetrics.LandscapeRatio)
            .clip(RoundedCornerShape(TvMetrics.CardRadius)).background(block)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeroContentInfo(film: Film, about: String = film.description) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Only facts present in the model are shown; nothing is invented.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KindBadge(film.series, size = 12)
            RatingChip(film.rating, size = 12)
            InfoChip(film.year); InfoChip(formatContentDuration(film.duration))
        }
        Text(mediaTitle(film), color = Color.White, fontFamily = Cairo, fontWeight = FontWeight.Bold,
            fontSize = 40.sp, lineHeight = 48.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (film.enTitle.isNotBlank() && film.enTitle != mediaTitle(film))
            Label(film.enTitle, 15, Muted, lines = 1)
        if (about.isNotBlank()) Text(about, color = Color.White.copy(alpha = .86f),
            fontFamily = Cairo, fontSize = 15.sp, lineHeight = 23.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val languages = film.subtitles.map { it.title }.distinct().take(3)
        if (languages.isNotEmpty()) Label("الترجمة: " + languages.joinToString(" · "), 13, Muted, lines = 1)
    }
}

@Composable
fun HeroActionButtons(film: Film, saved: Boolean, playFocus: FocusRequester, belowTarget: FocusRequester?,
    onPlay: () -> Unit, onWatchlist: () -> Unit, onDetails: () -> Unit) {
    // Down always lands on the remembered card of the first shelf instead of the nearest one.
    val down = Modifier.focusProperties { if (belowTarget != null) this.down = belowTarget }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        HeroButton(if (film.series) "الحلقات" else "شاهد الآن", Icons.Rounded.PlayArrow, true,
            Modifier.focusRequester(playFocus).then(down), onPlay)
        HeroButton(if (saved) "إزالة من قائمتي" else "إضافة إلى قائمتي", if (saved) Icons.Rounded.Check else Icons.Rounded.Add, false,
            down, onWatchlist)
        HeroButton("التفاصيل", Icons.Rounded.Info, false, down, onDetails)
    }
}

/** Primary = gold pill with label. Secondary = round glass button. Focus lifts and turns solid white or light gold. */
@Composable
internal fun HeroButton(label: String, icon: ImageVector, primary: Boolean,
    modifier: Modifier, onClick: () -> Unit, focusScale: Float = 1.08f, labelled: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium), label = "Hero button scale")
    val pill = primary || labelled
    val shape = if (pill) RoundedCornerShape(50) else CircleShape
    // No drop shadow: it is expensive to draw on weak GPUs; the colour change and scale carry the focus.
    TvButton(onClick, modifier.height(48.dp).then(if (pill) Modifier else Modifier.width(48.dp))
        .onFocusChanged { focused = it.isFocused }.zIndex(if (focused) 10f else 0f)
        .semantics { contentDescription = label }
        .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = TvButtonDefaults.shape(shape),
        scale = TvButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        colors = TvButtonDefaults.colors(
            containerColor = if (primary) Accent else Color.White.copy(alpha = .20f),
            contentColor = if (primary) OnAccent else Color.White,
            focusedContainerColor = if (primary) AccentLight else Color.White, focusedContentColor = if (primary) OnAccent else Color.Black),
        contentPadding = PaddingValues(horizontal = if (primary) 28.dp else if (labelled) 22.dp else 0.dp, vertical = 8.dp)) {
        Icon(icon, contentDescription = label, Modifier.size(if (primary) 24.dp else 22.dp))
        if (pill) { Spacer(Modifier.width(10.dp)); Label(label, 16, if (primary || focused) OnAccent else Color.White, true) }
    }
}

/** Round icon button with its label beside it (used on the details page). */
@Composable
internal fun RoundLabelButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroButton(label, icon, false, modifier.onFocusChanged { focused = it.isFocused }, onClick)
        Label(label, 14, if (focused) Color.White else Color.White.copy(alpha = .75f), focused, lines = 1)
    }
}

@Composable
fun ContentRailRow(shelf: Shelf, vm: CinemaViewModel, firstRequester: FocusRequester?,
    heroFocus: FocusRequester?, returnId: String? = null, wide: Boolean = true,
    rowState: androidx.compose.foundation.lazy.LazyListState? = null, onFocused: (Film) -> Unit) {
    TvShelfRow(shelf, vm, wide = wide || shelf.id == "recent", firstRequester = firstRequester, suppliedRowState = rowState,
        cardModifier = { Modifier.focusProperties { if (heroFocus != null) up = heroFocus } },
        onFilmFocused = onFocused,
        onCardLongClick = if (shelf.id == "recent") { film -> vm.showRecentOptions(film) } else null,
        onCardClick = { if (shelf.id == "recent") vm.play(it) else vm.open(it) })
}

/** Shared card implementation preserves progress, centered scrolling and restored focus. */
@Composable
fun LandscapeMediaCard(film: Film, progress: Float = 0f, modifier: Modifier = Modifier,
    onFocused: (Film) -> Unit = {}, onClick: () -> Unit) {
    FilmCard(film, progress, wide = true, modifier = modifier, onFocused = onFocused, click = onClick)
}
