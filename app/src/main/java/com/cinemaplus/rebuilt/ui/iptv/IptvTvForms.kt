package com.cinemaplus.rebuilt.ui.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button as TvButton
import androidx.tv.material3.ButtonDefaults as TvButtonDefaults
import com.cinemaplus.rebuilt.data.iptv.*
import com.cinemaplus.rebuilt.ui.*

@Composable internal fun IptvButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    TvButton(onClick, modifier.heightIn(min = 46.dp), enabled = enabled,
        colors = TvButtonDefaults.colors(containerColor = Glass, contentColor = Brand.TextPrimary,
            focusedContainerColor = androidx.compose.ui.graphics.Color.White, focusedContentColor = Ink),
        shape = TvButtonDefaults.shape(RoundedCornerShape(50)), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
        androidx.tv.material3.Text(text, fontFamily = Cairo, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable internal fun IptvDialog(title: String, onDismiss: () -> Unit, first: FocusRequester? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = LocalWindowInfo.current
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        var focusedOnce by remember { mutableStateOf(false) }
        // Request inside the dialog's own focused window, once only; IME dismissal must not refocus the first control.
        LaunchedEffect(first, window.isWindowFocused) {
            if (first != null && window.isWindowFocused && !focusedOnce) repeat(24) {
                withFrameNanos { }
                if (runCatching { first.requestFocus() }.getOrDefault(false)) { focusedOnce = true; return@LaunchedEffect }
            }
        }
        DisposableEffect(Unit) { onDispose { runCatching { keyboard?.hide(); focus.clearFocus(force = true) } } }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxWidth(if (com.cinemaplus.rebuilt.ui.mobile.usesTelevisionInterface(androidx.compose.ui.platform.LocalContext.current)) .67f else .96f).heightIn(max = maxHeight * .88f), color = Panel.copy(alpha = .98f),
                shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Brand.BorderStrong)) {
                Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Label(title, 22, bold = true)
                    content()
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun IptvSourceForm(existing: IptvSource?, state: IptvState, cancel: () -> Unit, save: (IptvSource) -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: IptvKind.M3U) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var user by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var http by rememberSaveable { mutableStateOf(existing?.allowHttp ?: false) }
    var output by rememberSaveable { mutableStateOf(existing?.output ?: IptvOutput.Hls) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val television = com.cinemaplus.rebuilt.ui.mobile.usesTelevisionInterface(LocalContext.current)
    val targets = remember { IptvSourceControl.entries.associateWith { FocusRequester() } }
    val otherType = remember { FocusRequester() }
    var pending by remember { mutableStateOf(IptvSourceControl.Type) }
    var editing by remember { mutableStateOf<IptvSourceControl?>(null) }
    val canConnect = !state.busy && address.isNotBlank()
    fun control(node: IptvSourceControl): Modifier {
        if (!television) return Modifier
        val before = IptvSourceFocus.move(node, -1, kind, canConnect)
        val after = IptvSourceFocus.move(node, 1, kind, canConnect)
        return Modifier.focusRequester(targets.getValue(node)).focusProperties {
            up = if (before == node) FocusRequester.Cancel else targets.getValue(before)
            down = if (after == node) FocusRequester.Cancel else targets.getValue(after)
            previous = targets.getValue(IptvSourceFocus.move(node, -1, kind, canConnect, true))
            next = targets.getValue(IptvSourceFocus.move(node, 1, kind, canConnect, true))
            if (node == IptvSourceControl.Connect) { left = targets.getValue(IptvSourceControl.Cancel); right = FocusRequester.Cancel }
            if (node == IptvSourceControl.Cancel) { left = FocusRequester.Cancel; right = if (canConnect) targets.getValue(IptvSourceControl.Connect) else FocusRequester.Cancel }
        }
    }
    val edited = editing
    val fieldLabel = when (edited) {
        IptvSourceControl.Name -> "اسم المصدر"
        IptvSourceControl.Address -> if (kind == IptvKind.M3U) "رابط قائمة M3U" else "عنوان خادم Xtream"
        IptvSourceControl.User -> "اسم المستخدم"
        else -> "كلمة المرور"
    }
    IptvDialog(if (television && edited != null) fieldLabel else if (existing == null) "إضافة مصدر IPTV" else "تعديل المصدر",
        { if (television && edited != null) { pending = edited; editing = null } else cancel() },
        if (television) null else first) {
        val window = LocalWindowInfo.current
        val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
        val imeVisible = WindowInsets.isImeVisible
        // Keep one dialog window and restore browse focus only after the IME has actually closed.
        LaunchedEffect(edited, pending, window.isWindowFocused, imeVisible) {
            if (television && edited == null && window.isWindowFocused && !imeVisible) repeat(24) {
                inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                withFrameNanos { }
                if (runCatching { targets.getValue(pending).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
        if (television && edited != null) {
            val value = when (edited) { IptvSourceControl.Name -> name; IptvSourceControl.Address -> address; IptvSourceControl.User -> user; else -> password }
            val next = IptvSourceFocus.move(edited, 1, kind, canConnect)
            key(edited) {
                IptvTvFieldEditor(fieldLabel, value,
                    when (edited) { IptvSourceControl.Address -> KeyboardType.Uri; IptvSourceControl.Password -> KeyboardType.Password; else -> KeyboardType.Text },
                    edited == IptvSourceControl.Password,
                    when (edited) { IptvSourceControl.Name -> 100; IptvSourceControl.Address -> 8192; IptvSourceControl.User -> 1024; else -> 4096 },
                    if (next in listOf(IptvSourceControl.Address, IptvSourceControl.User, IptvSourceControl.Password)) ImeAction.Next else ImeAction.Done,
                    commit = { updated ->
                        when (edited) { IptvSourceControl.Name -> name = updated; IptvSourceControl.Address -> address = updated; IptvSourceControl.User -> user = updated; else -> password = updated }
                        pending = next; editing = null
                    }, cancel = { pending = edited; editing = null })
            }
            return@IptvDialog
        }
        val keyboard = LocalSoftwareKeyboardController.current
        val manager = LocalFocusManager.current
        fun closeKeyboard() { keyboard?.hide(); manager.clearFocus(force = true) }
        fun inputActions() = KeyboardActions(onNext = {
            manager.moveFocus(FocusDirection.Next)
        }, onDone = { closeKeyboard() })
        LaunchedEffect(state.busy) {
            if (television && state.busy) repeat(24) {
                withFrameNanos { }
                if (runCatching { targets.getValue(IptvSourceControl.Cancel).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val m3u = if (kind == IptvKind.M3U) targets.getValue(IptvSourceControl.Type) else otherType
            val xtream = if (kind == IptvKind.Xtream) targets.getValue(IptvSourceControl.Type) else otherType
            IptvButton(if (kind == IptvKind.M3U) "✓ M3U" else "M3U",
                if (!television) Modifier.focusRequester(first) else Modifier.focusRequester(m3u).focusProperties {
                    left = xtream; right = FocusRequester.Cancel; down = targets.getValue(IptvSourceControl.Name); next = down; previous = targets.getValue(IptvSourceControl.Cancel)
                }, !state.busy) { kind = IptvKind.M3U }
            IptvButton(if (kind == IptvKind.Xtream) "✓ Xtream" else "Xtream",
                if (!television) Modifier else Modifier.focusRequester(xtream).focusProperties {
                    right = m3u; left = FocusRequester.Cancel; down = targets.getValue(IptvSourceControl.Name); next = down; previous = targets.getValue(IptvSourceControl.Cancel)
                }, !state.busy) { kind = IptvKind.Xtream }
        }
        IptvSourceInput("اسم المصدر", name, !state.busy, television, control(IptvSourceControl.Name),
            options = KeyboardOptions(imeAction = ImeAction.Next), actions = inputActions(),
            change = { name = it.take(100) }, edit = { editing = IptvSourceControl.Name })
        val addressAction = if (kind == IptvKind.Xtream) ImeAction.Next else ImeAction.Done
        IptvSourceInput(if (kind == IptvKind.M3U) "رابط قائمة M3U" else "عنوان خادم Xtream", address, !state.busy, television,
            control(IptvSourceControl.Address), options = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = addressAction),
            actions = inputActions(), change = { address = it.take(8192) }, edit = { editing = IptvSourceControl.Address })
        if (kind == IptvKind.Xtream) {
            IptvSourceInput("اسم المستخدم", user, !state.busy, television, control(IptvSourceControl.User),
                options = KeyboardOptions(imeAction = ImeAction.Next), actions = inputActions(),
                change = { user = it.take(1024) }, edit = { editing = IptvSourceControl.User })
            IptvSourceInput("كلمة المرور", password, !state.busy, television, control(IptvSourceControl.Password), secret = true,
                options = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), actions = inputActions(),
                change = { password = it.take(4096) }, edit = { editing = IptvSourceControl.Password })
            IptvButton(if (output == IptvOutput.Hls) "صيغة البث: HLS (مفضّل)" else "صيغة البث: MPEG-TS", control(IptvSourceControl.Output), enabled = !state.busy) {
                output = if (output == IptvOutput.Hls) IptvOutput.TransportStream else IptvOutput.Hls
            }
        }
        IptvButton(if (http) "✓ السماح باتصال HTTP لهذا المصدر" else "السماح باتصال HTTP لهذا المصدر", control(IptvSourceControl.Http), enabled = !state.busy) { http = !http }
        Label("فعّل HTTP إذا كان رابط المصدر أو بعض قنواته غير مشفّر. قد تمر بيانات الاشتراك دون تشفير؛ يُفضّل HTTPS.", 12, Brand.TextTertiary)
        (error ?: state.message)?.let { Label(it, 13, Brand.Danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IptvButton(if (state.busy) "جارٍ الاتصال…" else "اتصال وحفظ", control(IptvSourceControl.Connect), enabled = canConnect) {
                closeKeyboard()
                try {
                    val raw = address.trim().let { if (it.contains("://")) it else "https://$it" }
                    val source = IptvSource(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name, kind = kind,
                        address = raw, username = if (kind == IptvKind.Xtream) user.trim() else "",
                        password = if (kind == IptvKind.Xtream) password else "", allowHttp = http, output = output).validated()
                    error = null; save(source)
                } catch (e: Exception) { error = iptvMessage(e) }
            }
            IptvButton("إلغاء", control(IptvSourceControl.Cancel)) { closeKeyboard(); cancel() }
        }
        Label("تحفظ بيانات المصدر مشفّرة على هذا الجهاز، دون مزامنتها مع الحساب. Xtream للقنوات المباشرة فقط.", 12, Brand.TextTertiary)
    }
}

@Composable private fun IptvSourceInput(label: String, value: String, enabled: Boolean, television: Boolean,
    modifier: Modifier, secret: Boolean = false, options: KeyboardOptions, actions: KeyboardActions,
    change: (String) -> Unit, edit: () -> Unit) {
    if (television) IptvTvInput(label, value, enabled, secret, modifier, edit)
    else OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, enabled = enabled,
        modifier = modifier.fillMaxWidth(), visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = options, keyboardActions = actions)
}
