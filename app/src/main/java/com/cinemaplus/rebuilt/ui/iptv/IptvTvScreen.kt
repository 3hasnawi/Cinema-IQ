package com.cinemaplus.rebuilt.ui.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cinemaplus.rebuilt.data.iptv.*
import com.cinemaplus.rebuilt.ui.*

private enum class IptvPanel { Sources, Form, DeleteSource, Categories, Category, DeleteCategory, Hidden }
private data class IptvPinRequest(val token: Int, val title: String, val setup: Boolean,
    val success: () -> Unit, val cancelled: () -> Unit)

/** The television section resumes a station directly; management is an explicit overlay action. */
@Composable internal fun IptvTvScreen(onExit: () -> Unit, vm: IptvTvViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val latest by rememberUpdatedState(state)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val window = LocalWindowInfo.current
    var panel by remember { mutableStateOf<IptvPanel?>(null) }
    var editing by remember { mutableStateOf<IptvSource?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var sessionEpoch by remember { mutableIntStateOf(0) }
    var enteredSource by remember { mutableStateOf<String?>(null) }
    var managingGroup by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf<IptvPinRequest?>(null) }
    var pinToken by remember { mutableIntStateOf(0) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pinAttempt by remember { mutableIntStateOf(0) }
    var dialogEpoch by remember { mutableIntStateOf(0) }
    var categoryReturnFocus by remember { mutableStateOf(false) }
    var deleteReturnFocus by remember { mutableStateOf(false) }
    var restoreWhenReady by remember { mutableStateOf(false) }
    var sourceAuthorized by remember { mutableStateOf(false) }
    val first = remember(panel, managingGroup, dialogEpoch) { FocusRequester() }
    val landingFocus = remember { FocusRequester() }
    val categoryScroll = rememberLazyListState()
    val hiddenScroll = rememberLazyListState()
    val categoryCounts = remember(state.rawChannels, state.selectedId) {
        IptvNavigation.categories(state.rawChannels, state.selectedId.orEmpty())
    }
    val channelNumbers = remember(state.rawChannels, state.selectedId) {
        IptvNavigation.numbers(state.rawChannels, state.selectedId.orEmpty())
    }
    val hidden = remember(categoryCounts, state.categoryPolicy, state.selectedId) {
        categoryCounts.keys.filterTo(linkedSetOf()) {
            IptvCategoryPolicy.key(state.selectedId.orEmpty(), it) in state.categoryPolicy.hidden
        }
    }
    val locked = remember(categoryCounts, state.categoryPolicy, state.selectedId) {
        categoryCounts.keys.filterTo(linkedSetOf()) {
            IptvCategoryPolicy.key(state.selectedId.orEmpty(), it) in state.categoryPolicy.locked
        }
    }
    val current = state.rawChannels.firstOrNull { it.sourceId == state.selectedId && it.id == playingId }
    val playing = current?.takeIf(vm::canPlay)
    val entryState = IptvTvEntryState.resolve(
        storageReady = state.storageReady, storageLoading = state.storageLoading,
        hasSources = state.sources.isNotEmpty(), busy = state.busy,
        autoStartPending = state.sources.isNotEmpty() && enteredSource != state.selectedId,
        waitingForPin = pin != null || state.securityBusy,
        hasFailure = state.message != null && pin == null
    )

    fun cancelPin() {
        val abandoned = pin
        restoreWhenReady = latest.securityBusy
        pinToken++; pin = null; pinError = null; dialogEpoch++
        abandoned?.cancelled?.invoke()
    }
    fun requirePin(title: String, setup: Boolean = false, cancelled: () -> Unit = {}, success: () -> Unit) {
        pinToken++; pinError = null; pinAttempt++
        pin = IptvPinRequest(pinToken, title, setup, success, cancelled)
    }
    fun dismiss() {
        if (panel == IptvPanel.Form) vm.cancelConnection()
        panel = null; sourceAuthorized = false
    }
    fun openSources(target: IptvPanel = IptvPanel.Sources) {
        val open = {
            sourceAuthorized = true; editing = null
            panel = if (target == IptvPanel.Sources && latest.sources.isEmpty()) IptvPanel.Form else target
        }
        // A new import must not bypass existing category locks by creating fresh source/channel IDs.
        if (latest.categoryPolicy.locked.isNotEmpty()) requirePin("إدارة المصادر", success = open) else open()
    }
    fun play(channel: IptvChannel) {
        val snapshot = latest
        if (channel.sourceId != snapshot.selectedId) return
        val start = {
            if (vm.notePlaying(channel)) {
                vm.leaveCategory()
                sessionEpoch++; playingId = channel.id; dismiss()
            }
        }
        when (snapshot.categoryPolicy.gate(channel, snapshot.unlockedCategories)) {
            IptvCategoryGate.Hidden -> Unit
            IptvCategoryGate.Allowed -> start()
            IptvCategoryGate.PinRequired -> requirePin(channel.group) {
                if (latest.selectedId == channel.sourceId) {
                    vm.unlockCategory(channel.group); start()
                }
            }
        }
    }
    fun resumeLatest() {
        val snapshot = latest
        val source = snapshot.selectedId ?: return
        val categoryKeys = HashMap<Pair<String, String>, String>()
        val candidates = snapshot.rawChannels.filter {
            categoryKeys.getOrPut(it.sourceId to it.group) { IptvCategoryPolicy.key(it) } !in snapshot.categoryPolicy.hidden
        }
        IptvTvStartup.channel(candidates, source, snapshot.collection.recent)?.let(::play)
    }
    fun categoryAction(group: String, action: String) {
        val source = latest.selectedId ?: return
        val key = IptvCategoryPolicy.key(source, group)
        val commit = {
            if (latest.selectedId == source) vm.changeCategory(group, action) {
                categoryReturnFocus = false; deleteReturnFocus = false; dialogEpoch++
                panel = if (action == "restore") IptvPanel.Hidden else IptvPanel.Categories
                val now = vm.state.value
                val active = now.rawChannels.firstOrNull { it.id == playingId }
                if (active != null && !vm.canPlay(active)) { sessionEpoch++; playingId = now.channels.firstOrNull(vm::canPlay)?.id }
            }
        }
        when {
            action == "lock" && !latest.pinConfigured && latest.categoryPolicy.locked.isEmpty() ->
                requirePin("إنشاء رمز الحماية", setup = true, success = commit)
            action == "lock" || action == "unlock" || key in latest.categoryPolicy.locked -> requirePin(group, success = commit)
            else -> commit()
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foreground = true
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false
                vm.clearUnlocks(); cancelPin()
                latest.rawChannels.firstOrNull { it.id == playingId }?.let {
                    if (!vm.canPlay(it)) enteredSource = null
                }
                if (panel == IptvPanel.Form) vm.cancelConnection()
                panel = null; editing = null; sourceAuthorized = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.clearUnlocks() }
    }
    LaunchedEffect(state.storageReady, state.selectedId, state.rawChannels, state.busy, panel, foreground) {
        if (foreground && state.storageReady && state.rawChannels.isNotEmpty() && enteredSource != state.selectedId && panel == null && pin == null) {
            enteredSource = state.selectedId
            resumeLatest()
        }
    }
    LaunchedEffect(playing == null, panel, pin, window.isWindowFocused, entryState) {
        if (playing == null && panel == null && pin == null && window.isWindowFocused && entryState != IptvTvEntryState.Loading) repeat(16) {
            withFrameNanos { }
            if (runCatching { landingFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    LaunchedEffect(restoreWhenReady, state.securityBusy, pin?.token) {
        if (restoreWhenReady && !state.securityBusy && pin == null) { restoreWhenReady = false; dialogEpoch++ }
    }
    BackHandler(enabled = playing == null && panel == null && pin == null) { vm.clearUnlocks(); onExit() }
    Box(Modifier.fillMaxSize().background(Ink)) {
        if (playing != null) key(playing.sourceId, state.selected, sessionEpoch) {
            // Channel surfing updates the canonical ID without reallocating the player's remembered listener state.
            val seed = remember { playing }
            IptvTvPlayer(seed, state.channels, null, true, state.selected?.allowHttp == true, vm::refreshPlayback,
                collection = state.collection, favorite = vm::toggleFavorite, watched = { channel ->
                    if (vm.canPlay(channel)) { playingId = channel.id; vm.noteWatched(channel) }
                }, loadProgrammes = vm::programmes, categoryCounts = categoryCounts, lockedGroups = locked, hiddenGroups = hidden,
                requestGroupAccess = { group, done, cancelled ->
                    val snapshot = latest
                    val source = snapshot.selectedId
                    val key = if (source != null && group != null) IptvCategoryPolicy.key(source, group) else null
                    when {
                        source == null || key in snapshot.categoryPolicy.hidden -> cancelled()
                        group != null && key in snapshot.categoryPolicy.locked -> {
                            // A playback continuation never replaces admission to a new category visit.
                            requirePin(group, cancelled = {
                                if (latest.selectedId == source) cancelled()
                            }) {
                                if (foreground && latest.selectedId == source && key !in latest.categoryPolicy.hidden) {
                                    vm.unlockCategory(group); done()
                                } else if (latest.selectedId == source) cancelled()
                            }
                        }
                        else -> { vm.leaveCategory(); done() }
                    }
                }, manageCategory = { group ->
                    categoryReturnFocus = false
                    managingGroup = group.orEmpty()
                    panel = if (group == null) IptvPanel.Categories else IptvPanel.Category
                }, manageSources = { openSources() }, channelChanged = { channel ->
                    if (vm.notePlaying(channel)) playingId = channel.id
                }, canContinuePlayback = vm::canPlay, categoryVisitEnded = vm::leaveCategory,
                canonicalNumbers = channelNumbers
            ) { _, _ -> vm.clearUnlocks(); onExit() }
        } else Column(Modifier.fillMaxSize().padding(48.dp).testTag("iptv.tv.landing"),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            IptvSectionIcon(Modifier.size(64.dp), Brand.TextSecondary)
            when (entryState) {
                IptvTvEntryState.Loading -> {
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator(Modifier.size(24.dp).testTag("iptv.tv.entry.loading"), color = Accent, strokeWidth = 2.dp)
                }
                IptvTvEntryState.AddSource -> {
                    Spacer(Modifier.height(20.dp)); Label("التلفزيون", 30, bold = true)
                    Spacer(Modifier.height(18.dp))
                    IptvButton("إضافة مصدر", Modifier.focusRequester(landingFocus)) { openSources(IptvPanel.Form) }
                    IptvButton("العودة") { vm.clearUnlocks(); onExit() }
                }
                IptvTvEntryState.Recovery -> {
                    Spacer(Modifier.height(20.dp)); Label("التلفزيون", 30, bold = true)
                    Spacer(Modifier.height(18.dp))
                    state.message?.let { Label(it, 15, Brand.Danger) }
                    val resumable = state.rawChannels.any { it.group !in hidden }
                    if (state.storageReady) {
                        when {
                            resumable -> IptvButton("متابعة آخر قناة", Modifier.focusRequester(landingFocus)) { resumeLatest() }
                            categoryCounts.isNotEmpty() -> IptvButton("إدارة التصنيفات", Modifier.focusRequester(landingFocus)) { panel = IptvPanel.Categories }
                            else -> IptvButton("إعادة المحاولة", Modifier.focusRequester(landingFocus)) { enteredSource = null; vm.reload(true) }
                        }
                        // Source management is explicit recovery after a real failure, never startup content.
                        if (state.message != null) IptvButton("تعديل الاتصال") { openSources() }
                    }
                    IptvButton("العودة", if (!state.storageReady) Modifier.focusRequester(landingFocus) else Modifier) { vm.clearUnlocks(); onExit() }
                }
            }
        }
        key(panel, managingGroup, dialogEpoch) {
            when (panel) {
                IptvPanel.Form -> if (sourceAuthorized) key(editing?.id ?: "new") {
                    IptvSourceForm(editing, state, ::dismiss) { source ->
                        vm.connect(source) { panel = null; sourceAuthorized = false; enteredSource = null; playingId = null }
                    }
                }
                IptvPanel.Sources -> if (sourceAuthorized) IptvDialog("المصادر", ::dismiss, first, compact = true) {
                    state.sources.forEachIndexed { index, source ->
                        IptvButton((if (source.id == state.selectedId) "✓ " else "") + source.name,
                            if (index == 0) Modifier.focusRequester(first) else Modifier, !state.busy) {
                            dismiss(); playingId = null; enteredSource = null; vm.select(source)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IptvButton("تعديل", enabled = !state.busy) { editing = source; panel = IptvPanel.Form }
                            IptvButton("حذف", enabled = !state.busy) { editing = source; panel = IptvPanel.DeleteSource }
                        }
                    }
                    IptvButton("إضافة مصدر", enabled = !state.busy) { editing = null; panel = IptvPanel.Form }
                    IptvButton("تحديث القنوات", enabled = !state.busy) { vm.reload(force = true) }
                    IptvButton("رجوع", onClick = ::dismiss)
                }
                IptvPanel.DeleteSource -> if (sourceAuthorized) IptvDialog("حذف المصدر", ::dismiss, first, compact = true) {
                    Label("حذف ${editing?.name.orEmpty()} من هذا الجهاز؟", 17)
                    state.message?.let { Label(it, 13, Brand.Danger) }
                    IptvButton("إلغاء", Modifier.focusRequester(first), !state.busy) { panel = IptvPanel.Sources }
                    IptvButton("حذف", enabled = !state.busy) { editing?.let { source -> vm.remove(source) {
                        dismiss(); playingId = null; enteredSource = null
                    } } }
                }
                IptvPanel.Categories, IptvPanel.Hidden -> IptvDialog(if (panel == IptvPanel.Hidden) "التصنيفات المحذوفة" else "إدارة التصنيفات", ::dismiss, first, compact = true) {
                    val values = categoryCounts.keys.filter { if (panel == IptvPanel.Hidden) it in hidden else it !in hidden }
                    val firstGroup = values.firstOrNull { it == managingGroup } ?: values.firstOrNull()
                    val scroll = if (panel == IptvPanel.Hidden) hiddenScroll else categoryScroll
                    LaunchedEffect(firstGroup, dialogEpoch) {
                        val index = firstGroup?.let(values::indexOf) ?: -1
                        if (index >= 0) scroll.scrollToItem(index)
                    }
                    state.message?.let { Label(it, 13, Brand.Danger) }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), state = scroll,
                        contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(values, key = { it }) { group ->
                            IptvButton(group + if (group in locked) "  🔒" else "", Modifier.fillMaxWidth()
                                .then(if (group == firstGroup) Modifier.focusRequester(first) else Modifier), !state.securityBusy) {
                                managingGroup = group
                                categoryReturnFocus = false
                                if (panel == IptvPanel.Hidden) categoryAction(group, "restore") else panel = IptvPanel.Category
                            }
                        }
                    }
                    if (panel == IptvPanel.Categories && hidden.isNotEmpty()) IptvButton("المحذوفة (${hidden.size})") { panel = IptvPanel.Hidden }
                    IptvButton("رجوع", if (firstGroup == null) Modifier.focusRequester(first) else Modifier, !state.securityBusy) {
                        if (panel == IptvPanel.Hidden) panel = IptvPanel.Categories else dismiss()
                    }
                }
                IptvPanel.Category -> IptvDialog(managingGroup, ::dismiss, first, compact = true) {
                    state.message?.let { Label(it, 13, Brand.Danger) }
                    IptvButton("فتح التصنيف", enabled = !state.securityBusy) {
                        latest.rawChannels.firstOrNull { it.group == managingGroup }?.let(::play)
                    }
                    IptvButton(if (managingGroup in locked) "إلغاء القفل برمز PIN" else "قفل برمز PIN",
                        if (categoryReturnFocus) Modifier.focusRequester(first) else Modifier, enabled = !state.securityBusy) {
                        categoryReturnFocus = true
                        categoryAction(managingGroup, if (managingGroup in locked) "unlock" else "lock")
                    }
                    IptvButton("حذف التصنيف", enabled = !state.securityBusy) { deleteReturnFocus = false; panel = IptvPanel.DeleteCategory }
                    IptvButton("رجوع", if (!categoryReturnFocus) Modifier.focusRequester(first) else Modifier, !state.securityBusy) { panel = IptvPanel.Categories }
                }
                IptvPanel.DeleteCategory -> IptvDialog("حذف «$managingGroup»؟", ::dismiss, first, compact = true) {
                    Label("يُحذف التصنيف وقنواته من قوائم الجهاز. يمكنك استعادته من المحذوفة؛ الاشتراك لا يتغير.", 15)
                    state.message?.let { Label(it, 13, Brand.Danger) }
                    IptvButton("إلغاء", if (!deleteReturnFocus) Modifier.focusRequester(first) else Modifier, !state.securityBusy) { panel = IptvPanel.Category }
                    IptvButton("حذف التصنيف", if (deleteReturnFocus) Modifier.focusRequester(first) else Modifier, !state.securityBusy) {
                        deleteReturnFocus = true; categoryAction(managingGroup, "hide")
                    }
                }
                null -> Unit
            }
        }
        pin?.let { request ->
            IptvPinPrompt(request.title, request.setup, state.securityBusy, pinError ?: state.message, pinAttempt,
                cancel = ::cancelPin, submit = { value ->
                    if (request.setup) vm.createPin(value) {
                        if (pin?.token == request.token) { pin = null; dialogEpoch++; request.success() }
                    } else vm.verifyPin(value) { result ->
                        if (pin?.token == request.token) when (result) {
                            IptvPinResult.Accepted -> { pin = null; dialogEpoch++; request.success() }
                            IptvPinResult.Missing -> { pinError = "رمز الحماية غير متاح. لم يتم فتح التصنيف."; pinAttempt++ }
                            is IptvPinResult.Wrong -> { pinError = "رمز غير صحيح. المحاولات المتبقية: ${result.remainingAttempts}"; pinAttempt++ }
                            is IptvPinResult.Wait -> { pinError = "انتظر ${(result.retryAfterMillis / 1000).coerceAtLeast(1)} ثانية قبل المحاولة مجددًا."; pinAttempt++ }
                        }
                    }
                })
        }
    }
}
