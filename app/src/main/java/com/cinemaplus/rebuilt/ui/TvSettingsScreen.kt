package com.cinemaplus.rebuilt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.cinemaplus.rebuilt.BuildConfig
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.R
import com.cinemaplus.rebuilt.cloud.CloudAccount
import com.cinemaplus.rebuilt.data.PlaybackSpeed
import com.cinemaplus.rebuilt.data.SourceChoice
import com.cinemaplus.rebuilt.data.TitleLanguage

private enum class SettingsPane(val title: String, val caption: String) {
    Account("الحساب", "حساب المزامنة وحساب مصدر المحتوى"),
    Profiles("الملفات الشخصية", "لكل ملف قائمته وتقدّم مشاهدته"),
    Sources("المصدر والمحتوى", "من أين يُجلب المحتوى وكيف تظهر أسماء الأعمال"),
    Playback("التشغيل والجودة", "تُطبَّق على هذا التلفزيون فقط"),
    Subtitles("الترجمة", "اللغة والحجم والخلفية والارتفاع"),
    About("حول التطبيق", "الإصدار والتحديثات")
}

/**
 * TV settings (06-settings.png): the section list on the right, the selected section's panel on the left.
 * Selection follows focus in the list; Left enters the panel, Right or Back returns to the selected row.
 * Every existing setting is mapped into a section.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TvSettingsScreen(vm: CinemaViewModel) {
    var cloudDialog by remember { mutableStateOf(false) }
    if (cloudDialog) com.cinemaplus.rebuilt.cloud.CloudAccountDialog() { cloudDialog = false }
    var sourceDialog by remember { mutableStateOf(false) }
    var paneName by rememberSaveable { mutableStateOf(SettingsPane.Account.name) }
    val pane = SettingsPane.valueOf(paneName)
    val rowFocus = remember { SettingsPane.entries.associateWith { FocusRequester() } }
    val logoutFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    var panelHasFocus by remember { mutableStateOf(false) }
    RegisterPageEntry(rowFocus.getValue(SettingsPane.Account))
    BackHandler(enabled = panelHasFocus) { runCatching { rowFocus.getValue(pane).requestFocus() } }
    if (sourceDialog) SourceAccountDialog(vm) { sourceDialog = false; runCatching { panelFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Label("الإعدادات", 23, Color.White, true, lines = 1, modifier = Modifier.align(Alignment.TopStart).padding(start = 56.mx, top = 56.mx))
        // Section list. Entering it from outside lands on the selected row.
        Column(Modifier.align(Alignment.TopStart).padding(start = 56.mx, top = 150.mx).width(560.mx)
            .focusProperties { enter = { rowFocus.getValue(pane) } }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.mx)) {
            SettingsPane.entries.forEach { entry ->
                SectionRow(entry.title, paneIcon(entry), selected = entry == pane,
                    modifier = Modifier.focusRequester(rowFocus.getValue(entry)), onFocus = { paneName = entry.name }) {
                    runCatching { panelFocus.requestFocus() }
                }
            }
            if (vm.signedIn) {
                Box(Modifier.padding(horizontal = 24.mx, vertical = 8.mx).fillMaxWidth().height(1.dp).background(Brand.Divider))
                SectionRow(if (vm.accountBusy) "جارٍ تسجيل الخروج…" else "تسجيل الخروج من سينمانا", { tint -> Icon(Icons.Rounded.ExitToApp, null, Modifier.size(30.mx), tint = tint) },
                    selected = false, danger = true, height = 72.mx, modifier = Modifier.focusRequester(logoutFocus)) { vm.logout() }
            }
        }
        // Detail panel of the selected section.
        Column(Modifier.align(Alignment.TopEnd).padding(end = 58.mx, top = 150.mx, bottom = 96.mx).width(1060.mx)
            .clip(RoundedCornerShape(24.mx)).background(Brand.Surface)
            .onFocusChanged { panelHasFocus = it.hasFocus }
            .focusRequester(panelFocus)
            .focusProperties { exit = { direction ->
                // Leaving the panel towards the list always returns to the selected row.
                if (direction == androidx.compose.ui.focus.FocusDirection.Right) rowFocus.getValue(pane) else FocusRequester.Default
            } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.mx, vertical = 40.mx),
            verticalArrangement = Arrangement.spacedBy(22.mx)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.mx)) {
                Label(pane.title, 19, Color.White, true, lines = 1)
                Label(pane.caption, 13, Brand.TextTertiary, lines = 2)
            }
            when (pane) {
                SettingsPane.Account -> {
                    ActionRow("حساب Cinema iQ والمزامنة", CloudAccount.email ?: "مزامنة قائمتك وتقدم المشاهدة بالبريد الإلكتروني") { cloudDialog = true }
                    ActionRow("حساب سينمانا", if (vm.signedIn) "تم تسجيل الدخول إلى سينمانا" else "حساب مصدر المحتوى مستقل عن حساب المزامنة") { sourceDialog = true }
                }
                SettingsPane.Profiles -> {
                    val switchProfile = LocalProfileSwitch.current
                    ActionRow("الملف الحالي", "اضغط لتبديل الملف أو إضافة ملف جديد", value = vm.profile.name) { switchProfile() }
                }
                SettingsPane.Sources -> {
                    GroupTitle("المصدر")
                    SourceChoice.entries.forEach { choice ->
                        OptionRow(choice.label, null, vm.sourceChoice == choice) { vm.chooseSource(choice) }
                    }
                    Divider()
                    ActionRow("لغة أسماء الأعمال", null, value = vm.titleLanguage.label) {
                        vm.chooseTitleLanguage(next(TitleLanguage.entries.toList(), vm.titleLanguage))
                    }
                    SwitchRow("وضع التجربة", "محتوى تجريبي لمعاينة التطبيق دون اتصال بالمصادر", vm.demo) { vm.changeDemo(!vm.demo) }
                }
                SettingsPane.Playback -> {
                    GroupTitle("جودة الفيديو")
                    listOf("تلقائي" to "أعلى جودة يوفّرها المصدر", "1080p" to null, "720p" to null, "480p" to null).forEach { (quality, hint) ->
                        OptionRow(quality, hint, vm.quality == quality) { vm.chooseQuality(quality) }
                    }
                    Divider()
                    SwitchRow("تشغيل الحلقة التالية تلقائياً", "بعد ${PlaybackSpeed.AUTO_NEXT_SECONDS} ثوانٍ من نهاية الحلقة", vm.autoNext) { vm.chooseAutoNext(!vm.autoNext) }
                    SwitchRow("فلترة المشاهد غير اللائقة", "تتخطّى المشاهد اعتمادًا على ملف الترجمة", vm.sceneFilter) { vm.chooseSceneFilter(!vm.sceneFilter) }
                    SwitchRow("تشغيل البرومو في الخلفية", "في الصفحة الرئيسية وصفحة العمل", vm.trailerPreview) { vm.chooseTrailerPreview(!vm.trailerPreview) }
                    SwitchRow("صوت البرومو", "يبدأ البرومو مكتومًا عند إيقافه", !vm.trailerMuted) { vm.chooseTrailerMuted(!vm.trailerMuted) }
                }
                SettingsPane.Subtitles -> {
                    GroupTitle("اللغة المفضلة")
                    listOf("ar" to "العربية", "en" to "الإنجليزية", "off" to "بدون ترجمة").forEach { (code, label) ->
                        OptionRow(label, null, vm.subtitleLanguage == code) { vm.chooseSubtitleLanguage(code) }
                    }
                    Divider()
                    val sizes = listOf(18f, 22f, 26f, 30f)
                    val backgrounds = listOf(0f, .25f, .5f, .75f, 1f)
                    ActionRow("حجم الخط", null, value = "${vm.subtitleSize.toInt()}") {
                        vm.chooseSubtitleSize(sizes.firstOrNull { it > vm.subtitleSize } ?: sizes.first())
                    }
                    ActionRow("خلفية الترجمة", null, value = if (vm.subtitleBackground == 0f) "بدون خلفية" else "${(vm.subtitleBackground * 100).toInt()}%") {
                        vm.chooseSubtitleBackground(next(backgrounds, backgrounds.minByOrNull { kotlin.math.abs(it - vm.subtitleBackground) } ?: .5f))
                    }
                    ActionRow("ارتفاع الترجمة", null, value = subtitleHeightLabel(vm.subtitleHeight)) {
                        vm.chooseSubtitleHeight(nextSubtitleHeight(vm.subtitleHeight))
                    }
                    // Live sample at the real subtitle size, so the effect can be judged without starting a film.
                    Box(Modifier.fillMaxWidth().height(220.mx).clip(RoundedCornerShape(16.mx)).background(Brand.Raised),
                        contentAlignment = Alignment.BottomCenter) {
                        androidx.compose.material3.Text("هكذا ستظهر الترجمة", color = Color.White, fontFamily = Cairo, fontWeight = FontWeight.Bold,
                            fontSize = (vm.subtitleSize / TvMetrics.TextScale).sp, maxLines = 1,
                            modifier = Modifier.padding(bottom = (vm.subtitleHeight * 320).toInt().mx + 12.mx)
                                .background(Color.Black.copy(alpha = vm.subtitleBackground), RoundedCornerShape(6.mx)).padding(horizontal = 16.mx))
                    }
                }
                SettingsPane.About -> {
                    ActionRow(if (vm.updateBusy) "جارٍ الفحص أو التنزيل… ${vm.updateProgress}%" else "فحص التحديثات",
                        vm.updateStatus ?: "الإصدار الحالي ${BuildConfig.VERSION_NAME}") { vm.checkUpdates(manual = true) }
                    vm.availableUpdate?.let { update ->
                        ActionRow(if (vm.updateBusy) "جارٍ تنزيل التحديث…" else "تنزيل وتثبيت ${update.versionName}", null, accent = true) { vm.downloadUpdate() }
                    }
                    Divider()
                    Label(stringResource(R.string.app_name), 15, Brand.Accent, true, lines = 1)
                    Label("الإصدار ${BuildConfig.VERSION_NAME}", 12, Brand.TextTertiary, lines = 1)
                    Label("تطوير Emad Hasnawi", 12, Brand.TextTertiary, lines = 1)
                    if (vm.artworkFromTmdb) Label("This product uses the TMDB API but is not endorsed or certified by TMDB.", 10, Brand.TextMuted, lines = 2)
                }
            }
        }
        Label("${stringResource(R.string.app_name)} · الإصدار ${BuildConfig.VERSION_NAME}", 10, Brand.TextMuted, lines = 1,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 58.mx, bottom = 40.mx))
    }
}

private fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1).mod(options.size)]

private fun paneIcon(pane: SettingsPane): @Composable (Color) -> Unit = { tint ->
    val icon: ImageVector? = when (pane) {
        SettingsPane.Account -> Icons.Rounded.AccountCircle
        SettingsPane.Profiles -> Icons.Rounded.Face
        SettingsPane.Sources -> Icons.Rounded.List
        SettingsPane.Playback -> Icons.Rounded.PlayArrow
        SettingsPane.Subtitles -> null
        SettingsPane.About -> Icons.Rounded.Info
    }
    if (icon != null) Icon(icon, null, Modifier.size(30.mx), tint = tint)
    else PlayerTransportIcon("CC", tint, 30.mx)
}

/** Section row: 80 px, icon + label. Selected (focus elsewhere) is raised slate; focused is white with dark content. */
@Composable
private fun SectionRow(
    text: String, icon: @Composable (Color) -> Unit, selected: Boolean, modifier: Modifier = Modifier,
    danger: Boolean = false, height: androidx.compose.ui.unit.Dp = 80.mx, onFocus: () -> Unit = {}, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, tween(140), label = "Settings row focus")
    val content = when { focused -> Brand.Background; danger -> Brand.Danger; selected -> Color.White; else -> Brand.TextSecondary }
    Row(modifier.zIndex(if (focused) 1f else 0f).graphicsLayer { scaleX = scale; scaleY = scale }
        .fillMaxWidth().height(height).clip(RoundedCornerShape(16.mx))
        .background(when { focused -> Color.White; selected -> Brand.Raised; else -> Color.Transparent })
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
        .padding(horizontal = 24.mx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.mx)) {
        icon(content)
        Label(text, 14, content, focused || selected, lines = 1)
    }
}

@Composable private fun GroupTitle(text: String) = Label(text, 13, Brand.TextSecondary, true, lines = 1)

@Composable private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))

/** Focus colours of panel rows: white with dark text when focused. */
@Composable
private fun PanelRow(
    selectedBorder: Boolean, modifier: Modifier = Modifier, raised: Boolean = true, onClick: () -> Unit,
    content: @Composable RowScope.(focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.mx)
    Row(modifier.fillMaxWidth().heightIn(min = 84.mx).clip(shape)
        .background(when { focused -> Color.White; raised -> Brand.Raised; else -> Color.Transparent })
        .then(if (selectedBorder && !focused) Modifier.border(2.mx, Brand.Accent, shape) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
        .padding(horizontal = 28.mx, vertical = 12.mx),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.mx)) { content(focused) }
}

@Composable
private fun RowLabels(title: String, hint: String?, focused: Boolean, modifier: Modifier, accent: Boolean = false) {
    Column(modifier) {
        Label(title, 14, if (focused) Brand.OnPrimary else if (accent) Brand.Accent else Color.White, true, lines = 1)
        if (!hint.isNullOrBlank()) Label(hint, 10, if (focused) Brand.OnPrimary.copy(alpha = .7f) else Brand.TextTertiary, lines = 2)
    }
}

/** Option row with a radio on the start side; the selected one has a 2 px accent border. */
@Composable
private fun OptionRow(title: String, hint: String?, selected: Boolean, onClick: () -> Unit) =
    PanelRow(selected, onClick = onClick) { focused ->
        Box(Modifier.size(28.mx).border(3.mx, if (selected) Brand.Accent else if (focused) Brand.OnPrimary else Brand.BorderStrong, CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(14.mx).background(Brand.Accent, CircleShape))
        }
        RowLabels(title, hint, focused, Modifier.weight(1f))
    }

/** Row that opens or cycles something; the current value sits on the end side. */
@Composable
private fun ActionRow(title: String, hint: String?, value: String? = null, accent: Boolean = false, onClick: () -> Unit) =
    PanelRow(false, onClick = onClick) { focused ->
        RowLabels(title, hint, focused, Modifier.weight(1f), accent)
        if (value != null) Label(value, 13, if (focused) Brand.OnPrimary else Brand.TextTertiary, lines = 1)
        Label("‹", 16, if (focused) Brand.OnPrimary else Brand.TextMuted, lines = 1)
    }

/** Switch row: accent track when on; in RTL the "on" thumb sits on the left (the end side). */
@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, onClick: () -> Unit) =
    PanelRow(false, raised = false, onClick = onClick) { focused ->
        RowLabels(title, hint, focused, Modifier.weight(1f))
        Box(Modifier.size(76.mx, 40.mx).background(if (checked) Brand.Accent else Brand.BorderStrong, RoundedCornerShape(20.mx)).padding(4.mx),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(32.mx).background(if (checked) Color.White else Brand.TextSecondary, CircleShape))
        }
    }
