package com.cinemaplus.rebuilt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
                Label("اختر الواجهة", 26, ColorWhite, true)
                Label("خيار مؤقت للاختبار • اختر باللمس أو الريموت", 14, Muted, lines = 2)
                TvActionButton("واجهة التلفزيون", modifier = Modifier.fillMaxWidth().focusRequester(televisionFocus)) {
                    onChoose(TestInterface.Television)
                }
                TvActionButton("واجهة الهاتف", modifier = Modifier.fillMaxWidth().focusRequester(phoneFocus)) {
                    onChoose(TestInterface.Phone)
                }
                Label("يظهر الاختيار مجددًا عند تشغيل التطبيق من جديد", 12, Muted, lines = 2)
            }
        }
    }
}

private val ColorWhite = androidx.compose.ui.graphics.Color.White
