package com.cinemaplus.rebuilt.cloud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Touch and D-pad accessible email account controls, independent of source login. */
@Composable
fun CloudAccountDialog(sourceSignedIn: Boolean = true, onSourceLogin: ((String, String) -> Unit)? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(CloudAccount.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showImport by remember { mutableStateOf(false) }
    // One screen signs in to both accounts. The source password stays on this device and never goes to Firebase.
    var sourceUser by remember { mutableStateOf("") }
    var sourcePassword by remember { mutableStateOf("") }
    val askSource = !sourceSignedIn && onSourceLogin != null
    fun signInSource() {
        if(askSource && sourceUser.isNotBlank() && sourcePassword.isNotEmpty()) onSourceLogin?.invoke(sourceUser.trim(), sourcePassword)
        sourcePassword = ""
    }
    @Composable fun SourceFields() {
        if(!askSource) return
        Text("حساب سينمانا (اختياري، يبقى على هذا الجهاز فقط)")
        OutlinedTextField(sourceUser, { sourceUser = it }, label = { Text("اسم مستخدم سينمانا") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourcePassword, { sourcePassword = it }, label = { Text("كلمة مرور سينمانا") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
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
                    TextButton(enabled = !busy, onClick = { action { CloudAccount.verifyAgain(); message = if(CloudAccount.verified) "تم تأكيد الحساب" else "البريد لم يؤكد بعد" } }) { Text("تحقق من تأكيد البريد") }
                    TextButton(enabled = !busy, onClick = { action { CloudAccount.resendVerification(); message = "أُرسلت رسالة التأكيد" } }) { Text("إعادة إرسال التأكيد") }
                } else {
                    Text(CloudLibrarySync.status)
                    CloudProfileSync.error?.let { Text(it) }
                    TextButton(enabled = !busy, onClick = { showImport = true }) { Text("ضم بيانات هذا الجهاز إلى الحساب") }
                    SourceFields()
                    if(askSource) TextButton(enabled = sourceUser.isNotBlank() && sourcePassword.isNotEmpty(),
                        onClick = { signInSource(); message = "جارٍ تسجيل الدخول إلى سينمانا" }) { Text("دخول سينمانا") }
                }
                TextButton(enabled = !busy, onClick = { CloudAccount.logout(); onDismiss() }) { Text("تسجيل الخروج") }
            } else {
                OutlinedTextField(email, { email = it }, label = { Text("البريد الإلكتروني") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("كلمة المرور") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                SourceFields()
                Row {
                    TextButton(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = { action { CloudAccount.login(email,password); signInSource(); message = if(CloudAccount.verified) "تم تسجيل الدخول" else "أكد بريدك لتفعيل المزامنة" } }) { Text("دخول") }
                    TextButton(enabled = !busy && email.isNotBlank() && password.length >= 6, onClick = { action { CloudAccount.register(email,password); signInSource(); message = "أُرسلت رسالة لتأكيد بريدك" } }) { Text("إنشاء حساب") }
                }
                TextButton(enabled = !busy && email.isNotBlank(), onClick = { action { CloudAccount.reset(email); message = "إذا كان البريد مسجلاً فستصلك رسالة استعادة" } }) { Text("نسيت كلمة المرور") }
            }
            if(busy) CircularProgressIndicator()
            message?.let { Text(it) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("إغلاق") } })
    if(showImport) AlertDialog(onDismissRequest = { showImport = false }, title = { Text("ضم بيانات الجهاز؟") },
        text = { Text("ستُضاف قوائم وبيانات المشاهدة المحلية إلى هذا الحساب. قد تحل بيانات الجهاز محل تقدم العمل نفسه في الحساب.") },
        confirmButton = { TextButton(onClick = { CloudImport.importLocal(context); showImport = false; message = "أُضيفت البيانات؛ تُزامن عند الاتصال" }) { Text("ضم البيانات") } },
        dismissButton = { TextButton(onClick = { showImport = false }) { Text("إلغاء") } })
}
