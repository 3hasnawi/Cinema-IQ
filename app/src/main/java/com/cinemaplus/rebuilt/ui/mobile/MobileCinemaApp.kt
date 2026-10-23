package com.cinemaplus.rebuilt.ui.mobile

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.cinemaplus.rebuilt.*
import com.cinemaplus.rebuilt.data.*
import com.cinemaplus.rebuilt.ui.*
import kotlinx.coroutines.delay

@Composable
fun MobileCinemaApp(vm: CinemaViewModel) {
    val activity = LocalContext.current as? Activity
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { vm.refreshFollowedSeries(); vm.checkUpdates() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val playback = vm.playing != null
    DisposableEffect(activity, playback) {
        activity?.requestedOrientation = if (playback) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.let {
            // Keep the display awake only while the phone player is mounted.
            if (playback) it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                if (playback) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    var compactBar by remember { mutableStateOf(false) }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -2f) compactBar = true
                else if (available.y > 2f) compactBar = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(vm.page) { compactBar = false }
    var exit by remember { mutableStateOf(false) }
    BackHandler(enabled = !playback) {
        when { vm.selected != null -> vm.backDetail(); vm.page == Page.Explore && vm.exploreMood != null -> vm.chooseMood(null); vm.page != Page.Home -> vm.backPage(); else -> exit = true }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalFilmTitle provides { film -> vm.withArtwork(film).displayTitle(vm.titleLanguage) }) {
        val typography = Typography().let { t -> t.copy(
            titleLarge = t.titleLarge.copy(fontFamily = Cairo), titleMedium = t.titleMedium.copy(fontFamily = Cairo),
            bodyLarge = t.bodyLarge.copy(fontFamily = Cairo), bodyMedium = t.bodyMedium.copy(fontFamily = Cairo),
            labelLarge = t.labelLarge.copy(fontFamily = Cairo), labelMedium = t.labelMedium.copy(fontFamily = Cairo),
            labelSmall = t.labelSmall.copy(fontFamily = Cairo)) }
        MaterialTheme(colorScheme = darkColorScheme(primary = Brand.Accent, onPrimary = Brand.OnAccent,
            background = Brand.Background, surface = Brand.Surface, onBackground = Color.White, onSurface = Color.White), typography = typography) {
            vm.recentOptions?.let { film ->
                AlertDialog(onDismissRequest = { vm.dismissRecentOptions() }, title = { Text(film.title, maxLines = 2) },
                    text = { Column {
                        TextButton(onClick = { vm.removeRecent(film) }) { Text("إزالة من تابع المشاهدة") }
                        TextButton(onClick = { vm.markWatched(film) }) { Text("تعليم كمشاهَد") }
                    } },
                    confirmButton = {}, dismissButton = { TextButton(onClick = { vm.dismissRecentOptions() }) { Text("إلغاء") } })
            }
            if (exit) AlertDialog(onDismissRequest = { exit = false }, title = { Text("الخروج من التطبيق؟") },
                confirmButton = { TextButton(onClick = { activity?.finish() }) { Text("خروج") } },
                dismissButton = { TextButton(onClick = { exit = false }) { Text("إلغاء") } })
            if (playback) MobilePlayerScreen(vm)
            else if (vm.selected != null) MobileDetails(vm)
            // Pages lay out under the status bar themselves (the home hero is full-bleed).
            else Box(Modifier.fillMaxSize().background(Brand.Background).nestedScroll(scrollConnection)) {
                when (vm.page) { Page.Settings -> MobileSettings(vm); Page.Explore -> MobileExplore(vm); else -> MobileBrowse(vm) }
                if (vm.page != Page.Settings) MobileGlassNavigation(vm, compactBar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MobileBrowse(vm: CinemaViewModel) {
    var typed by rememberSaveable { mutableStateOf(vm.query) }
    LaunchedEffect(typed, vm.page) {
        if (vm.page == Page.Search && typed.trim() != vm.query) { delay(400); vm.search(typed) }
    }
    val revision = vm.libraryRevision
    val recent = remember(revision) { vm.library.recent() }
    val libraryFilms = remember(revision, vm.librarySection) { vm.libraryItems() }
    val featured = remember(vm.shelves) { phoneFeaturedFilms(vm.shelves) }
    // Sharper artwork and missing synopses from TMDB for what is on screen first; bounded so a long list stays cheap.
    LaunchedEffect(featured, vm.shelves) {
        vm.enrich(featured + vm.shelves.take(4).flatMap { it.films.take(6) })
    }
    // Search and My List are 3-column poster grids; the other pages are shelves.
    val grid = vm.page == Page.Search || vm.page == Page.Library
    val gridFilms = when (vm.page) {
        Page.Library -> vm.filter.apply(libraryFilms)
        Page.Search -> vm.filter.apply(vm.shelves.flatMap { it.films }.distinctBy { it.id })
        else -> emptyList()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (vm.page == Page.Home && featured.isNotEmpty()) item(key = "hero") { MobileHero(vm, featured) { PhoneTopBar(vm) } }
        else item(key = "bar") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PhoneTopBar(vm)
                if (vm.page != Page.Home) PText(if (vm.page == Page.Search) "البحث" else vm.page.label, 28.sp, weight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                if (vm.page == Page.Library) PText(vm.librarySection.description, 13.sp, Brand.TextTertiary, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        if (vm.page == Page.Library) item(key = "sections") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibrarySection.entries.forEach { section -> PhoneFilterChip(section.label, vm.librarySection == section) { vm.chooseLibrarySection(section) } }
            }
        }
        if (vm.page == Page.Search) item(key = "field") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PhoneSearchField(typed, { typed = it }) { vm.search(typed) }
                if (typed.isBlank() && vm.searchHistory.isNotEmpty()) Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PText("عمليات البحث الأخيرة", 13.sp, Brand.TextTertiary, FontWeight.SemiBold, Modifier.weight(1f))
                        TextButton(onClick = { vm.clearSearchHistory() }) { PText("مسح", 13.sp, Brand.Accent) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.searchHistory.forEach { text -> PhoneFilterChip(text, false) { typed = text; vm.search(text) } }
                    }
                }
            }
        }
        if (grid && (gridFilms.isNotEmpty() || vm.filter.active)) item(key = "filters") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindFilter.entries.forEach { kind -> PhoneFilterChip(kind.label, vm.filter.kind == kind) { vm.filter = vm.filter.copy(kind = kind) } }
                PhoneFilterChip("الترتيب: ${vm.filter.sort.label}", false) { vm.cycleSort() }
            }
        }
        if (vm.page == Page.Search && vm.query.isNotBlank() && gridFilms.isNotEmpty()) item(key = "count") {
            PText("النتائج · ${gridFilms.size} عملاً", 13.sp, Brand.TextTertiary, modifier = Modifier.padding(horizontal = 20.dp))
        }
        if (vm.busy) item(key = "busy") { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Accent) } }
        vm.error?.let { message -> item(key = "error") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                PText(message, 14.sp, Brand.TextTertiary, lines = 3); TextButton(onClick = { vm.reload() }) { PText("إعادة المحاولة", 14.sp, Brand.Accent) }
            }
        } }
        if (grid) {
            items(gridFilms.chunked(3), key = { "row-" + it.first().id }) { row ->
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { film -> PhonePosterCard(vm.withArtwork(film), Modifier.weight(1f), width = null) { vm.open(film) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (!vm.busy && gridFilms.isEmpty()) item(key = "empty") {
                PText(when {
                    vm.page == Page.Library && vm.librarySection == LibrarySection.Watched -> "لا توجد أعمال مكتملة في سجل المشاهدة بعد."
                    vm.page == Page.Library -> "أضف أعمالاً من صفحة التفاصيل."
                    typed.isBlank() -> "اكتب اسم العمل للبحث"
                    else -> "لا توجد نتائج"
                }, 14.sp, Brand.TextTertiary, lines = 2, modifier = Modifier.padding(horizontal = 20.dp))
            }
        } else {
            if (vm.page == Page.Home && recent.isNotEmpty()) item(key = "recent") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhoneShelfTitle("متابعة المشاهدة")
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { it.id }) { film ->
                            PhoneContinueCard(vm.withArtwork(film), vm.library.fraction(film.id), onLongClick = { vm.showRecentOptions(film) }) { vm.play(film) }
                        }
                    }
                }
            }
            items(vm.shelves, key = { it.id }) { shelf ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhoneShelfTitle(shelf.title)
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(shelf.films, key = { it.id }) { film -> PhonePosterCard(vm.withArtwork(film)) { vm.open(film) } }
                    }
                }
            }
            if (!vm.busy && vm.shelves.isEmpty() && recent.isEmpty()) item(key = "empty") {
                PText("لا يوجد محتوى لعرضه", 14.sp, Brand.TextTertiary, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        if (vm.page in listOf(Page.Search, Page.Movies, Page.Series) && vm.shelves.any { it.films.isNotEmpty() }) item(key = "more") {
            TextButton(onClick = { if (!vm.busy) { vm.pageNumber++; vm.reload(append = true) } }, modifier = Modifier.fillMaxWidth()) {
                PText("عرض المزيد", 14.sp, Brand.Accent, FontWeight.SemiBold)
            }
        }
    }
}

/** Search field of the phone: surface, radius 12, accent border while focused, clear button; system keyboard. */
@Composable
private fun PhoneSearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(value, onChange, singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontFamily = Cairo),
        cursorBrush = SolidColor(Brand.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().height(48.dp).clip(shape).background(Brand.Surface)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) Brand.Accent else Brand.Divider, shape)
                .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = Brand.TextTertiary)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) PText("اسم الفيلم أو المسلسل", 15.sp, Brand.TextMuted)
                    inner()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "مسح", Modifier.size(18.dp), tint = Brand.TextTertiary) }
            }
        })
}

/**
 * Phone details (phone-png/02): 16:9 trailer or artwork with back and mute buttons, title, meta line, the resume
 * button with its progress strip, labelled actions, synopsis with "more", cast, then the episodes / similar tabs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MobileDetails(vm: CinemaViewModel) {
    val film = vm.selected?.let(vm::withArtwork) ?: return
    val revision = vm.libraryRevision
    val saved = remember(revision, film.id) { vm.library.favorites().any { it.id == film.id } }
    val season = vm.seasons.getOrNull(vm.seasonIndex)
    LaunchedEffect(film.id) { vm.loadDetailsCredits() }
    LaunchedEffect(film.id, season?.number) { if (season != null) vm.enrichSelectedSeason() }
    val credits = vm.detailsCredits
    // The episode Play starts: the one in progress, else the first not yet watched.
    val resume = remember(film.id, vm.seasons, revision) { if (film.series) vm.resumeEpisode(film) else null }
    val target = if (film.series) resume else film
    val started = target != null && vm.library.progress(target.id) > 0
    val playLabel = when {
        target == null -> "شاهد الآن"
        target.episode > 0 && started -> "تابع المشاهدة · م${target.season} ح${target.episode}"
        target.episode > 0 && (target.episode > 1 || target.season > 1) -> "شاهد الحلقة ${target.episode}"
        started -> "تابع المشاهدة"
        else -> "شاهد الآن"
    }
    val playProgress = remember(revision, target?.id) { target?.let { vm.library.fraction(it.id) } ?: 0f }
    var showTrailer by remember(film.id) { mutableStateOf(false) }
    val candidates = vm.shelves.map { shelf -> shelf.copy(films = shelf.films.map(vm::withArtwork)) }
    val related = phoneRelatedFilms(film, candidates)
    LaunchedEffect(film.id, vm.shelves) {
        vm.enrich(vm.shelves.flatMap { it.films }.filter { it.series == film.series && it.id != film.id }.distinctBy { it.id }.take(36))
    }
    if (showTrailer) MobileTrailer(film) { showTrailer = false }
    var tab by remember(film.id) { mutableIntStateOf(if (film.series) 0 else 1) }
    var expanded by remember(film.id) { mutableStateOf(false) }
    // A new list state per title, so opening a similar title starts at its top instead of the previous scroll offset.
    val listState = remember(film.id) { androidx.compose.foundation.lazy.LazyListState() }
    LazyColumn(Modifier.fillMaxSize().background(Brand.Background), state = listState, contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "top") { PhoneDetailsArtwork(film, vm.trailerPreview && !showTrailer) { vm.backDetail() } }
        item(key = "info") {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PText(mediaTitle(film), 26.sp, weight = FontWeight.Bold, lines = 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    PhoneRating(film.imdbRating.ifBlank { film.rating }, glass = false)
                    val seasons = if (film.series && vm.seasons.isNotEmpty())
                        (if (vm.seasons.size == 1) "موسم واحد" else if (vm.seasons.size == 2) "موسمان" else "${vm.seasons.size} مواسم") else ""
                    PhoneDotted(listOf(film.year, seasons, formatContentDuration(film.duration)))
                    listOf(film.contentQuality, film.edition).filter(String::isNotBlank).distinct().forEach { PhoneChip(it, ChipKind.Glass) }
                }
                PhonePrimaryButton(playLabel, Modifier.fillMaxWidth(), height = 48.dp, radius = 8.dp, progress = playProgress,
                    enabled = target != null) { target?.let(vm::play) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    PhoneLabeledAction(if (saved) Icons.Rounded.Check else Icons.Rounded.Add, "قائمتي") { vm.toggle(film) }
                    PhoneLabeledAction(TrailerIcon, "البرومو") { showTrailer = true }
                }
                val synopsis = film.description.ifBlank { if (vm.detailBusy) "جارٍ تحميل التفاصيل…" else "لا يتوفر وصف لهذا العمل." }
                Column(Modifier.clickable { expanded = !expanded }) {
                    PText(synopsis, 14.sp, Brand.TextSecondary, lines = if (expanded) Int.MAX_VALUE else 3)
                    if (synopsis.length > 120) PText(if (expanded) "أقل" else "المزيد", 14.sp, Color.White, FontWeight.SemiBold)
                }
                val castLine = when {
                    !credits?.cast.isNullOrEmpty() -> "بطولة: " + credits!!.cast.take(4).joinToString("، ") { it.name }
                    !credits?.directors.isNullOrEmpty() -> "إخراج: " + credits!!.directors.joinToString("، ")
                    !credits?.creators.isNullOrEmpty() -> "صنّاع العمل: " + credits!!.creators.joinToString("، ")
                    else -> ""
                }
                if (castLine.isNotBlank()) PText(castLine, 12.sp, Brand.TextTertiary, lines = 2)
                vm.detailError?.let { PText(it, 13.sp, Brand.Danger, lines = 2) }
            }
        }
        val tabs = buildList { if (film.series) add(0 to "الحلقات"); if (related.isNotEmpty()) add(1 to "أعمال مشابهة") }
        if (tabs.isNotEmpty()) item(key = "tabs") {
            Column(Modifier.padding(top = 10.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    tabs.forEach { (key, label) ->
                        val active = tab == key || tabs.size == 1
                        Column(Modifier.clickable { tab = key }, horizontalAlignment = Alignment.CenterHorizontally) {
                            PText(label, 14.sp, if (active) Color.White else Brand.TextTertiary, if (active) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(bottom = 8.dp))
                            Box(Modifier.height(3.dp).width(48.dp).background(if (active) Brand.Accent else Color.Transparent))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.Divider))
            }
        }
        val showEpisodes = film.series && (tab == 0 || related.isEmpty())
        if (showEpisodes) {
            if (vm.seasons.isNotEmpty()) item(key = "season") { PhoneSeasonPicker(vm) }
            val episodes = season?.episodes.orEmpty().map { it.copy(seriesId = film.id, season = season?.number ?: it.season) }
            items(episodes, key = { "ep-" + it.id }) { episode ->
                PhoneEpisodeRow(episode, vm, revision, film, setOf(film.title, film.enTitle, film.originalTitle)) { vm.play(episode) }
            }
            if (episodes.isEmpty() && !vm.detailBusy) item(key = "no-episodes") {
                PText("لا توجد حلقات متاحة لهذا الموسم.", 13.sp, Brand.TextTertiary, modifier = Modifier.padding(horizontal = 20.dp))
            }
            if (vm.detailBusy) item(key = "busy") { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Accent) } }
        } else if (related.isNotEmpty()) {
            items(related.chunked(3), key = { "rel-" + it.first().id }) { row ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { other -> PhonePosterCard(vm.withArtwork(other), Modifier.weight(1f), width = null) { vm.open(other) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 16:9 top of the details page: muted trailer (when enabled) over the artwork, back and mute buttons. */
@Composable
private fun PhoneDetailsArtwork(film: Film, trailerAllowed: Boolean, onBack: () -> Unit) {
    var ids by remember(film.id) { mutableStateOf<List<String>>(emptyList()) }
    var index by remember(film.id) { mutableIntStateOf(0) }
    var playing by remember(film.id) { mutableStateOf(false) }
    var muted by remember(film.id) { mutableStateOf(true) }
    LaunchedEffect(film.id, trailerAllowed) {
        playing = false
        ids = if (trailerAllowed) runCatching { TrailerResolver.candidates(film) }.getOrDefault(emptyList()) else emptyList()
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Brand.Raised)) {
        val id = ids.getOrNull(index)
        if (trailerAllowed && id != null) key(id, muted) {
            TrailerPreview(id, Modifier.fillMaxSize(), muted = muted,
                onPlaying = { playing = true; TrailerResolver.markWorking(film.id, id) },
                onFailed = { if (index + 1 < ids.size) index++ else ids = emptyList() })
        }
        if (!playing) AsyncImage(film.backdrop.ifBlank { film.poster }, mediaTitle(film), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(90.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Brand.Background))))
        Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp).size(36.dp).clip(CircleShape)
            .background(Brand.Background.copy(alpha = .6f)).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowForward, "رجوع", Modifier.size(20.dp), tint = Color.White)
        }
        if (playing) Box(Modifier.align(Alignment.BottomEnd).padding(start = 16.dp, end = 16.dp, bottom = 20.dp).size(32.dp).clip(CircleShape)
            .background(Brand.Background.copy(alpha = .6f)).border(1.dp, Brand.GlassStroke, CircleShape)
            .clickable { muted = !muted }, contentAlignment = Alignment.Center) {
            SpeakerGlyph(muted, Color.White)
        }
    }
}

/** Speaker drawn in code (the icon set has none); a slash when muted. */
@Composable
private fun SpeakerGlyph(muted: Boolean, tint: Color) {
    Canvas(Modifier.size(16.dp).semantics { contentDescription = if (muted) "تشغيل الصوت" else "كتم الصوت" }) {
        val w = size.width; val h = size.height
        val body = Path().apply {
            moveTo(w * .08f, h * .36f); lineTo(w * .30f, h * .36f); lineTo(w * .55f, h * .12f)
            lineTo(w * .55f, h * .88f); lineTo(w * .30f, h * .64f); lineTo(w * .08f, h * .64f); close()
        }
        drawPath(body, tint)
        val stroke = w * .09f
        if (muted) {
            drawLine(tint, Offset(w * .68f, h * .36f), Offset(w * .94f, h * .64f), stroke, StrokeCap.Round)
            drawLine(tint, Offset(w * .68f, h * .64f), Offset(w * .94f, h * .36f), stroke, StrokeCap.Round)
        } else drawArc(tint, -50f, 100f, false, Offset(w * .45f, h * .22f), Size(w * .42f, h * .56f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun PhoneLabeledAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 64.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = Color.White)
        PText(label, 12.sp)
    }
}

/** Season dropdown pill above the episode rows. */
@Composable
private fun PhoneSeasonPicker(vm: CinemaViewModel) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.dp)
    Box(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Row(Modifier.height(34.dp).clip(shape).background(Brand.Raised).border(1.dp, Brand.GlassStroke, shape)
            .clickable(enabled = vm.seasons.size > 1) { open = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PText("الموسم ${vm.seasons.getOrNull(vm.seasonIndex)?.number ?: 1}", 13.sp, weight = FontWeight.SemiBold)
            if (vm.seasons.size > 1) Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp), tint = Color.White)
        }
        DropdownMenu(open, { open = false }, Modifier.background(Brand.Surface)) {
            vm.seasons.forEachIndexed { index, value ->
                DropdownMenuItem(text = { PText("الموسم ${value.number}", 14.sp, if (index == vm.seasonIndex) Brand.Accent else Color.White) },
                    onClick = { vm.seasonIndex = index; open = false })
            }
        }
    }
}

/** Episode row: 128×72 thumbnail with progress, "episode N", its name and the duration. */
@Composable
private fun PhoneEpisodeRow(item: Film, vm: CinemaViewModel, revision: Int, series: Film, seriesTitles: Set<String>, onClick: () -> Unit) {
    var details by remember(item.id) { mutableStateOf(item) }
    LaunchedEffect(item.id) { details = vm.episodeCardDetails(item) }
    val progress = remember(revision, item.id) { vm.library.fraction(item.id) }
    val complete = remember(revision, item.id) { vm.library.isCompleted(item.id) }
    val image = vm.withArtwork(details).let { it.backdrop.ifBlank { it.poster } }.ifBlank { item.backdrop.ifBlank { item.poster.ifBlank { series.backdrop } } }
    val name = EpisodeLabels.title(item.episodeTitle.ifBlank { details.episodeTitle.ifBlank { details.title } }, seriesTitles)
    val duration = formatContentDuration(item.duration.ifBlank { details.duration })
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(8.dp)).background(Brand.Raised)) {
            if (image.isNotBlank()) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (complete) Icon(Icons.Rounded.CheckCircle, "تمت المشاهدة", Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp), tint = Color.White)
            if (progress > 0f && !complete) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .2f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Brand.Accent))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PText("الحلقة ${item.episode}", 14.sp, weight = FontWeight.Bold)
            if (name.isNotBlank()) PText(name, 12.sp, Brand.TextSecondary)
            if (duration.isNotBlank()) PText(duration, 12.sp, Brand.TextTertiary)
        }
    }
}

/** Phone settings (phone-png/04): opened from the avatar, grouped rounded lists, no bottom bar. */
@Composable
private fun MobileSettings(vm: CinemaViewModel) {
    var cloudDialog by remember { mutableStateOf(false) }
    if (cloudDialog) com.cinemaplus.rebuilt.cloud.CloudAccountDialog() { cloudDialog = false }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val switchProfile = com.cinemaplus.rebuilt.ui.LocalProfileSwitch.current
    val email = com.cinemaplus.rebuilt.cloud.CloudAccount.email
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.backPage() }) { Icon(Icons.Rounded.ArrowForward, "رجوع", tint = Color.White) }
                PText("الإعدادات", 22.sp, weight = FontWeight.Bold)
            }
        }
        item(key = "card") {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.Surface).clickable { switchProfile() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).background(Brand.BorderStrong, CircleShape), contentAlignment = Alignment.Center) {
                    PText(vm.profile.name.trim().take(1).ifBlank { "؟" }, 20.sp, weight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    PText(vm.profile.name, 16.sp, weight = FontWeight.Bold)
                    PText(email ?: "لم تسجّل الدخول إلى المزامنة", 12.sp, Brand.TextTertiary)
                }
                PText("تبديل الملف", 12.sp, Brand.Accent, FontWeight.SemiBold)
            }
        }
        item(key = "account") {
            PhoneGroup("الحساب", listOf<@Composable () -> Unit>(
                { PhoneRow("حساب Cinema iQ والمزامنة", email ?: "تسجيل الدخول") { cloudDialog = true } },
                {
                    if (vm.signedIn) PhoneRow("حساب سينمانا", "تم تسجيل الدخول", chevron = false)
                    else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PText("حساب سينمانا", 15.sp)
                        OutlinedTextField(username, { username = it }, label = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it }, label = { Text("كلمة المرور") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        Button(enabled = !vm.accountBusy, onClick = { vm.login(username, password); password = "" }) { Text(if (vm.accountBusy) "جارٍ تسجيل الدخول…" else "تسجيل الدخول") }
                        vm.accountError?.let { PText(it, 12.sp, Brand.Danger, lines = 2) }
                    }
                }))
        }
        item(key = "content") {
            PhoneGroup("المحتوى", SourceChoice.entries.map { choice ->
                @Composable { PhoneRow(choice.label, if (vm.sourceChoice == choice) "✓" else "", chevron = false, valueColor = Brand.Accent) { vm.chooseSource(choice) } }
            } + listOf<@Composable () -> Unit>(
                { PhoneRow("لغة أسماء الأعمال", vm.titleLanguage.label) { vm.chooseTitleLanguage(TitleLanguage.entries[(vm.titleLanguage.ordinal + 1) % TitleLanguage.entries.size]) } },
                { PhoneSwitchRow("وضع التجربة", vm.demo) { vm.changeDemo(it) } }))
        }
        item(key = "playback") {
            PhoneGroup("التشغيل", listOf<@Composable () -> Unit>(
                { PhoneRow("الجودة المفضلة", vm.quality) { val qualities = listOf("تلقائي", "1080p", "720p", "480p"); vm.chooseQuality(qualities[(qualities.indexOf(vm.quality) + 1) % qualities.size]) } },
                { PhoneSwitchRow("تشغيل الحلقة التالية تلقائيًا", vm.autoNext, vm::chooseAutoNext) },
                { PhoneSwitchRow("المشاهدة العائلية", vm.sceneFilter, vm::chooseSceneFilter) },
                { PhoneSwitchRow("تشغيل البرومو في الخلفية", vm.trailerPreview, vm::chooseTrailerPreview) },
                { PhoneSwitchRow("صوت البرومو", !vm.trailerMuted) { vm.chooseTrailerMuted(!it) } }))
        }
        item(key = "subtitles") {
            PhoneGroup("الترجمة", listOf<@Composable () -> Unit>(
                { PhoneRow("لغة الترجمة المفضلة", when (vm.subtitleLanguage) { "ar" -> "العربية"; "en" -> "الإنجليزية"; else -> "بدون ترجمة" }) {
                    val languages = listOf("ar", "en", "off"); vm.chooseSubtitleLanguage(languages[(languages.indexOf(vm.subtitleLanguage) + 1) % languages.size])
                } },
                { PhoneRow("حجم الترجمة", vm.subtitleSize.toInt().toString()) { vm.chooseSubtitleSize(if (vm.subtitleSize >= 30) 18f else vm.subtitleSize + 2) } },
                { PhoneRow("خلفية الترجمة", if (vm.subtitleBackground == 0f) "بدون خلفية" else "${(vm.subtitleBackground * 100).toInt()}%") {
                    vm.chooseSubtitleBackground(if (vm.subtitleBackground >= 1f) 0f else vm.subtitleBackground + .25f)
                } },
                { PhoneRow("ارتفاع الترجمة", "${(vm.subtitleHeight * 100).toInt()}%") { vm.chooseSubtitleHeight(if (vm.subtitleHeight >= .25f) .08f else vm.subtitleHeight + .04f) } }))
        }
        item(key = "about") {
            val about = mutableListOf<@Composable () -> Unit>()
            about += { PhoneRow(if (vm.updateBusy) "جارٍ الفحص أو التنزيل…" else "فحص التحديثات", vm.updateStatus ?: "") { if (!vm.updateBusy) vm.checkUpdates(manual = true) } }
            vm.availableUpdate?.let { update -> about += { PhoneRow("تنزيل وتثبيت ${update.versionName}", "", valueColor = Brand.Accent) { if (!vm.updateBusy) vm.downloadUpdate() } } }
            about += { PhoneRow("الإصدار", BuildConfig.VERSION_NAME, chevron = false) }
            about += { PhoneRow("التطوير", "Emad Hasnawi", chevron = false) }
            PhoneGroup("حول التطبيق", about)
            if (vm.updateBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = Brand.Accent)
        }
        if (vm.signedIn) item(key = "logout") {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.Surface).clickable(enabled = !vm.accountBusy) { vm.logout() }
                .padding(16.dp), contentAlignment = Alignment.Center) {
                PText(if (vm.accountBusy) "جارٍ تسجيل الخروج…" else "تسجيل الخروج من سينمانا", 15.sp, Brand.Danger, FontWeight.SemiBold)
            }
        }
        item(key = "version") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                PText("Cinema iQ · الإصدار ${BuildConfig.VERSION_NAME}", 12.sp, Brand.TextMuted)
                if (vm.artworkFromTmdb) PText("This product uses the TMDB API but is not endorsed or certified by TMDB.", 11.sp, Brand.TextMuted, lines = 2, align = TextAlign.Center)
            }
        }
    }
}

/** Grouped rounded list: surface, radius 12, hairline separators. */
@Composable
private fun PhoneGroup(title: String, rows: List<@Composable () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PText(title, 13.sp, Brand.TextTertiary, FontWeight.SemiBold, Modifier.padding(horizontal = 4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.Surface)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Brand.Divider))
                row()
            }
        }
    }
}

@Composable
private fun PhoneRow(title: String, value: String, chevron: Boolean = true, valueColor: Color = Brand.TextTertiary, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PText(title, 15.sp, modifier = Modifier.weight(1f), lines = 2)
        if (value.isNotBlank()) PText(value, 14.sp, valueColor)
        if (chevron && onClick != null) Icon(Icons.Rounded.KeyboardArrowLeft, null, Modifier.size(20.dp), tint = Brand.TextMuted)
    }
}

/** Switch row; in RTL the "on" thumb sits on the left, which Material's switch does on its own. */
@Composable
private fun PhoneSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        PText(title, 15.sp, modifier = Modifier.weight(1f), lines = 2)
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Brand.Accent,
            uncheckedThumbColor = Brand.TextSecondary, uncheckedTrackColor = Brand.BorderStrong, uncheckedBorderColor = Color.Transparent))
    }
}

/** Browse by mood on the phone: a two-column grid of moods, then the matching shelves. */
@Composable
private fun MobileExplore(vm: CinemaViewModel) {
    if(vm.sourceChoice == SourceChoice.DAWWIRLI) { MobileBrowse(vm); return }
    val mood = vm.exploreMood
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PhoneTopBar(vm) }
        item { PageHeader(mood?.title ?: "استكشاف", mood?.subtitle ?: pageSubtitle(Page.Explore), Modifier.padding(horizontal = 16.dp)) }
        if (mood == null) {
            items(com.cinemaplus.rebuilt.data.Moods.all.chunked(2), key = { it.first().id }) { row ->
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { m ->
                        Column(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(m.color), Brand.Surface)))
                            .clickable { vm.chooseMood(m) }.padding(14.dp), verticalArrangement = Arrangement.Bottom) {
                            PText(m.title, 16.sp, weight = FontWeight.Bold)
                            PText(m.subtitle, 11.sp, Brand.TextTertiary, lines = 2)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            val shelves = com.cinemaplus.rebuilt.data.Moods.shelves(mood, vm.shelves)
            items(shelves, key = { it.id }) { shelf ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PhoneShelfTitle(shelf.title)
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(shelf.films, key = { it.id }) { film -> PhonePosterCard(vm.withArtwork(film)) { vm.open(film) } }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (vm.busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Brand.Accent) }
                vm.error?.let { PText(it, 14.sp, Brand.TextTertiary, lines = 3) }
                if (mood != null && !vm.busy && vm.error == null && com.cinemaplus.rebuilt.data.Moods.shelves(mood, vm.shelves).isEmpty())
                    PText("لا توجد تصنيفات مطابقة لهذا المزاج في المصدر", 14.sp, Brand.TextTertiary, lines = 2)
            }
        }
    }
}
