@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.cinemaplus.rebuilt.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.data.Film
import com.cinemaplus.rebuilt.data.Shelf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults

/** Apple TV style metrics: generous safe margins, 16:9 shelves, 2:3 posters. */
object TvMetrics {
    val PosterWidth = 160.dp
    val PosterHeight = 240.dp
    val ContinueWidth = 288.dp
    val ContinueHeight = 162.dp
    const val PosterRatio = 2f / 3f
    const val LandscapeRatio = 16f / 9f
    const val CardFocusScale = 1.08f
    const val ButtonFocusScale = 1.06f
    val CardRadius = 14.dp
    val CardGap = 16.dp
    val RowGap = 12.dp
    val EdgeInset = 48.dp
    /** Space reserved at the top of every non-home page above page titles (the section rail is at the side). */
    val TabBarSpace = 24.dp
    /** Browse and details pages are laid out at this fraction of the device density so more content fits. */
    /** Collapsed (icons only) and expanded (icons + labels) width of the section rail. */
    val RailCollapsed = 84.dp
    val RailExpanded = 212.dp
    const val UiScale = 0.74f
    /** Text shrinks less than layout so it stays readable from the couch. */
    const val TextScale = 0.86f
}

/** Shrinks layout (dp) and text (sp) of a page. The player is not wrapped, so its controls keep full size. */
@Composable
fun TvScaled(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val scaled = remember(base.density, base.fontScale) {
        Density(base.density * TvMetrics.UiScale, base.fontScale * (TvMetrics.TextScale / TvMetrics.UiScale))
    }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/** Focus state of the side section rail: it widens to show labels while focus is inside it. */
@Stable
class TabBarState {
    var hasFocus by mutableStateOf(false)
}

val LocalTabBarState = staticCompositionLocalOf { TabBarState() }

/** Translucent "material" used for tvOS style pills and panels. */
val Glass = Color.White.copy(alpha = .30f)
val GlassStrong = Color(0xF21E1722)

/** Settings switch for the trailer on focused cards. */
val LocalTrailerPreviewEnabled = compositionLocalOf { true }

// Focus-driven scrolling has a single owner. RTL offsets are handled by Foundation.
private object CenteredTvBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - (containerSize - size) / 2f
}

/** Vertical lists use this so that focus never scrolls them by itself; the screen scrolls explicitly, once per row. */
private object NoFocusScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
fun WithoutFocusScroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoFocusScroll, content = content)
}

/** Glass pill button. Primary = white fill, secondary = translucent. Focus lifts and turns white. */
@Composable
fun TvActionButton(
    text: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) TvMetrics.ButtonFocusScale else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "TV action focus"
    )
    val shape = RoundedCornerShape(50)
    val light = primary || focused
    TvButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).zIndex(if (focused) 10f else 0f)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale },
        scale = TvButtonDefaults.scale(scale = 1f, focusedScale = 1f, pressedScale = 1f),
        colors = TvButtonDefaults.colors(
            containerColor = if (primary) Accent else Glass,
            contentColor = if (primary) OnAccent else Color.White,
            focusedContainerColor = if (primary) AccentLight else Color.White,
            focusedContentColor = if (primary) OnAccent else Color.Black
        ),
        shape = TvButtonDefaults.shape(shape = shape),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp)
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Label(text, 14, if (light) OnAccent else Color.White, true, lines = 1)
    }
}

private const val TrailerLookupDelayMillis = 800L

/**
 * Cards no longer grow into a trailer preview. Growing the focused card relaid out the whole row on every frame,
 * which made neighbouring cards shift and the row shake. Focus is now shown with a ring and a small scale only,
 * both drawn in the graphics layer. The preview code stays for a future overlay-based design.
 */
private const val CardExpansionEnabled = false

/**
 * Remembers which card of a shelf had focus so it can be restored after returning from another screen.
 * Deliberately not observable: updating it never recomposes anything.
 */
class RowMemory(var focusedId: String? = null, var returning: Boolean = false)
private val RowMemorySaver = listSaver<RowMemory, Any>(
    save = { listOf(it.focusedId.orEmpty(), it.returning) },
    restore = { RowMemory((it[0] as String).ifEmpty { null }, it[1] as Boolean) })

/**
 * Poster / landscape card. Built so that moving focus costs almost nothing:
 *  - focus is read only inside draw and layer blocks and inside an effect, never during composition, so the
 *    card body does not recompose when focus moves;
 *  - one animation value drives the scale, the ring and the caption together;
 *  - there is no drop shadow (expensive on weak GPUs); a gold ring and a small scale mark the focus.
 * With [preview] on, a card that keeps focus for [TrailerDwellMillis] smoothly expands and plays its trailer.
 */
@Composable
fun FilmCard(
    f: Film,
    progress: Float = 0f,
    wide: Boolean = false,
    width: Dp = if (wide) TvMetrics.ContinueWidth else TvMetrics.PosterWidth,
    modifier: Modifier = Modifier,
    onFocused: (Film) -> Unit = {},
    requester: FocusRequester? = null,
    restoreFocus: Boolean = false,
    preview: Boolean = false,
    describe: (suspend (Film) -> String)? = null,
    onLongClick: (() -> Unit)? = null,
    click: () -> Unit
) {
    val trailerEnabled = LocalTrailerPreviewEnabled.current
    var about by remember(f.id) { mutableStateOf(f.description) }
    var focused by remember(f.id) { mutableStateOf(false) }
    var expanded by remember(f.id) { mutableStateOf(false) }
    var trailerIds by remember(f.id) { mutableStateOf<List<String>>(emptyList()) }
    var trailerIndex by remember(f.id) { mutableIntStateOf(0) }
    var trailerPlaying by remember(f.id) { mutableStateOf(false) }
    var trailerFailed by remember(f.id) { mutableStateOf(false) }
    var trailerStatus by remember(f.id) { mutableStateOf("") }
    val focusRequester = requester ?: remember(f.id) { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    val focusAnim = remember(f.id) { Animatable(0f) }
    val smooth = tween<Dp>(300, easing = FastOutSlowInEasing)
    val artHeight = if (wide) width / TvMetrics.LandscapeRatio else width * 1.5f
    // Posters grow into 16:9 at the same height; landscape cards grow 1.5x in both directions.
    val targetWidth = if (!expanded) width else if (wide) width * 1.5f else artHeight * TvMetrics.LandscapeRatio
    val targetHeight = if (expanded && wide) artHeight * 1.5f else artHeight
    // The expanding-card design is switched off, so plain values avoid four animation states on every card.
    val cardWidth: Dp
    val cardHeight: Dp
    val expandAlpha: Float
    val trailerAlpha: Float
    if (CardExpansionEnabled) {
        val animatedWidth by animateDpAsState(targetWidth, smooth, label = "TV card width")
        val animatedHeight by animateDpAsState(targetHeight, smooth, label = "TV card height")
        val animatedExpand by animateFloatAsState(if (expanded) 1f else 0f, tween(520), label = "TV card expand")
        val animatedTrailer by animateFloatAsState(if (trailerPlaying) 1f else 0f, tween(700), label = "TV trailer fade")
        cardWidth = animatedWidth; cardHeight = animatedHeight; expandAlpha = animatedExpand; trailerAlpha = animatedTrailer
    } else {
        cardWidth = width; cardHeight = artHeight; expandAlpha = 0f; trailerAlpha = 0f
    }
    val shape = RoundedCornerShape(TvMetrics.CardRadius)
    val image = if (wide || expanded) f.backdrop.ifBlank { f.poster } else f.poster.ifBlank { f.backdrop }
    val subtitle = if (f.episode > 0) listOf("الموسم ${f.season} • الحلقة ${f.episode}", formatContentDuration(f.duration)).filter(String::isNotBlank).joinToString(" • ")
    else listOf(if (f.series) "مسلسل" else "فيلم", f.year).filter(String::isNotBlank).joinToString(" • ")

    val continueStyle = wide && progress > 0f
    val continueStatus = if (!continueStyle) "" else {
        val total = if (':' in f.duration) f.duration.split(':').mapNotNull { it.trim().toLongOrNull() }.fold(0L) { t, p -> t * 60 + p }
            else f.duration.trim().toLongOrNull() ?: 0L
        val left = if (progress >= .95f) "على وشك الانتهاء"
            else if (total > 0) "متبقي " + formatContentDuration((total * (1f - progress)).toLong().toString()) else "تابع المشاهدة"
        val label = if (f.episode > 0) (if (f.season > 1) "الموسم ${f.season} • الحلقة ${f.episode}" else "الحلقة ${f.episode}") else ""
        listOf(label, left).filter(String::isNotBlank).joinToString(" • ")
    }

    // Focus changes are observed here, outside composition. The gold ring and scale animate immediately;
    // the trailer is resolved while the user dwells, so the card only expands when there is something to play.
    LaunchedEffect(f.id, preview, trailerEnabled) {
        snapshotFlow { focused }.collectLatest { isFocused ->
            launch { focusAnim.animateTo(if (isFocused) 1f else 0f, tween(140, easing = FastOutSlowInEasing)) }
            if (isFocused && preview && CardExpansionEnabled) {
                val ids: List<String> = if (trailerEnabled && !trailerFailed) coroutineScope {
                    // Network lookups start only after the card has settled, so fast scrolling never triggers them.
                    delay(TrailerLookupDelayMillis)
                    val lookup = async { TrailerResolver.candidates(f) }
                    delay((TrailerDwellMillis - TrailerLookupDelayMillis).coerceAtLeast(0L))
                    lookup.await()
                } else { delay(TrailerDwellMillis); emptyList() }
                trailerIds = ids; trailerIndex = 0; expanded = true
            } else { expanded = false; trailerPlaying = false; trailerIds = emptyList() }
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            // Keep the growing card on screen once the width animation has mostly settled.
            launch { delay(320); bringIntoView.bringIntoView() }
            if (about.isBlank() && describe != null) about = describe.invoke(f)
        }
    }

    Column(modifier.width(cardWidth).bringIntoViewRequester(bringIntoView)) {
        Box(
            Modifier.fillMaxWidth().height(cardHeight)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused(f)
                }
                .drawWithContent {
                    drawContent()
                    val p = focusAnim.value
                    if (p > 0.01f) {
                        val stroke = 3.dp.toPx()
                        drawRoundRect(Accent.copy(alpha = p), Offset(stroke / 2, stroke / 2),
                            Size(size.width - stroke, size.height - stroke),
                            CornerRadius(TvMetrics.CardRadius.toPx() - stroke / 2), style = Stroke(stroke))
                    }
                }
                .then(if (onLongClick != null) Modifier.combinedClickable(onClick = click, onLongClick = onLongClick)
                    else Modifier.clickable(onClick = click))
        ) {
            Box(Modifier.fillMaxSize().clip(shape).background(Panel)) {
                // The trailer sits underneath at full alpha (a WebView at alpha 0 may never start playing);
                // the artwork on top fades out once playback has really begun.
                val id = trailerIds.getOrNull(trailerIndex)
                // key() gives each candidate a fresh WebView.
                if (expanded && id != null) key(id) {
                    TrailerPreview(id, Modifier.fillMaxSize(),
                        onPlaying = { trailerPlaying = true; TrailerResolver.markWorking(f.id, id) },
                        onStatus = { trailerStatus = "(${trailerIndex + 1}/${trailerIds.size}) $it" },
                        onFailed = {
                            // Try the next search result when a video refuses to embed; give up after the last one.
                            if (trailerIndex + 1 < trailerIds.size) trailerIndex++
                            else {
                                trailerFailed = true
                                // Keep the card open and show the description instead of the trailer.
                                trailerIds = emptyList()
                            }
                        })
                }
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - trailerAlpha }) {
                    if (image.isNotBlank()) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Rounded.PlayArrow, null, Modifier.align(Alignment.Center).size(40.dp), tint = Color.White.copy(alpha = .5f))
                }
                if (!expanded) {
                    // Rating on the artwork's start corner; series carry their own tag unless this is an episode card.
                    RatingChip(f.rating, Modifier.align(Alignment.TopStart).padding(8.dp), 11)
                    if (f.series && f.episode == 0 && progress <= 0f)
                        KindBadge(true, Modifier.align(Alignment.BottomStart).padding(8.dp), 11)
                }
                if (TrailerDebug && expanded && trailerStatus.isNotEmpty() && !trailerPlaying)
                    Text(trailerStatus, Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = .75f)).padding(6.dp),
                        color = Color(0xFFFFD60A), fontSize = 9.sp, lineHeight = 11.sp, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (expandAlpha > 0.01f) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = expandAlpha }.background(Brush.verticalGradient(
                        0f to Color.Transparent, .55f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f))))
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().graphicsLayer { alpha = expandAlpha }
                        .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Label(mediaTitle(f), 16, Color.White, true, lines = 1)
                        Label(listOf(f.rating.takeIf(String::isNotBlank)?.let { "★ $it" }.orEmpty(), subtitle)
                            .filter(String::isNotBlank).joinToString("  •  "), 12, Color.White.copy(alpha = .85f), lines = 1)
                        if (!trailerPlaying && about.isNotBlank()) Label(about, 12, Color.White.copy(alpha = .8f), lines = if (wide) 3 else 4)
                    }
                }
                if (continueStyle && !expanded) {
                    // Continue-watching cards carry their own title and a small status pill inside the artwork.
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Transparent, .35f to Color.Transparent, 1f to Color.Black.copy(alpha = .88f))))
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label(mediaTitle(f), 16, Color.White, true, lines = 1)
                        Row(Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp), tint = Color.White)
                            Label(continueStatus, 12, Color.White, true, lines = 1)
                        }
                    }
                } else if (progress > 0f) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color.Transparent, .7f to Color.Transparent, 1f to Color.Black.copy(alpha = .7f))))
                }
                if (progress > 0f) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                        .height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = .3f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Accent))
                    }
                }
            }
        }
        // The caption brightens with the same animation value; the card never recomposes for it.
        if (!continueStyle) Column(Modifier.fillMaxWidth().graphicsLayer { alpha = .78f + .22f * focusAnim.value }
            .padding(top = 10.dp, start = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(mediaTitle(f), 14, Color.White, true, lines = 1)
            if (subtitle.isNotBlank()) Label(subtitle, 12, Muted, lines = 1)
        }
    }
    LaunchedEffect(restoreFocus) {
        if (restoreFocus) { delay(32); focusRequester.requestFocus() }
    }
}

@Composable
fun TvShelfRow(
    shelf: Shelf,
    vm: CinemaViewModel,
    wide: Boolean = false,
    onFilmFocused: (Film) -> Unit = {},
    width: Dp = if (wide) TvMetrics.ContinueWidth else TvMetrics.PosterWidth,
    firstRequester: FocusRequester? = null,
    /** Which card carries [firstRequester]; the first one unless the caller restores another. */
    firstRequesterIndex: Int = 0,
    entryRequester: FocusRequester? = null,
    suppliedRowState: LazyListState? = null,
    cardModifier: (Int) -> Modifier = { Modifier },
    preview: Boolean = true,
    onCardLongClick: ((Film) -> Unit)? = null,
    onCardClick: (Film) -> Unit = { vm.open(it) }
) {
    if (shelf.films.isEmpty()) return
    val localRowState = rememberLazyListState()
    val rowState = suppliedRowState ?: localRowState
    // Plain holder, not state: moving focus along the row must not recompose the cards.
    val memory = rememberSaveable(shelf.id, saver = RowMemorySaver) { RowMemory() }
    val describe = remember(vm) { val lookup: suspend (Film) -> String = { film -> vm.describe(film) }; lookup }
    // Warm the next cards' artwork while the viewer is still on the current ones, at the exact size the cards use.
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    LaunchedEffect(shelf.id, shelf.films.size, wide, width) {
        val imageLoader = coil.Coil.imageLoader(context)
        val widthPx = with(density) { width.roundToPx() }
        val heightPx = with(density) { (if (wide) width / TvMetrics.LandscapeRatio else width * 1.5f).roundToPx() }
        snapshotFlow { rowState.firstVisibleItemIndex }.collect { first ->
            val from = (first + 5).coerceAtMost(shelf.films.size)
            shelf.films.subList(from, (from + 6).coerceAtMost(shelf.films.size)).forEach { film ->
                val url = if (wide) film.backdrop.ifBlank { film.poster } else film.poster.ifBlank { film.backdrop }
                if (url.isNotBlank()) imageLoader.enqueue(coil.request.ImageRequest.Builder(context).data(url)
                    .size(widthPx, heightPx).scale(coil.size.Scale.FILL).build())
            }
        }
    }
    Column {
        SectionHeader(shelf.title, Modifier.padding(horizontal = TvMetrics.EdgeInset), count = shelf.films.size)
        CompositionLocalProvider(LocalBringIntoViewSpec provides CenteredTvBringIntoView) {
            // Vertical padding leaves room for the focus scale.
            LazyRow(state = rowState, contentPadding = PaddingValues(
                horizontal = TvMetrics.EdgeInset, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap),
                modifier = Modifier.fillMaxWidth().then(if(entryRequester != null) Modifier.focusRequester(entryRequester) else Modifier).focusGroup()) {
                itemsIndexed(shelf.films, key = { _, film -> film.id }, contentType = { _, _ -> "film-card" }) { index, film ->
                    FilmCard(film, vm.library.fraction(film.id), wide, width,
                        modifier = cardModifier(index),
                        requester = if (index == firstRequesterIndex) firstRequester else null,
                        restoreFocus = memory.returning && memory.focusedId == film.id,
                        preview = preview,
                        describe = describe,
                        onLongClick = onCardLongClick?.let { handler -> { handler(film) } },
                        onFocused = {
                            memory.focusedId = it.id; memory.returning = false
                            onFilmFocused(it)
                        }) { memory.returning = true; onCardClick(film) }
                }
            }
        }
    }
}

/** Poster grid used by Movies, Series, Library and Search. */
@Composable
fun PosterGrid(
    films: List<Film>,
    vm: CinemaViewModel,
    modifier: Modifier = Modifier,
    firstRequester: FocusRequester? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null
) {
    LazyVerticalGrid(
        GridCells.Adaptive(TvMetrics.PosterWidth + 20.dp), modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = TvMetrics.EdgeInset, end = TvMetrics.EdgeInset,
            top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalArrangement = Arrangement.spacedBy(TvMetrics.CardGap)
    ) {
        if (header != null) item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header() }
        items(films.size, key = { films[it].id }) { index ->
            val film = films[index]
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                FilmCard(film, vm.library.fraction(film.id), requester = if (index == 0) firstRequester else null) { vm.open(film) }
            }
        }
        if (footer != null) item(span = { GridItemSpan(maxLineSpan) }, key = "footer") { footer() }
    }
}
