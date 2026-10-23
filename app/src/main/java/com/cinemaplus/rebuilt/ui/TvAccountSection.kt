package com.cinemaplus.rebuilt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.cinemaplus.rebuilt.CinemaViewModel
import com.cinemaplus.rebuilt.cloud.AccountTextField
import com.cinemaplus.rebuilt.cloud.CloudAccount

/** Independent entry points avoid placing an editable source field at the account page's focus root. */
@Composable
internal fun TvAccountSection(vm: CinemaViewModel, cloudVisible: Boolean, openCloud: () -> Unit) {
    val cloudFocus = remember { FocusRequester() }
    val sourceFocus = remember { FocusRequester() }
    var sourceDialog by remember { mutableStateOf(false) }
    LaunchedEffect(cloudVisible) { if(!cloudVisible) awaitTvFocus { cloudFocus } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Action("حساب Cinema iQ والمزامنة", modifier = Modifier.focusRequester(cloudFocus)
            .focusProperties { down = sourceFocus }) { openCloud() }
        Label(CloudAccount.email ?: "مزامنة قائمتك وتقدم المشاهدة بالبريد الإلكتروني", 13, Muted, lines = 2)
        Action("حساب سينمانا", modifier = Modifier.focusRequester(sourceFocus)
            .focusProperties { up = cloudFocus }) { sourceDialog = true }
        Label(if(vm.signedIn) "تم تسجيل الدخول إلى سينمانا" else "حساب مصدر المحتوى مستقل عن حساب المزامنة", 13, Muted, lines = 2)
    }
    if(sourceDialog) SourceAccountDialog(vm) { sourceDialog = false; runCatching { sourceFocus.requestFocus() } }
}

@Composable
internal fun SourceAccountDialog(vm: CinemaViewModel, dismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(vm.signedIn) { awaitTvFocus { if(vm.signedIn) actionFocus else userFocus } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("حساب سينمانا") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("هذا الحساب خاص بمصدر المحتوى، ولا يُستخدم لمزامنة بيانات Cinema iQ.")
            if(vm.signedIn) Text("تم تسجيل الدخول") else {
                AccountTextField(username, { username = it }, "اسم مستخدم سينمانا", userFocus, closeFocus, passwordFocus, Modifier.fillMaxWidth(), enabled = !vm.accountBusy)
                AccountTextField(password, { password = it }, "كلمة مرور سينمانا", passwordFocus, userFocus, actionFocus, Modifier.fillMaxWidth(), password = true, done = true, enabled = !vm.accountBusy)
            }
            vm.accountError?.let { Text(it) }
        }
    }, confirmButton = {
        TextButton(enabled = !vm.accountBusy, modifier = Modifier.focusRequester(actionFocus)
            .focusProperties { up = if(vm.signedIn) closeFocus else passwordFocus; down = closeFocus }, onClick = {
                if(vm.signedIn) vm.logout() else if(username.isNotBlank() && password.isNotEmpty()) { vm.login(username, password); password = "" }
            }) { Text(if(vm.accountBusy) "جارٍ التنفيذ…" else if(vm.signedIn) "تسجيل الخروج" else "تسجيل الدخول") }
    }, dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.focusRequester(closeFocus)
        .focusProperties { up = actionFocus; down = if(vm.signedIn) actionFocus else userFocus }) { Text("إغلاق") } })
}
