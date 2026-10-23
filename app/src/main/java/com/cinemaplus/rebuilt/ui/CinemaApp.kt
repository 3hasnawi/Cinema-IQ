package com.cinemaplus.rebuilt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cinemaplus.rebuilt.*
import com.cinemaplus.rebuilt.R
import com.cinemaplus.rebuilt.data.*
import kotlinx.coroutines.delay
import androidx.tv.material3.MaterialTheme as TvTheme

val Ink = Brand.Background
val Panel = Brand.Surface
val PanelHi = Brand.Raised
/** Marquee gold: primary buttons, focus rings, selection, progress and ratings. */
val Accent = Brand.Accent
val AccentLight = Brand.Highlight
/** Text and icons drawn on top of [Accent]. */
val OnAccent = Brand.OnAccent
/** Deep plum that tints the bottom of hero artwork. */
val AccentDeep = Brand.Background
val InputBorder = Brand.BorderStrong
val Signal = Brand.Danger
val Muted = Brand.TextTertiary
val Cairo = FontFamily(Font(R.font.cairo_regular, FontWeight.Normal), Font(R.font.cairo_semibold, FontWeight.SemiBold), Font(R.font.cairo_bold, FontWeight.Bold))

/** App shell: shows the profile picker when several profiles exist, then runs one view model per profile. */
@Composable fun CinemaApp() {
    val context = LocalContext.current
    val cloudUid = com.cinemaplus.rebuilt.cloud.CloudAccount.uid
    val store = remember(cloudUid) { ProfileStore(context) }
    com.cinemaplus.rebuilt.cloud.CloudProfilesEffect(cloudUid)
    val television = com.cinemaplus.rebuilt.ui.mobile.usesTelevisionInterface(context)
    DisposableEffect(television) {
        val activity = context as? android.app.Activity
        activity?.requestedOrientation = if (television) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.let {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                if (television) hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                else show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
    var activeId by rememberSaveable(cloudUid) { mutableStateOf(store.activeId) }
    var picking by rememberSaveable(cloudUid) { mutableStateOf(store.profiles().size > 1) }
    val cloudProfilesRevision = com.cinemaplus.rebuilt.cloud.CloudProfileSync.revision
    LaunchedEffect(cloudProfilesRevision, cloudUid) { activeId = store.activeId }
    // The opening animation plays once per launch and always runs to its end; the app opens right after it.
    var introDone by rememberSaveable { mutableStateOf(false) }
    if (!introDone) {
        // Start the profile's data loading while the animation plays.
        if (!picking) viewModel<CinemaViewModel>(key = "profile-${cloudUid ?: "local"}-$activeId")
        AppIntro { introDone = true }
    } else if (picking) {
        ProfilePicker(store) { chosen -> store.activeId = chosen.id; activeId = chosen.id; picking = false }
    } else {
        // A separate view model per profile keeps lists, history and settings from leaking between viewers.
        val vm: CinemaViewModel = viewModel(key = "profile-${cloudUid ?: "local"}-$activeId")
        com.cinemaplus.rebuilt.cloud.CloudLibraryEffect(cloudUid, vm)
        // Another profile may have signed the shared account in or out while this one was in the background.
        LaunchedEffect(vm, cloudProfilesRevision) { vm.refreshAccount(); vm.refreshProfile(); vm.syncWatchNext() }
        CompositionLocalProvider(LocalProfileSwitch provides { picking = true }) { CinemaApp(vm, television) }
    }
}

@Composable fun CinemaApp(vm: CinemaViewModel,
    television: Boolean = com.cinemaplus.rebuilt.ui.mobile.usesTelevisionInterface(LocalContext.current)) {
    // A Watch Next card from the TV home screen opens straight into playback of that title.
    val launchContext = LocalContext.current
    val requested = LaunchRequest.pending
    LaunchedEffect(requested, vm) {
        if (requested != null) {
            val recent = vm.library.recent()
            val result = com.cinemaplus.rebuilt.data.WatchNextLaunchPolicy.resolve(
                requested.filmId, requested.profileId, vm.library.profileId, recent)
            when(result) {
                is com.cinemaplus.rebuilt.data.WatchNextLaunchPolicy.Result.Play -> vm.play(result.film)
                com.cinemaplus.rebuilt.data.WatchNextLaunchPolicy.Result.WrongProfile ->
                    android.widget.Toast.makeText(launchContext, "هذا العمل يخص ملفاً شخصياً آخر. اختر ملفه لبدء المشاهدة.", android.widget.Toast.LENGTH_LONG).show()
                com.cinemaplus.rebuilt.data.WatchNextLaunchPolicy.Result.Missing ->
                    android.widget.Toast.makeText(launchContext, "لم يعد هذا العمل في متابعة المشاهدة. افتحه من البحث أو المكتبة.", android.widget.Toast.LENGTH_LONG).show()
            }
            // Refresh stale home cards without changing the selected profile or starting another title.
            vm.syncWatchNext()
            if(LaunchRequest.pending == requested) LaunchRequest.pending = null
        }
    }
    if (television) TvCinemaApp(vm)
    else com.cinemaplus.rebuilt.ui.mobile.MobileCinemaApp(vm)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable private fun TvCinemaApp(vm: CinemaViewModel) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) { vm.refreshFollowedSeries(); vm.checkUpdates() }
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.syncWatchNext()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val context = LocalContext.current
    val browseState = rememberSaveableStateHolder()
    val tabBar = remember { TabBarState() }
    val railFocus = remember { FocusRequester() }
    // Focus moves into the page once after launch, when the first page has loaded.
    var pendingFocus by remember { mutableStateOf(true) }
    BackHandler(enabled = vm.playing == null && vm.selected == null) {
        // Back first returns to the section rail; from there it goes back a page, and on Home it exits without asking.
        if (!tabBar.hasFocus) runCatching { railFocus.requestFocus() }
        else if (vm.page != Page.Home) vm.backPage() else (context as? android.app.Activity)?.finish()
    }
    val fonts = Typography().let { t -> t.copy(
        headlineLarge = t.headlineLarge.copy(fontFamily = Cairo, fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Cairo, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = Cairo, fontWeight = FontWeight.Bold),
        bodyLarge = t.bodyLarge.copy(fontFamily = Cairo), bodyMedium = t.bodyMedium.copy(fontFamily = Cairo),
        labelLarge = t.labelLarge.copy(fontFamily = Cairo, fontWeight = FontWeight.SemiBold)) }
    CompositionLocalProvider(LocalFilmTitle provides { film -> vm.withArtwork(film).displayTitle(vm.titleLanguage) }, LocalLayoutDirection provides LayoutDirection.Rtl, LocalTrailerMuted provides vm.trailerMuted,
        LocalTrailerPreviewEnabled provides vm.trailerPreview) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Panel), typography = fonts) {
            TvTheme {
                Box(Modifier.fillMaxSize().background(Ink)) {
                    // Long press on a continue-watching card: remove it, or mark it watched.
                    vm.recentOptions?.let { film ->
                        // The OK key is still held when this dialog opens; its key repeats must not press a button.
                        // Buttons stay inert for a moment, and the harmless option comes first.
                        var armed by remember(film.id) { mutableStateOf(false) }
                        LaunchedEffect(film.id) { delay(900); armed = true }
                        AlertDialog(onDismissRequest = { if (armed) vm.dismissRecentOptions() },
                            containerColor = GlassStrong,
                            title = { Label(film.title, 20, bold = true, lines = 2) },
                            text = { Column {
                                TextButton(onClick = { if (armed) vm.markWatched(film) }) { Label("تعليم كمشاهَد", 16, bold = true) }
                                TextButton(onClick = { if (armed) vm.removeRecent(film) }) { Label("إزالة من تابع المشاهدة", 16, bold = true) }
                            } },
                            confirmButton = {},
                            dismissButton = { TextButton(onClick = { if (armed) vm.dismissRecentOptions() }) { Label("إلغاء", 15) } })
                    }

                    if (vm.playing != null) PlayerScreen(vm)
                    else if (vm.selected != null) TvScaled { MovieDetailsScreen(vm) }
                    else browseState.SaveableStateProvider("browse") { TvScaled {
                        val contentFocus = remember(vm.page, vm.demo) { FocusRequester() }
                        // The page names where focus should land when it is entered from the rail (its first card).
                        val pageEntry = remember { arrayOfNulls<FocusRequester>(1) }
                        val latestContentFocus by rememberUpdatedState(contentFocus)
                        LaunchedEffect(pendingFocus, vm.busy, vm.page) {
                            if (pendingFocus) {
                                // Take focus as soon as the page has something focusable, so the rail folds at once;
                                // otherwise wait for loading to finish and try again.
                                val moved = awaitTvFocus { latestContentFocus }
                                if (moved || !vm.busy) pendingFocus = false
                            }
                        }
                        // When a select press removes the focused item (a settings card, a section tab), the system drops
                        // focus onto the rail. That is not the user's choice: send it back into the page.
                        var lastSelectKey by remember { mutableLongStateOf(0L) }
                        LaunchedEffect(tabBar.hasFocus) {
                            if (tabBar.hasFocus && !pendingFocus && System.currentTimeMillis() - lastSelectKey < 600) {
                                delay(60)
                                awaitTvFocus { latestContentFocus }
                            }
                        }
                        CompositionLocalProvider(LocalTabBarState provides tabBar, LocalPageEntry provides pageEntry) {
                            Box(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                                val key = event.nativeKeyEvent.keyCode
                                if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && (key == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                                        || key == android.view.KeyEvent.KEYCODE_ENTER || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER))
                                    lastSelectKey = System.currentTimeMillis()
                                false
                            }) {
                                // The page leaves room for the collapsed rail on the right; the rail widens over it.
                                Box(Modifier.fillMaxSize().padding(start = TvMetrics.RailCollapsed)
                                    .focusRequester(contentFocus)
                                    .focusProperties { enter = { pageEntry[0] ?: FocusRequester.Default } }.focusGroup()) {
                                    if (vm.page == Page.Settings) SettingsScreen(vm)
                                    else key(vm.page, vm.demo) { BrowseScreen(vm) }
                                }
                                // Alpha is read in the layer block, so the dimming animates without recomposing the page.
                                // With the rail open the page dims behind a scrim that is darkest on the rail side.
                                val scrim = animateFloatAsState(if (tabBar.hasFocus) 1f else 0f, tween(220), label = "Rail scrim")
                                Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim.value }.background(Brush.horizontalGradient(
                                    0f to Color(0x660C0F14), .40f to Color(0x8C0C0F14), .74f to Color(0xE60C0F14), 1f to Color(0xF50C0F14))))
                                if (tabBar.hasFocus) RailHints(Modifier.align(Alignment.BottomEnd).padding(bottom = 48.mx, end = 58.mx))
                                SideRail(vm, railFocus, Modifier.align(Alignment.TopStart)) { pendingFocus = true }
                            }
                        }
                    } }
                }
            }
        }
    }
}

@Composable fun Label(text: String, size: Int = 14, color: Color = Color.White, bold: Boolean = false, modifier: Modifier = Modifier, lines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = color, fontSize = size.sp, lineHeight = (size * 1.4).sp, fontFamily = Cairo,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable fun Action(text: String, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvActionButton(text, primary, modifier, onClick = onClick)
}

/** Where focus lands when a page is entered from the side rail; a page publishes its first card here. */
internal val LocalPageEntry = androidx.compose.runtime.staticCompositionLocalOf { arrayOfNulls<FocusRequester>(1) }

@Composable internal fun RegisterPageEntry(target: FocusRequester?) {
    val slot = LocalPageEntry.current
    SideEffect { slot[0] = target }
    DisposableEffect(slot) { onDispose { slot[0] = null } }
}

// ───────────────────────── Side rail ─────────────────────────

/**
 * Section rail (02-menu-open.png). Collapsed it is a 112 px column of icons on the right edge with the current
 * section in an accent square. When focus enters it widens to 460 px over the page (the page never reflows)
 * with the profile, labelled rows and the remote hints; the focused row is a white pill.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable private fun SideRail(vm: CinemaViewModel, railFocus: FocusRequester, modifier: Modifier = Modifier, onNavigated: () -> Unit) {
    val bar = LocalTabBarState.current
    val expanded = bar.hasFocus
    val width by animateDpAsState(if (expanded) TvMetrics.RailExpanded else TvMetrics.RailCollapsed,
        tween(220, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "Rail width")
    // Explore (moods) is kept although the mockup leaves it out: the brief forbids dropping existing features.
    val items = listOf(Page.Search, Page.Home, Page.Movies, Page.Series, Page.Explore, Page.Library)
    val switchProfile = LocalProfileSwitch.current
    Box(modifier.fillMaxHeight().width(width)
        .background(if (expanded) Color.Transparent else Brand.Background.copy(alpha = .72f))
        .drawBehind {
            // 1 px hairline on the page side of the collapsed rail.
            if (!expanded) {
                val x = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width - 1f
                drawRect(Color.White.copy(alpha = .08f), Offset(x, 0f), Size(1f, size.height))
            }
        }) {
        Column(Modifier.fillMaxSize()
            .onFocusChanged { bar.hasFocus = it.hasFocus }
            // Entering the rail from the page lands on the current section, not on whichever item the search meets first.
            .focusProperties { enter = { railFocus } }.focusGroup()
            .padding(top = 48.mx, bottom = 48.mx, start = if (expanded) 40.mx else 0.dp, end = if (expanded) 32.mx else 0.dp),
            horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (expanded) 10.mx else 12.mx)) {
            RailProfile(vm.profile, expanded) { switchProfile() }
            items.forEach { page ->
                RailItem(page, vm.page == page, expanded, if (page == vm.page) Modifier.focusRequester(railFocus) else Modifier) {
                    vm.navigate(page); onNavigated()
                }
            }
            Spacer(Modifier.weight(1f))
            if (expanded) Box(Modifier.fillMaxWidth().padding(vertical = 8.mx).height(1.dp).background(Brand.Divider))
            RailItem(Page.Settings, vm.page == Page.Settings, expanded,
                if (vm.page == Page.Settings) Modifier.focusRequester(railFocus) else Modifier, height = 64.mx) {
                vm.navigate(Page.Settings); onNavigated()
            }
        }
    }
}

@Composable private fun RailProfile(profile: Profile, expanded: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.padding(bottom = if (expanded) 36.mx else 28.mx).clip(RoundedCornerShape(16.mx))
        .background(if (focused) Color.White else Color.Transparent)
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
        .semantics { contentDescription = "الملف الشخصي: ${profile.name}" }
        .padding(if (expanded) 6.mx else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.mx)) {
        val size = if (expanded) 64.mx else 56.mx
        Box(Modifier.size(size).clip(CircleShape).background(Brand.BorderStrong)) { ProfileAvatar(profile, Modifier.fillMaxSize()) }
        if (expanded) Column {
            Label(profile.name, 14, if (focused) Brand.Background else Color.White, true, lines = 1)
            Label("تبديل الملف الشخصي", 10, if (focused) Brand.OnPrimary else Brand.TextTertiary, lines = 1)
        }
    }
}

@Composable private fun RailItem(page: Page, selected: Boolean, expanded: Boolean, modifier: Modifier,
    height: Dp = 72.mx, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && expanded) 1.03f else 1f, tween(140), label = "Rail focus")
    if (!expanded) {
        // Collapsed: a 64 px rounded square; accent for the current section, white when focused.
        val bg = when { focused -> Color.White; selected -> Brand.Accent; else -> Color.Transparent }
        val fg = when { focused -> Brand.Background; selected -> Brand.OnAccent; else -> Brand.TextTertiary }
        Box(modifier.size(64.mx).clip(RoundedCornerShape(16.mx)).background(bg)
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
            .semantics { contentDescription = page.label }, contentAlignment = Alignment.Center) {
            RailGlyph(page, fg, 30.mx)
        }
        return
    }
    val fg = when { focused -> Brand.Background; selected -> Color.White; else -> Brand.TextTertiary }
    Box(modifier.fillMaxWidth()) {
        // The current section is marked by an accent bar on the outer edge and an accent icon.
        if (selected && !focused) Box(Modifier.align(Alignment.CenterStart).offset(x = (-12).mx).size(5.mx, 36.mx)
            .clip(CircleShape).background(Brand.Accent))
        Row(Modifier.fillMaxWidth().height(height).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.mx)).background(if (focused) Color.White else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
            .semantics { contentDescription = page.label }.padding(horizontal = 20.mx),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.mx)) {
            RailGlyph(page, if (selected && !focused) Brand.Accent else fg, 30.mx)
            Label(page.label, if (height < 70.mx) 14 else 15, fg, focused || selected, lines = 1)
        }
    }
}

/** Remote hints shown while the rail is open, at the far side of the screen. */
@Composable private fun RailHints(modifier: Modifier) {
    @Composable fun hint(key: String, text: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mx)) {
            Box(Modifier.border(1.dp, Brand.BorderStrong, RoundedCornerShape(8.mx)).padding(horizontal = 12.mx, vertical = 2.mx)) {
                Label(key, 12, Color.White, lines = 1)
            }
            Label(text, 12, Brand.TextTertiary, lines = 1)
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(28.mx)) {
        hint("OK", "اختيار")
        hint("←", "إغلاق القائمة")
    }
}

/** Rail icons: the stock set where it fits, drawn glyphs for movies and series. */
@Composable private fun RailGlyph(page: Page, tint: Color, iconSize: Dp = 22.dp) {
    val size = Modifier.size(iconSize)
    when (page) {
        Page.Search -> Icon(Icons.Rounded.Search, null, size, tint = tint)
        Page.Home -> Icon(Icons.Rounded.Home, null, size, tint = tint)
        Page.Library -> Icon(Icons.Rounded.Favorite, null, size, tint = tint)
        Page.Explore -> Icon(Icons.Rounded.Star, null, size, tint = tint)
        Page.Settings -> Icon(Icons.Rounded.Settings, null, size, tint = tint)
        // Film frame: a rounded frame with sprocket dots on both sides.
        Page.Movies -> Canvas(size) {
            val w = this.size.width
            drawRoundRect(tint, Offset(w * .10f, w * .16f), Size(w * .80f, w * .68f), CornerRadius(w * .10f), style = Stroke(w * .09f))
            for (i in 0..2) {
                val y = w * (.30f + i * .20f)
                drawCircle(tint, w * .035f, Offset(w * .22f, y)); drawCircle(tint, w * .035f, Offset(w * .78f, y))
            }
            drawLine(tint, Offset(w * .40f, w * .34f), Offset(w * .40f, w * .66f), w * .09f, StrokeCap.Round)
        }
        // Screen on a stand.
        Page.Series -> Canvas(size) {
            val w = this.size.width
            drawRoundRect(tint, Offset(w * .08f, w * .14f), Size(w * .84f, w * .56f), CornerRadius(w * .10f), style = Stroke(w * .09f))
            drawLine(tint, Offset(w * .32f, w * .86f), Offset(w * .68f, w * .86f), w * .09f, StrokeCap.Round)
            drawLine(tint, Offset(w * .50f, w * .72f), Offset(w * .50f, w * .86f), w * .09f, StrokeCap.Round)
        }
    }
}

// ───────────────────────── Browse pages ─────────────────────────

@Composable private fun BrowseScreen(vm: CinemaViewModel) {
    when (vm.page) {
        Page.Home -> HomeScreen(vm)
        Page.Search -> SearchScreen(vm)
        Page.Explore -> if(vm.sourceChoice == com.cinemaplus.rebuilt.data.SourceChoice.DAWWIRLI) GenreScreen(vm) else ExploreScreen(vm)
        else -> CatalogScreen(vm)
    }
}

/** Movies and Series grouped by genre: one poster shelf per category under a large page title. */
@Composable private fun GenreScreen(vm: CinemaViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = TvMetrics.TabBarSpace, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(TvMetrics.RowGap)) {
        item(key = "title") {
            Column(Modifier.padding(horizontal = TvMetrics.EdgeInset), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PageHeader(vm.page.label, pageSubtitle(vm.page))
                if (vm.demo) Label("عرض تجريبي • الأعمال للتوضيح فقط", 13, Muted)
            }
        }
        items(vm.shelves.filter { it.films.isNotEmpty() }, key = { it.id }) { shelf -> TvShelfRow(shelf, vm, wide = false) }
        item(key = "status") {
            Column(Modifier.fillMaxWidth().padding(horizontal = TvMetrics.EdgeInset), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (vm.busy) CircularProgressIndicator(color = Accent)
                vm.error?.let { Message(it, "أعد المحاولة", { vm.reload() }, { vm.changeDemo(true) }) }
                if (!vm.busy && vm.error == null && vm.shelves.all { it.films.isEmpty() }) Label("لم يُرجع المصدر محتوى للعرض", 17, Muted)
            }
        }
    }
}

/** Movies/Series (flat fallback) and Library: big page title over a poster grid. */
@Composable private fun CatalogScreen(vm: CinemaViewModel) {
    // Genre shelves when the source returned categories (or in demo mode); otherwise the flat grid.
    if ((vm.page == Page.Movies || vm.page == Page.Series) && (vm.demo || vm.shelves.any { it.id.startsWith("genre-") } || vm.busy && vm.shelves.isEmpty())) {
        GenreScreen(vm); return
    }
    val libraryFilms = remember(vm.libraryRevision, vm.librarySection) { vm.libraryItems() }
    val films = if(vm.page == Page.Library) vm.filter.apply(libraryFilms) else vm.shelves.flatMap { it.films }.distinctBy { it.id }
    val firstFocus = remember { FocusRequester() }
    RegisterPageEntry(if (films.isNotEmpty()) firstFocus else null)
    Column(Modifier.fillMaxSize().padding(top = TvMetrics.TabBarSpace)) {
        PosterGrid(films, vm, firstRequester = firstFocus,
            header = {
                Column(Modifier.padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PageHeader(vm.page.label, if (vm.page == Page.Library) vm.librarySection.description else pageSubtitle(vm.page))
                    if (vm.demo) Label("عرض تجريبي • الأعمال للتوضيح فقط", 13, Muted)
                    if (vm.page == Page.Library) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            com.cinemaplus.rebuilt.data.LibrarySection.entries.forEach { section ->
                                Action(section.label, primary = vm.librarySection == section) { vm.chooseLibrarySection(section) }
                            }
                        }
                        FilterBar(vm)
                    }
                }
            },
            footer = {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (vm.busy) CircularProgressIndicator(color = Accent)
                    vm.error?.let { Message(it, "أعد المحاولة", { vm.reload() }, { vm.changeDemo(true) }) }
                    if (!vm.busy && vm.error == null && films.isEmpty()) Label(when (vm.page) {
                        Page.Library -> if(vm.librarySection == com.cinemaplus.rebuilt.data.LibrarySection.Watched) "لا توجد أعمال مكتملة في سجل المشاهدة بعد." else "قائمتك فارغة. أضف عملًا من صفحة التفاصيل."
                        else -> "لم يُرجع المصدر محتوى للعرض"
                    }, 17, Muted)
                    if (!vm.demo && vm.page in listOf(Page.Movies, Page.Series) && films.isNotEmpty())
                        Action(if (vm.busy) "جارٍ التحميل…" else "عرض المزيد") { if (!vm.busy) vm.more() }
                }
            })
    }
}

@Composable private fun SearchScreen(vm: CinemaViewModel) {
    val films = vm.filter.apply(vm.shelves.flatMap { it.films }.distinctBy { it.id })
    val firstResult = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(top = TvMetrics.TabBarSpace)) {
        PosterGrid(films, vm, firstRequester = firstResult,
            header = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    // Down leaves the text field for the first result instead of wandering elsewhere.
                    SearchBox(vm) { films.isNotEmpty() && runCatching { firstResult.requestFocus() }.isSuccess }
                    when {
                        vm.busy -> Label("جارٍ البحث…", 15, Muted)
                        vm.error != null -> Label(vm.error!!, 14, Muted, lines = 2)
                        vm.query.isBlank() -> {
                            Label("ابحث عن فيلم أو مسلسل", 17, Muted)
                            if (vm.searchHistory.isNotEmpty()) {
                                Label("عمليات البحث الأخيرة", 15, Muted)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    vm.searchHistory.take(4).forEach { text -> Action(text) { vm.search(text) } }
                                    Action("مسح السجل") { vm.clearSearchHistory() }
                                }
                            }
                        }
                        films.isEmpty() -> { Label("لا توجد نتائج", 17, Muted); if (vm.filter.active) FilterBar(vm) }
                        else -> { Label("نتائج «${vm.query}»", 22, bold = true); FilterBar(vm) }
                    }
                }
            },
            footer = {
                if (!vm.demo && films.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Action(if (vm.busy) "جارٍ التحميل…" else "عرض المزيد") { if (!vm.busy) vm.more() }
                }
            })
    }
}

/** Type and sort buttons; each press cycles to the next value. */
@Composable private fun FilterBar(vm: CinemaViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Action("النوع: ${vm.filter.kind.label}") { vm.cycleKind() }
        Action("الترتيب: ${vm.filter.sort.label}") { vm.cycleSort() }
    }
}

@Composable private fun SearchBox(vm: CinemaViewModel, onMoveDown: () -> Boolean) {
    var typed by rememberSaveable { mutableStateOf(vm.query) }
    LaunchedEffect(typed) {
        delay(500)
        if (typed.trim() != vm.query) vm.search(typed)
    }
    OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
        if (event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown) onMoveDown() else false
    }, singleLine = true,
        shape = RoundedCornerShape(50),
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { vm.search(typed) }),
        placeholder = { Label("اسم الفيلم أو المسلسل", 16, Muted) },
        textStyle = LocalTextStyle.current.copy(fontFamily = Cairo, fontSize = 18.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = .20f), unfocusedContainerColor = Glass,
            focusedBorderColor = Accent, unfocusedBorderColor = Color.Transparent, cursorColor = Accent,
            focusedTextColor = Color.White, unfocusedTextColor = Color.White))
}

@Composable fun Message(message: String, button: String, retry: () -> Unit, demo: (() -> Unit)? = null) {
    Column(Modifier.padding(32.dp).widthIn(max = 630.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Rounded.Warning, null, Modifier.size(36.dp), tint = Accent)
        Label("تعذّر الوصول إلى المحتوى", 24, bold = true)
        Label(message, 15, Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Action(button, true, onClick = retry)
            if (demo != null) Action("تجربة التصميم", onClick = demo)
        }
    }
}

// ───────────────────────── Settings (tvOS list style) ─────────────────────────

private enum class SettingsSection(val title: String) {
    Sources("المصادر والمحتوى"), Account("الحساب"), Profiles("الملفات الشخصية"),
    Subtitles("الترجمة"), Playback("التشغيل"), About("التحديث وحول التطبيق")
}

/** Settings as a hub of cards; each card opens its own short page, and Back returns to the hub. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable private fun SettingsScreen(vm: CinemaViewModel) {
    var cloudDialog by remember { mutableStateOf(false) }
    if (cloudDialog) com.cinemaplus.rebuilt.cloud.CloudAccountDialog() { cloudDialog = false }
    var sectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val section = SettingsSection.entries.firstOrNull { it.name == sectionName }
    BackHandler(enabled = section != null) { sectionName = null }
    // Back from a section puts focus on the card that was opened, not on the side rail.
    val cardFocus = remember { mutableMapOf<String, FocusRequester>() }
    val cardVisibility = remember { mutableMapOf<String, BringIntoViewRequester>() }
    var openedSection by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(section) {
        val name = openedSection
        if (section == null && name != null) {
            withFrameNanos { }
            cardVisibility[name]?.bringIntoView()
            if(awaitTvFocus { cardFocus[name] }) openedSection = null
        }
    }
    fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1).mod(options.size)]
    val sizes = listOf(18f, 22f, 26f, 30f)
    val languages = listOf("ar", "en", "off")
    val backgrounds = listOf(0f, .25f, .5f, .75f, 1f)
    val qualities = listOf("تلقائي", "1080p", "720p", "480p")
    val languageLabel = when (vm.subtitleLanguage) { "ar" -> "العربية"; "en" -> "الإنجليزية"; else -> "بدون ترجمة" }
    // Entering the page from the rail lands on the first card (the right-most one), not on the last.
    Box(Modifier.fillMaxSize().focusProperties {
        enter = { if (section == null) cardFocus[SettingsSection.entries.first().name] ?: FocusRequester.Default else FocusRequester.Default }
    }.focusGroup()) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(top = TvMetrics.TabBarSpace, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = if (section == null) 980.dp else 780.dp).fillMaxWidth().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)) {
            PageHeader(section?.title ?: "الإعدادات", if (section == null) pageSubtitle(Page.Settings) else "")
            if (section == null) {
                val cards = listOf(
                    SettingsSection.Sources to "${vm.sourceChoice.label} · ${vm.titleLanguage.label}",
                    SettingsSection.Account to if (vm.signedIn) "تم تسجيل الدخول" else "غير مسجّل",
                    SettingsSection.Profiles to vm.profile.name,
                    SettingsSection.Subtitles to "الحجم ${vm.subtitleSize.toInt()} · $languageLabel",
                    SettingsSection.Playback to "الجودة ${vm.quality}",
                    SettingsSection.About to if (vm.availableUpdate != null) "تحديث متاح" else "الإصدار ${BuildConfig.VERSION_NAME}")
                cards.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { (target, summary) -> SettingsCard(target.title, summary, Modifier.weight(1f).bringIntoViewRequester(cardVisibility.getOrPut(target.name) { BringIntoViewRequester() }).focusRequester(cardFocus.getOrPut(target.name) { FocusRequester() })) { openedSection = target.name; sectionName = target.name } }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label(androidx.compose.ui.res.stringResource(R.string.app_name), 18, Accent, true)
                    Label("الإصدار ${com.cinemaplus.rebuilt.BuildConfig.VERSION_NAME}", 13, Muted)
                    if(vm.artworkFromTmdb) Label("This product uses the TMDB API but is not endorsed or certified by TMDB.", 11, Muted, lines = 2)
                    Label("تطوير Emad Hasnawi", 13, Muted)
                }
            }
            if (section == SettingsSection.About) SettingsGroup("تحديث التطبيق") {
                Label("الإصدار الحالي ${BuildConfig.VERSION_NAME}", 14, Muted)
                Action(if (vm.updateBusy) "جارٍ الفحص أو التنزيل… ${vm.updateProgress}%" else "فحص التحديثات") {
                    vm.checkUpdates(manual = true)
                }
                vm.updateStatus?.let { Label(it, 13, Muted, lines = 2) }
                vm.availableUpdate?.let { update ->
                    Action(if (vm.updateBusy) "جارٍ تنزيل التحديث…" else "تنزيل وتثبيت ${update.versionName}", true) { vm.downloadUpdate() }
                }
            }
            if (section == SettingsSection.Sources) {
                SettingsGroup("المصدر") {
                    Label("اختر من أين يُجلب المحتوى", 13, Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    SourceChoice.entries.forEach { choice ->
                        SettingRow(choice.label, if (vm.sourceChoice == choice) "✓ مختار" else "") { vm.chooseSource(choice) }
                    }
                }
                SettingsGroup("المحتوى") {
                    SettingRow("لغة أسماء الأعمال", vm.titleLanguage.label) {
                        vm.chooseTitleLanguage(next(TitleLanguage.entries.toList(), vm.titleLanguage))
                    }
                }
            }
            if (section == SettingsSection.Profiles) {
                val switchProfile = LocalProfileSwitch.current
                SettingsGroup("الملفات الشخصية") {
                    SettingRow("الملف الحالي", vm.profile.name) { switchProfile() }
                    Label("اضغط لتبديل الملف أو إضافة ملف جديد", 12, Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            if (section == SettingsSection.Account) SettingsGroup("الحساب") {
                TvAccountSection(vm, cloudDialog) { cloudDialog = true }
            }
            if (section == SettingsSection.Subtitles) SettingsGroup("الترجمة") {
                SettingRow("حجم الخط", "${vm.subtitleSize.toInt()}") { vm.chooseSubtitleSize(next(sizes, vm.subtitleSize)) }
                SettingRow("اللغة المفضلة", languageLabel) {
                    vm.chooseSubtitleLanguage(next(languages, vm.subtitleLanguage))
                }
                SettingRow("خلفية الترجمة", if (vm.subtitleBackground == 0f) "بدون خلفية" else "${(vm.subtitleBackground * 100).toInt()}%") {
                    vm.chooseSubtitleBackground(next(backgrounds, backgrounds.minByOrNull { kotlin.math.abs(it - vm.subtitleBackground) } ?: .5f))
                }
                SettingRow("ارتفاع الترجمة", subtitleHeightLabel(vm.subtitleHeight)) {
                    vm.chooseSubtitleHeight(nextSubtitleHeight(vm.subtitleHeight))
                }
                // Live sample so the effect of size, background and height can be judged without starting a film.
                Box(Modifier.fillMaxWidth().height(110.dp).padding(8.dp).clip(RoundedCornerShape(14.dp)).background(PanelHi),
                    contentAlignment = Alignment.BottomCenter) {
                    Label("هكذا ستظهر الترجمة", vm.subtitleSize.toInt(), Color.White, true,
                        Modifier.padding(bottom = (vm.subtitleHeight * 160).dp + 6.dp)
                            .background(Color.Black.copy(alpha = vm.subtitleBackground), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
                        lines = 1)
                }
            }
            if (section == SettingsSection.Playback) SettingsGroup("التشغيل") {
                SettingRow("الجودة المفضلة", vm.quality) { vm.chooseQuality(next(qualities, vm.quality)) }
                SettingRow("تشغيل الحلقة التالية تلقائيًا", if (vm.autoNext) "مفعّل (عدّاد 8 ثوانٍ)" else "معطّل") { vm.chooseAutoNext(!vm.autoNext) }
                SettingRow("فلترة المشاهد غير اللائقة", if (vm.sceneFilter) "مفعّلة" else "متوقفة") { vm.chooseSceneFilter(!vm.sceneFilter) }
            }
        }
    }
    }
}

/** Large hub card: title and a one-line summary of the current state. Focus turns it into the accent colour. */
@Composable private fun SettingsCard(title: String, summary: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "Settings card focus")
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.height(104.dp).zIndex(if (focused) 5f else 0f).graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape).background(if (focused) Accent else Panel)
        .border(1.dp, Color.White.copy(alpha = .08f), shape)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center) {
        Label(title, 18, if (focused) OnAccent else Color.White, true, lines = 1)
        Label(summary, 13, if (focused) OnAccent.copy(alpha = .75f) else Muted, lines = 1)
    }
}

@Composable private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(title, 15, Muted, true, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel)
            .border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(22.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.02f else 1f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow), label = "Setting focus")
    Row(Modifier.fillMaxWidth().zIndex(if (focused) 5f else 0f)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(16.dp))
        .background(if (focused) Accent else Color.Transparent)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClick = onClick)
        .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Label(title, 17, if (focused) OnAccent else Color.White, true, lines = 1)
        Label(value, 16, if (focused) OnAccent.copy(alpha = .7f) else Muted, lines = 1)
    }
}
