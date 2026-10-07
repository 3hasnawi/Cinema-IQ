package com.cinemaplus.rebuilt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.delay

/** Temporary launch selection; saved only for this activity, never as a permanent device preference. */
internal enum class TestInterface { Television, Phone }

@Composable
internal fun InterfaceTestPicker(televisionDevice: Boolean, onChoose: (TestInterface) -> Unit) {
    val televisionFocus = remember { FocusRequester() }
    val phoneFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { if (televisionDevice) televisionFocus.requestFocus() else phoneFocus.requestFocus() }
    }
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Ink).safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Label("Cinema iQ", 28, Accent, true)
                Label("اختر الواجهة", 26, Color.White, true)
                Label("خيار مؤقت للاختبار • اختر باللمس أو الريموت", 14, Muted, lines = 2)
                InterfaceChoiceButton("واجهة التلفزيون", modifier = Modifier.fillMaxWidth().focusRequester(televisionFocus)) {
                    onChoose(TestInterface.Television)
                }
                InterfaceChoiceButton("واجهة الهاتف", modifier = Modifier.fillMaxWidth().focusRequester(phoneFocus)) {
                    onChoose(TestInterface.Phone)
                }
                Label("يظهر الاختيار مجددًا عند تشغيل التطبيق من جديد", 12, Muted, lines = 2)
            }
        }
    }
}

/** Foundation clickable handles touch, mouse and keyboard/remote selection on the same target. */
@Composable
private fun InterfaceChoiceButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Box(modifier.heightIn(min = 56.dp)
        .onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .background(if (focused) Color.White else PanelHi)
        .border(2.dp, if (focused) Accent else InputBorder, shape)
        .clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 22.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        Label(text, 16, if (focused) OnAccent else Color.White, true, lines = 1)
    }
}
