package com.cinemaplus.rebuilt.ui.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
        Text(text, fontFamily = Cairo)
    }
}

@Composable internal fun IptvDialog(title: String, onDismiss: () -> Unit, first: FocusRequester? = null, content: @Composable ColumnScope.() -> Unit) {
    LaunchedEffect(first) {
        if (first != null) repeat(12) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxWidth(.67f).heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .88f).dp), color = Panel.copy(alpha = .98f),
                shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Brand.BorderStrong)) {
                Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Label(title, 22, bold = true)
                    content()
                }
            }
        }
    }
}

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
    IptvDialog(if (existing == null) "إضافة مصدر IPTV" else "تعديل المصدر", cancel, first) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IptvButton(if (kind == IptvKind.M3U) "✓ M3U" else "M3U", Modifier.focusRequester(first), !state.busy) { kind = IptvKind.M3U }
            IptvButton(if (kind == IptvKind.Xtream) "✓ Xtream" else "Xtream", enabled = !state.busy) { kind = IptvKind.Xtream }
        }
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("اسم المصدر") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, { address = it.take(8192) }, label = { Text(if (kind == IptvKind.M3U) "رابط قائمة M3U" else "عنوان خادم Xtream") },
            singleLine = true, enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        if (kind == IptvKind.Xtream) {
            OutlinedTextField(user, { user = it.take(1024) }, label = { Text("اسم المستخدم") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it.take(4096) }, label = { Text("كلمة المرور") }, singleLine = true, enabled = !state.busy,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            IptvButton(if (output == IptvOutput.Hls) "صيغة البث: HLS (مفضّل)" else "صيغة البث: MPEG-TS", enabled = !state.busy) {
                output = if (output == IptvOutput.Hls) IptvOutput.TransportStream else IptvOutput.Hls
            }
        }
        IptvButton(if (http) "✓ السماح باتصال HTTP لهذا المصدر" else "السماح باتصال HTTP لهذا المصدر", enabled = !state.busy) { http = !http }
        Label("فعّل HTTP إذا كان رابط المصدر أو بعض قنواته غير مشفّر. قد تمر بيانات الاشتراك دون تشفير؛ يُفضّل HTTPS.", 12, Brand.TextTertiary)
        (error ?: state.message)?.let { Label(it, 13, Brand.Danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IptvButton(if (state.busy) "جارٍ الاتصال…" else "اتصال وحفظ", enabled = !state.busy && address.isNotBlank()) {
                try {
                    val raw = address.trim().let { if (it.contains("://")) it else "https://$it" }
                    val source = IptvSource(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name, kind = kind,
                        address = raw, username = if (kind == IptvKind.Xtream) user.trim() else "",
                        password = if (kind == IptvKind.Xtream) password else "", allowHttp = http, output = output).validated()
                    error = null; save(source)
                } catch (e: Exception) { error = iptvMessage(e) }
            }
            IptvButton("إلغاء", onClick = cancel)
        }
        Label("تحفظ بيانات المصدر مشفّرة على هذا الجهاز، دون مزامنتها مع الحساب. Xtream للقنوات المباشرة فقط.", 12, Brand.TextTertiary)
    }
}
