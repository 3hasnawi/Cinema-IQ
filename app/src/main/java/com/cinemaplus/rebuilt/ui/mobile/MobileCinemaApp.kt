package com.cinemaplus.rebuilt.ui.mobile

import android.app.Activity
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
    val barScale by animateFloatAsState(if (compactBar) .9f else 1f, spring(), label = "floating-navigation")
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
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = OnAccent,
            background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White), typography = typography) {
            if (exit) AlertDialog(onDismissRequest = { exit = false }, title = { Text("الخروج من التطبيق؟") },
                confirmButton = { TextButton(onClick = { activity?.finish() }) { Text("خروج") } },
                dismissButton = { TextButton(onClick = { exit = false }) { Text("إلغاء") } })
            if (playback) MobilePlayerScreen(vm)
            else if (vm.selected != null) MobileDetails(vm)
            else Scaffold(containerColor = Ink, bottomBar = {
                NavigationBar(containerColor = Panel.copy(alpha = .96f),
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)
                        .scale(barScale).clip(RoundedCornerShape(28.dp)), windowInsets = WindowInsets(0, 0, 0, 0)) {
                    val destinations = listOf(Page.Home to Icons.Rounded.Home, Page.Search to Icons.Rounded.Search, Page.Explore to Icons.Rounded.Star,
                        Page.Library to Icons.Rounded.Favorite, Page.Settings to Icons.Rounded.Settings)
                    destinations.forEach { (page, icon) ->
                        NavigationBarItem(selected = vm.page == page || (page == Page.Home && vm.page in listOf(Page.Movies, Page.Series)),
                            onClick = { vm.navigate(page) }, icon = { Icon(icon, page.label) }, label = { Text(page.label) })
                    }
                }
            }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).nestedScroll(scrollConnection)) {
                    when (vm.page) { Page.Settings -> MobileSettings(vm); Page.Explore -> MobileExplore(vm); else -> MobileBrowse(vm) }
                }
            }
        }
    }
}

@Composable
private fun MobileBrowse(vm: CinemaViewModel) {
    var typed by rememberSaveable { mutableStateOf(vm.query) }
    LaunchedEffect(typed, vm.page) {
        if (vm.page == Page.Search && typed.trim() != vm.query) { delay(400); vm.search(typed) }
    }
    val revision = vm.libraryRevision
    val recent = remember(revision) { vm.library.recent() }
    val shelves = if (vm.page == Page.Home && recent.isNotEmpty()) listOf(Shelf("recent", "تابع المشاهدة", recent)) + vm.shelves else vm.shelves
    val featured = remember(vm.shelves) { phoneFeaturedFilms(vm.shelves) }
    // Sharper artwork and missing synopses from TMDB for what is on screen first; bounded so a long list stays cheap.
    LaunchedEffect(featured, vm.shelves) {
        vm.enrich(featured + vm.shelves.take(4).flatMap { it.films.take(6) })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cinema iQ", fontSize = 24.sp, color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.reload() }) { Icon(Icons.Rounded.Refresh, "تحديث المحتوى") }
            }
            if (vm.page in listOf(Page.Home, Page.Movies, Page.Series)) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Page.Home, Page.Movies, Page.Series).forEach { page ->
                        FilterChip(selected = vm.page == page, onClick = { vm.navigate(page) }, label = { Text(page.label) })
                    }
                }
            }
            if (vm.page != Page.Home) PageHeader(vm.page.label, pageSubtitle(vm.page), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (vm.page == Page.Search) OutlinedTextField(typed, { typed = it }, singleLine = true,
                label = { Text("اسم الفيلم أو المسلسل") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        if (vm.page == Page.Home && featured.isNotEmpty()) item(key = "hero") { MobileHero(vm, featured) }
        if (vm.busy) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        vm.error?.let { message -> item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(message, color = Muted); TextButton(onClick = { vm.reload() }) { Text("إعادة المحاولة") }
            }
        } }
        items(shelves, key = { it.id }) { shelf ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(shelf.title, Modifier.padding(horizontal = 16.dp), count = shelf.films.size)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(shelf.films, key = { it.id }) { film ->
                        MobileCard(vm.withArtwork(film), vm.library.fraction(film.id), shelf.id == "recent") {
                            if (shelf.id == "recent") vm.play(film) else vm.open(film)
                        }
                    }
                }
            }
        }
        if (!vm.busy && shelves.isEmpty()) item {
            Text(if (vm.page == Page.Search && typed.isBlank()) "اكتب اسم العمل للبحث" else "لا يوجد محتوى لعرضه",
                color = Muted, modifier = Modifier.padding(16.dp))
        }
        if (vm.page in listOf(Page.Search, Page.Movies, Page.Series) && shelves.any { it.films.isNotEmpty() }) item {
            TextButton(onClick = { if (!vm.busy) { vm.pageNumber++; vm.reload(append = true) } }, modifier = Modifier.fillMaxWidth()) { Text("عرض المزيد") }
        }
    }
}

@Composable
private fun MobileCard(film: Film, progress: Float, wide: Boolean, width: androidx.compose.ui.unit.Dp = if (wide) 230.dp else 132.dp, click: () -> Unit) {
    Column(Modifier.width(width).clickable(onClick = click), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            AsyncImage(if (wide) film.backdrop.ifBlank { film.poster } else film.poster, mediaTitle(film),
                Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f).clip(RoundedCornerShape(10.dp)).background(Panel),
                contentScale = ContentScale.Crop)
            RatingChip(film.rating, Modifier.align(Alignment.TopStart).padding(6.dp), size = 11)
        }
        if (wide && progress > 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(mediaTitle(film), fontFamily = Cairo, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(kindTint(film.series), CircleShape))
            Text(if (film.episode > 0) "الموسم ${film.season} • الحلقة ${film.episode}" else film.year, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MobileDetails(vm: CinemaViewModel) {
    val film = vm.selected?.let(vm::withArtwork) ?: return
    val revision = vm.libraryRevision
    val saved = remember(revision, film.id) { vm.library.favorites().any { it.id == film.id } }
    val season = vm.seasons.getOrNull(vm.seasonIndex)
    var showTrailer by remember(film.id) { mutableStateOf(false) }
    val candidates = vm.shelves.map { shelf -> shelf.copy(films = shelf.films.map(vm::withArtwork)) }
    val related = phoneRelatedFilms(film, candidates)
    LaunchedEffect(film.id, vm.shelves) {
        vm.enrich(vm.shelves.flatMap { it.films }.filter { it.series == film.series && it.id != film.id }.distinctBy { it.id }.take(36))
    }
    if (showTrailer) MobileTrailer(film) { showTrailer = false }
    // A new list state per title, so opening a similar title starts at its top instead of the previous scroll offset.
    val listState = remember(film.id) { androidx.compose.foundation.lazy.LazyListState() }
    LazyColumn(Modifier.fillMaxSize().background(Ink).statusBarsPadding(), state = listState, contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Box(Modifier.fillMaxWidth().aspectRatio(1.15f)) {
                HeroBackdrop(film, Modifier.fillMaxSize(), sideScrim = false, fadeFrom = .40f)
                IconButton(onClick = { vm.backDetail() }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Panel, RoundedCornerShape(24.dp))) {
                    Icon(Icons.Rounded.ArrowForward, "رجوع")
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(mediaTitle(film), fontSize = 27.sp, fontFamily = Cairo, fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    KindBadge(film.series)
                    RatingChip(film.rating)
                    listOf(film.year, formatContentDuration(film.duration), film.contentQuality, film.edition).forEach { InfoChip(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !film.series || season?.episodes?.isNotEmpty() == true,
                        onClick = { vm.play(if (film.series) season?.episodes?.firstOrNull()?.copy(seriesId = film.id, season = season.number) ?: film else film) }) { Icon(Icons.Rounded.PlayArrow, null); Text("شاهد الآن") }
                    OutlinedButton(onClick = { vm.toggle(film) }) { Text(if (saved) "إزالة من قائمتي" else "قائمتي") }
                    IconButton(onClick = { showTrailer = true }) { Icon(TrailerIcon, "التريلر") }
                }
                Text(film.description.ifBlank { "لا يتوفر وصف لهذا العمل." }, color = Color.White.copy(alpha = .85f))
                if (vm.detailBusy) CircularProgressIndicator(Modifier.size(24.dp))
                vm.detailError?.let { Text(it, color = Muted) }
            }
        }
        if (vm.seasons.isNotEmpty()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.seasons.indices.toList()) { index ->
                    FilterChip(selected = vm.seasonIndex == index, onClick = { vm.seasonIndex = index }, label = { Text("الموسم ${vm.seasons[index].number}") })
                }
            }
        }
        if (season != null) item {
            MobileEpisodeRail(season.episodes.map { it.copy(seriesId = film.id, season = season.number) },
                fallbackArtwork = film.backdrop, onPlay = vm::play)
        }
        if (related.isNotEmpty()) item(key = "related") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("أعمال مشابهة", Modifier.padding(horizontal = 16.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(related, key = { it.id }) { other -> MobileCard(vm.withArtwork(other), 0f, false, 110.dp) { vm.open(other) } }
                }
            }
        }
    }
}

@Composable
private fun MobileSettings(vm: CinemaViewModel) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("الإعدادات", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        item { Text("المصادر", fontWeight = FontWeight.Bold) }
        SourceChoice.entries.forEach { choice ->
            item(key = "source-${choice.name}") { MobileChoice(choice.label, if (vm.sourceChoice == choice) "✓ مختار" else "") { vm.chooseSource(choice) } }
        }
        item {
            val switchProfile = com.cinemaplus.rebuilt.ui.LocalProfileSwitch.current
            MobileChoice("الملف الحالي", vm.profile.name) { switchProfile() }
        }
        item { MobileChoice("لغة أسماء الأعمال", vm.titleLanguage.label) { vm.chooseTitleLanguage(TitleLanguage.entries[(vm.titleLanguage.ordinal + 1) % TitleLanguage.entries.size]) } }
        item {
            Text("الحساب", fontWeight = FontWeight.Bold)
            if (vm.signedIn) TextButton(onClick = { vm.logout() }, enabled = !vm.accountBusy) { Text("تسجيل الخروج") }
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(username, { username = it }, label = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("كلمة المرور") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Button(enabled = !vm.accountBusy, onClick = { vm.login(username, password); password = "" }) { Text(if (vm.accountBusy) "جارٍ تسجيل الدخول…" else "تسجيل الدخول") }
            }
            vm.accountError?.let { Text(it, color = Muted) }
        }
        item { Text("الترجمة والتشغيل", fontWeight = FontWeight.Bold) }
        item { MobileChoice("لغة الترجمة المفضلة", when (vm.subtitleLanguage) { "ar" -> "العربية"; "en" -> "الإنجليزية"; else -> "بدون ترجمة" }) {
            val languages = listOf("ar", "en", "off"); vm.chooseSubtitleLanguage(languages[(languages.indexOf(vm.subtitleLanguage) + 1) % languages.size])
        } }
        item { MobileChoice("حجم الترجمة", vm.subtitleSize.toInt().toString()) { vm.chooseSubtitleSize(if (vm.subtitleSize >= 30) 18f else vm.subtitleSize + 2) } }
        item { MobileChoice("خلفية الترجمة", if (vm.subtitleBackground == 0f) "بدون خلفية" else "${(vm.subtitleBackground * 100).toInt()}%") {
            vm.chooseSubtitleBackground(if (vm.subtitleBackground >= 1f) 0f else vm.subtitleBackground + .25f)
        } }
        item { MobileChoice("ارتفاع الترجمة", "${(vm.subtitleHeight * 100).toInt()}%") { vm.chooseSubtitleHeight(if (vm.subtitleHeight >= .25f) .08f else vm.subtitleHeight + .04f) } }
        item { MobileChoice("الجودة المفضلة", vm.quality) { val qualities = listOf("تلقائي", "1080p", "720p", "480p"); vm.chooseQuality(qualities[(qualities.indexOf(vm.quality) + 1) % qualities.size]) } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("المشاهدة العائلية", Modifier.weight(1f)); Switch(vm.sceneFilter, vm::chooseSceneFilter) } }
        item { Text("تحديث التطبيق", fontWeight = FontWeight.Bold)
            TextButton(enabled = !vm.updateBusy, onClick = { vm.checkUpdates(manual = true) }) { Text("فحص التحديثات") }
            vm.updateStatus?.let { Text(it, color = Muted) }
            vm.availableUpdate?.let { update -> Button(enabled = !vm.updateBusy, onClick = { vm.downloadUpdate() }) { Text("تنزيل وتثبيت ${update.versionName}") } }
            if (vm.updateBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        item { Text("Cinema iQ • ${BuildConfig.VERSION_NAME}", color = Muted); Text("تطوير Emad Hasnawi", color = Muted)
            if (vm.artworkFromTmdb) Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MobileChoice(title: String, value: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f)); Text(value, color = Accent)
        }
    }
}

/** Browse by mood on the phone: a two-column grid of moods, then the matching shelves. */
@Composable
private fun MobileExplore(vm: CinemaViewModel) {
    val mood = vm.exploreMood
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeader(mood?.title ?: "استكشاف", mood?.subtitle ?: pageSubtitle(Page.Explore)) }
        if (mood == null) {
            items(com.cinemaplus.rebuilt.data.Moods.all.chunked(2), key = { it.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { m ->
                        Column(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(m.color), Panel)))
                            .clickable { vm.chooseMood(m) }.padding(14.dp), verticalArrangement = Arrangement.Bottom) {
                            Text(m.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                            Text(m.subtitle, fontSize = 11.sp, color = Muted, maxLines = 2)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            val shelves = com.cinemaplus.rebuilt.data.Moods.shelves(mood, vm.shelves)
            items(shelves, key = { it.id }) { shelf ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(shelf.title, Modifier, count = shelf.films.size)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(shelf.films, key = { it.id }) { film -> MobileCard(vm.withArtwork(film), 0f, false) { vm.open(film) } }
                    }
                }
            }
        }
        item {
            if (vm.busy) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            vm.error?.let { Text(it, color = Muted) }
            if (mood != null && !vm.busy && vm.error == null && com.cinemaplus.rebuilt.data.Moods.shelves(mood, vm.shelves).isEmpty())
                Text("لا توجد تصنيفات مطابقة لهذا المزاج في المصدر", color = Muted)
        }
    }
}
