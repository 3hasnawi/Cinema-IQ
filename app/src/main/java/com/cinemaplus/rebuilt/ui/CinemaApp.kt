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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
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

val Ink = Color(0xFF151827)
val Panel = Color(0xFF202439)
val PanelHi = Color(0xFF2C3250)
/** Marquee gold: primary buttons, focus rings, selection, progress and ratings. */
val Accent = Color(0xFFFFB93B)
val AccentLight = Color(0xFFFFD27A)
/** Text and icons drawn on top of [Accent]. */
val OnAccent = Color(0xFF1A1204)
/** Deep plum that tints the bottom of hero artwork. */
val AccentDeep = Color(0xFF2A1740)
val InputBorder = Color(0xFF424A6E)
val Signal = Color(0xFFFF453A)
val Muted = Color(0xFFA3A8BE)
val Cairo = FontFamily(Font(R.font.cairo_regular, FontWeight.Normal), Font(R.font.cairo_semibold, FontWeight.SemiBold), Font(R.font.cairo_bold, FontWeight.Bold))

@Composable fun CinemaApp(vm: CinemaViewModel = viewModel()) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) { vm.refreshFollowedSeries(); vm.checkUpdates() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val context = LocalContext.current
    val browseState = rememberSaveableStateHolder()
    var confirmExit by remember { mutableStateOf(false) }
    val tabBar = remember { TabBarState() }
    val railFocus = remember { FocusRequester() }
    // Focus moves into the page once after launch, when the first page has loaded.
    var pendingFocus by remember { mutableStateOf(true) }
    BackHandler(enabled = vm.playing == null && vm.selected == null) {
        // Back first returns to the section rail; from there it goes back a page, then asks to exit.
        if (!tabBar.hasFocus) runCatching { railFocus.requestFocus() }
        else if (vm.page != Page.Home) vm.backPage() else confirmExit = true
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
                    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
                        containerColor = GlassStrong,
                        title = { Label("الخروج من التطبيق؟", 20, bold = true) },
                        confirmButton = { TextButton(onClick = { (context as? android.app.Activity)?.finish() }) { Label("خروج", 15, bold = true) } },
                        dismissButton = { TextButton(onClick = { confirmExit = false }) { Label("إلغاء", 15) } })

                    if (vm.playing != null) PlayerScreen(vm)
                    else if (vm.selected != null) TvScaled { MovieDetailsScreen(vm) }
                    else browseState.SaveableStateProvider("browse") { TvScaled {
                        val contentFocus = remember(vm.page, vm.demo) { FocusRequester() }
                        val latestContentFocus by rememberUpdatedState(contentFocus)
                        LaunchedEffect(pendingFocus, vm.busy, vm.page) {
                            if (pendingFocus && !vm.busy) {
                                delay(60); runCatching { latestContentFocus.requestFocus() }; pendingFocus = false
                            }
                        }
                        CompositionLocalProvider(LocalTabBarState provides tabBar) {
                            Box(Modifier.fillMaxSize()) {
                                // The page leaves room for the collapsed rail on the right; the rail widens over it.
                                Box(Modifier.fillMaxSize().padding(start = TvMetrics.RailCollapsed)
                                    .focusRequester(contentFocus).focusGroup()) {
                                    if (vm.page == Page.Settings) SettingsScreen(vm)
                                    else key(vm.page, vm.demo) { BrowseScreen(vm) }
                                }
                                // Alpha is read in the layer block, so the dimming animates without recomposing the page.
                                val scrim = animateFloatAsState(if (tabBar.hasFocus) .72f else 0f, tween(220), label = "Rail scrim")
                                Box(Modifier.fillMaxSize().graphicsLayer { alpha = scrim.value }.background(Ink))
                                SideRail(vm, railFocus, Modifier.align(Alignment.TopStart))
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

// ───────────────────────── Side rail ─────────────────────────

/**
 * Section rail on the right edge: icons only while the page has focus, icons and labels while focus is
 * inside it. It widens over the page rather than pushing it, so content never reflows.
 */
@Composable private fun SideRail(vm: CinemaViewModel, railFocus: FocusRequester, modifier: Modifier = Modifier) {
    val bar = LocalTabBarState.current
    val width by animateDpAsState(if (bar.hasFocus) TvMetrics.RailExpanded else TvMetrics.RailCollapsed,
        tween(220, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)), label = "Rail width")
    val labels = width > 130.dp
    val items = listOf(Page.Home, Page.Search, Page.Movies, Page.Series, Page.Library)
    val shape = RoundedCornerShape(32.dp)
    // A floating capsule centred on the edge; it widens over the page and never reflows it.
    Box(modifier.fillMaxHeight().width(width).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().clip(shape).background(PanelHi.copy(alpha = .94f))
            .border(1.dp, Color.White.copy(alpha = .10f), shape)
            .onFocusChanged { bar.hasFocus = it.hasFocus }.focusGroup()
            .padding(horizontal = 7.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { p ->
                RailItem(p, vm.page == p, labels, if (p == vm.page) Modifier.focusRequester(railFocus) else Modifier) { vm.navigate(p) }
            }
            TicketDivider(Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            RailItem(Page.Settings, vm.page == Page.Settings, labels,
                if (vm.page == Page.Settings) Modifier.focusRequester(railFocus) else Modifier) { vm.navigate(Page.Settings) }
        }
    }
}

@Composable private fun RailItem(page: Page, selected: Boolean, labelled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Focused = gold pill with dark content; the current page is drawn in gold.
    val fg = when { focused -> OnAccent; selected -> Accent; else -> Color.White.copy(alpha = .85f) }
    Row(modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(if (focused) Accent else Color.Transparent)
        .onFocusChanged { focused = it.isFocused }
        .clickable(onClick = onClick)
        .semantics { contentDescription = page.label }
        .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (labelled) Arrangement.Start else Arrangement.Center) {
        RailGlyph(page, fg)
        if (labelled) {
            Spacer(Modifier.width(12.dp))
            Label(page.label, 15, fg, focused || selected, lines = 1)
        }
    }
}

/** Rail icons: the stock set where it fits, drawn glyphs for movies and series. */
@Composable private fun RailGlyph(page: Page, tint: Color) {
    val size = Modifier.size(22.dp)
    when (page) {
        Page.Search -> Icon(Icons.Rounded.Search, null, size, tint = tint)
        Page.Home -> Icon(Icons.Rounded.Home, null, size, tint = tint)
        Page.Library -> Icon(Icons.Rounded.Favorite, null, size, tint = tint)
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
    val films = vm.shelves.flatMap { it.films }.distinctBy { it.id }
    val firstFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(top = TvMetrics.TabBarSpace)) {
        PosterGrid(films, vm, firstRequester = firstFocus,
            header = {
                Column(Modifier.padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PageHeader(vm.page.label, if (vm.page == Page.Library && films.isNotEmpty()) "${films.size} عمل في قائمتك" else pageSubtitle(vm.page))
                    if (vm.demo) Label("عرض تجريبي • الأعمال للتوضيح فقط", 13, Muted)
                }
            },
            footer = {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (vm.busy) CircularProgressIndicator(color = Accent)
                    vm.error?.let { Message(it, "أعد المحاولة", { vm.reload() }, { vm.changeDemo(true) }) }
                    if (!vm.busy && vm.error == null && films.isEmpty()) Label(when (vm.page) {
                        Page.Library -> "قائمتك فارغة. أضف عملًا من صفحة التفاصيل."
                        else -> "لم يُرجع المصدر محتوى للعرض"
                    }, 17, Muted)
                    if (!vm.demo && vm.page in listOf(Page.Movies, Page.Series) && films.isNotEmpty())
                        Action(if (vm.busy) "جارٍ التحميل…" else "عرض المزيد") { if (!vm.busy) vm.more() }
                }
            })
    }
}

@Composable private fun SearchScreen(vm: CinemaViewModel) {
    val films = vm.shelves.flatMap { it.films }.distinctBy { it.id }
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
                        vm.query.isBlank() -> Label("ابحث عن فيلم أو مسلسل", 17, Muted)
                        films.isEmpty() -> Label("لا توجد نتائج", 17, Muted)
                        else -> Label("نتائج «${vm.query}»", 22, bold = true)
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

@Composable private fun SettingsScreen(vm: CinemaViewModel) {
    val context = LocalContext.current
    var loginMessage by remember { mutableStateOf<String?>(null) }
    fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1).mod(options.size)]
    val sizes = listOf(18f, 22f, 26f, 30f)
    val languages = listOf("ar", "en", "off")
    val backgrounds = listOf(0f, .25f, .5f, .75f, 1f)
    val qualities = listOf("تلقائي", "1080p", "720p", "480p")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(top = TvMetrics.TabBarSpace, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 780.dp).fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            PageHeader("الإعدادات", pageSubtitle(Page.Settings))
            SettingsGroup("تحديث التطبيق") {
                Label("الإصدار الحالي ${BuildConfig.VERSION_NAME}", 14, Muted)
                Action(if (vm.updateBusy) "جارٍ الفحص أو التنزيل… ${vm.updateProgress}%" else "فحص التحديثات") {
                    vm.checkUpdates(manual = true)
                }
                vm.updateStatus?.let { Label(it, 13, Muted, lines = 2) }
                vm.availableUpdate?.let { update ->
                    Action(if (vm.updateBusy) "جارٍ تنزيل التحديث…" else "تنزيل وتثبيت ${update.versionName}", true) { vm.downloadUpdate() }
                }
            }
            SettingsGroup("المحتوى") {
                SettingRow("لغة أسماء الأعمال", vm.titleLanguage.label) {
                    vm.chooseTitleLanguage(next(TitleLanguage.entries.toList(), vm.titleLanguage))
                }
                SettingRow("مصدر المحتوى", vm.sourceChoice.label) {
                    vm.chooseSource(next(SourceChoice.entries.toList(), vm.sourceChoice))
                }
            }
            SettingsGroup("الحساب") {
                var username by remember { mutableStateOf("") }
                var password by remember { mutableStateOf("") }
                if(vm.signedIn) {
                    Label("تم تسجيل الدخول", 14, Muted)
                    Action(if(vm.accountBusy) "جارٍ تسجيل الخروج…" else "تسجيل الخروج") { vm.logout() }
                } else {
                    OutlinedTextField(username, { username = it }, singleLine = true, enabled = !vm.accountBusy,
                        label = { Label("اسم المستخدم") })
                    OutlinedTextField(password, { password = it }, singleLine = true, enabled = !vm.accountBusy,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        label = { Label("كلمة المرور") })
                    Action(if(vm.accountBusy) "جارٍ تسجيل الدخول…" else "تسجيل الدخول") {
                        vm.login(username, password); password = ""
                    }
                }
                vm.accountError?.let { Label(it, 12, Muted, lines = 2) }
            }
            SettingsGroup("الترجمة") {
                SettingRow("حجم الخط", "${vm.subtitleSize.toInt()}") { vm.chooseSubtitleSize(next(sizes, vm.subtitleSize)) }
                SettingRow("اللغة المفضلة", when (vm.subtitleLanguage) { "ar" -> "العربية"; "en" -> "الإنجليزية"; else -> "بدون ترجمة" }) {
                    vm.chooseSubtitleLanguage(next(languages, vm.subtitleLanguage))
                }
                SettingRow("خلفية الترجمة", if (vm.subtitleBackground == 0f) "بدون خلفية" else "${(vm.subtitleBackground * 100).toInt()}%") {
                    vm.chooseSubtitleBackground(next(backgrounds, backgrounds.minByOrNull { kotlin.math.abs(it - vm.subtitleBackground) } ?: .5f))
                }
                SettingRow("ارتفاع الترجمة", subtitleHeightLabel(vm.subtitleHeight)) {
                    vm.chooseSubtitleHeight(nextSubtitleHeight(vm.subtitleHeight))
                }
            }
            SettingsGroup("التشغيل") {
                SettingRow("الجودة المفضلة", vm.quality) { vm.chooseQuality(next(qualities, vm.quality)) }
                SettingRow("معاينة التريلر في البطاقات", if(vm.trailerPreview) "مفعّلة" else "معطّلة (يظهر الوصف)") { vm.chooseTrailerPreview(!vm.trailerPreview) }
                SettingRow("صوت التريلر", if(vm.trailerMuted) "مكتوم" else "مفتوح") { vm.chooseTrailerMuted(!vm.trailerMuted) }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(androidx.compose.ui.res.stringResource(R.string.app_name), 18, Accent, true)
                Label("الإصدار ${com.cinemaplus.rebuilt.BuildConfig.VERSION_NAME}", 13, Muted)
                if(vm.artworkFromTmdb) Label("This product uses the TMDB API but is not endorsed or certified by TMDB.", 11, Muted, lines = 2)
                Label("تطوير Emad Hasnawi", 13, Muted)
            }
        }
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
