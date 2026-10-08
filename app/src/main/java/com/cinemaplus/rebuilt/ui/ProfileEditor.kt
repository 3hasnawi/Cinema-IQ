package com.cinemaplus.rebuilt.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cinemaplus.rebuilt.data.Profile
import com.cinemaplus.rebuilt.data.ProfileList
import com.cinemaplus.rebuilt.data.ProfileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProfileEditor(store: ProfileStore, profile: Profile?, onClose: () -> Unit, onChanged: () -> Unit) {
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var avatar by remember { mutableStateOf(profile?.avatar ?: "avatar-${store.profiles().size % 6}") }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val pendingImages = remember { mutableListOf<String>() }
    val scope = rememberCoroutineScope()
    val preview = (profile ?: ProfileList.default()).copy(name = name.ifBlank { "ملف جديد" }, avatar = avatar)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            importing = true; error = null
            try {
                val image = store.importAvatar(uri)
                pendingImages.add(image); avatar = image
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { error = "تعذّر استيراد الصورة. اختر صورة أخرى." }
            finally { importing = false }
        }
    }
    // Imported drafts are removed when cancelled; only the saved portrait remains.
    DisposableEffect(Unit) {
        onDispose { pendingImages.filter { !saved || it != avatar }.forEach(store::discardImportedImage) }
    }
    AlertDialog(onDismissRequest = { if(!importing) onClose() }, containerColor = Panel,
        title = { Label(if(profile == null) "ملف جديد" else "تعديل الملف الشخصي", 20, bold = true) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ProfileAvatar(preview, Modifier.size(80.dp))
                OutlinedTextField(name, { name = it.take(16) }, label = { Label("الاسم") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !importing)
                Label("اختر صورة رمزية", 14, Muted)
                ProfileList.avatars.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { value ->
                            var focused by remember { mutableStateOf(false) }
                            Box(Modifier.size(64.dp).border(if(focused) 3.dp else 2.dp,
                                if(focused || avatar == value) Color.White else Color.Transparent, CircleShape)
                                .onFocusChanged { focused = it.isFocused }
                                .semantics { contentDescription = "صورة رمزية ${ProfileList.avatars.indexOf(value) + 1}" }
                                .selectable(selected = avatar == value, enabled = !importing, role = Role.RadioButton) { avatar = value },
                                contentAlignment = Alignment.Center) {
                                ProfileAvatar(preview.copy(avatar = value), Modifier.size(56.dp))
                            }
                        }
                    }
                }
                TextButton(enabled = !importing, onClick = {
                    try { picker.launch(arrayOf("image/*")) }
                    catch(_: Exception) { error = "لا يوجد منتقي صور على الجهاز. ثبّت مدير ملفات يدعم اختيار الصور." }
                }) { Label(if(importing) "جارٍ استيراد الصورة…" else "استيراد صورة من الجهاز", 14) }
                error?.let { Label(it, 12, Signal, lines = 2) }
                if(profile != null && profile.id != ProfileList.MAIN) TextButton(enabled = !importing, onClick = { deleting = true }) {
                    Label("حذف الملف الشخصي", 14, Signal)
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !importing, onClick = {
            val updated = if(profile == null) store.add(name, avatar) else store.update(profile.id, name, avatar)
            if(updated != null) { saved = true; onChanged(); onClose() } else error = "تعذّر حفظ الملف الشخصي."
        }) { Label("حفظ", 15, bold = true) } },
        dismissButton = { TextButton(enabled = !importing, onClick = onClose) { Label("إلغاء", 15) } })
    if(deleting && profile != null) AlertDialog(onDismissRequest = { deleting = false }, containerColor = Panel,
        title = { Label("حذف ${profile.name}؟", 18, bold = true) },
        text = { Label("سيُحذف سجل المشاهدة والقائمة الخاصة بهذا الملف.", 14, Muted) },
        confirmButton = { TextButton(onClick = { store.remove(profile.id); onChanged(); onClose() }) { Label("حذف", 14, Signal) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Label("إلغاء", 14) } })
}
