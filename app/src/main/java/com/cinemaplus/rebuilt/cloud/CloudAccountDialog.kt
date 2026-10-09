package com.cinemaplus.rebuilt.cloud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Touch and D-pad accessible email account controls, independent of source login. */
@Composable
fun CloudAccountDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(CloudAccount.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val primaryFocus = remember { FocusRequester() }
    val registerFocus = remember { FocusRequester() }
    val resetFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    val secondaryFocus = remember { FocusRequester() }
    val logoutFocus = remember { FocusRequester() }
    LaunchedEffect(CloudAccount.email, CloudAccount.verified) {
        com.cinemaplus.rebuilt.ui.awaitTvFocus { if(CloudAccount.email == null) emailFocus else primaryFocus }
    }

    fun action(block: suspend () -> Unit) {
        if(busy) return
        scope.launch {
            busy = true; message = null
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { message = "تعذّر تنفيذ الطلب. تحقق من البيانات والاتصال، ثم أعد المحاولة." }
            finally { busy = false; password = "" }
        }
    }
    AlertDialog(onDismissRequest = { if(!busy) onDismiss() },
        title = { Text("حساب Cinema iQ والمزامنة") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("حساب مستقل لمزامنة قائمتك وتقدم المشاهدة، ولا يغيّر حساب مصدر المحتوى.")
            if(CloudAccount.email != null) {
                Text(CloudAccount.email.orEmpty())
                if(!CloudAccount.verified) {
                    Text("أكد البريد من الرسالة التي وصلتك، ثم اضغط تحقق.")
                    TextButton(modifier = Modifier.focusRequester(primaryFocus).focusProperties { up = closeFocus; down = secondaryFocus }, enabled = !busy, onClick = { action { CloudAccount.verifyAgain(); message = if(CloudAccount.verified) "تم تأكيد الحساب" else "البريد لم يؤكد بعد" } }) { Text("تحقق من تأكيد البريد") }
                    TextButton(modifier = Modifier.focusRequester(secondaryFocus).focusProperties { up = primaryFocus; down = logoutFocus }, enabled = !busy, onClick = { action { CloudAccount.resendVerification(); message = "أُرسلت رسالة التأكيد" } }) { Text("إعادة إرسال التأكيد") }
                } else {
                    Text(CloudLibrarySync.status)
                    CloudProfileSync.error?.let { Text(it) }
                    TextButton(modifier = Modifier.focusRequester(primaryFocus).focusProperties { up = closeFocus; down = logoutFocus }, enabled = !busy, onClick = { showImport = true }) { Text("ضم بيانات هذا الجهاز إلى الحساب") }
                }
                TextButton(modifier = Modifier.focusRequester(logoutFocus).focusProperties { up = if(CloudAccount.verified) primaryFocus else secondaryFocus; down = closeFocus }, enabled = !busy, onClick = { CloudAccount.logout(); onDismiss() }) { Text("تسجيل الخروج") }
            } else {
                AccountTextField(email, { email = it }, "البريد الإلكتروني", emailFocus, closeFocus, passwordFocus, Modifier.fillMaxWidth(), email = true, enabled = !busy)
                AccountTextField(password, { password = it }, "كلمة المرور", passwordFocus, emailFocus, primaryFocus, Modifier.fillMaxWidth(), password = true, done = true, enabled = !busy)
                Row {
                    TextButton(modifier = Modifier.focusRequester(primaryFocus).focusProperties { up = passwordFocus; down = resetFocus; left = registerFocus }, enabled = !busy, onClick = {
                        if(email.isBlank() || password.isEmpty()) message = "أدخل البريد وكلمة المرور" else action { CloudAccount.login(email,password); message = if(CloudAccount.verified) "تم تسجيل الدخول" else "أكد بريدك لتفعيل المزامنة" }
                    }) { Text("دخول") }
                    TextButton(modifier = Modifier.focusRequester(registerFocus).focusProperties { up = passwordFocus; down = resetFocus; right = primaryFocus }, enabled = !busy, onClick = {
                        if(email.isBlank() || password.length < 6) message = "أدخل بريدك وكلمة مرور من 6 أحرف على الأقل" else action { CloudAccount.register(email,password); message = "أُرسلت رسالة لتأكيد بريدك" }
                    }) { Text("إنشاء حساب") }
                }
                TextButton(modifier = Modifier.focusRequester(resetFocus).focusProperties { up = primaryFocus; down = closeFocus }, enabled = !busy, onClick = {
                    if(email.isBlank()) message = "أدخل بريدك الإلكتروني أولاً" else action { CloudAccount.reset(email); message = "إذا كان البريد مسجلاً فستصلك رسالة استعادة" }
                }) { Text("نسيت كلمة المرور") }
            }
            if(busy) CircularProgressIndicator()
            message?.let { Text(it) }
        } }, confirmButton = { TextButton(modifier = Modifier.focusRequester(closeFocus).focusProperties { up = if(CloudAccount.email == null) resetFocus else logoutFocus; down = if(CloudAccount.email == null) emailFocus else primaryFocus }, enabled = !busy, onClick = onDismiss) { Text("إغلاق") } })
    if(showImport) AlertDialog(onDismissRequest = { showImport = false }, title = { Text("ضم بيانات الجهاز؟") },
        text = { Text("ستُضاف قوائم وبيانات المشاهدة المحلية إلى هذا الحساب. قد تحل بيانات الجهاز محل تقدم العمل نفسه في الحساب.") },
        confirmButton = { TextButton(onClick = { CloudImport.importLocal(context); showImport = false; message = "أُضيفت البيانات؛ تُزامن عند الاتصال" }) { Text("ضم البيانات") } },
        dismissButton = { TextButton(onClick = { showImport = false }) { Text("إلغاء") } })
}
