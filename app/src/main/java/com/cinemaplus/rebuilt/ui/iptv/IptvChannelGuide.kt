package com.cinemaplus.rebuilt.ui.iptv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cinemaplus.rebuilt.data.iptv.*
import com.cinemaplus.rebuilt.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Browser state is separate from the playing queue; moving focus never opens a stream. */
@Composable internal fun IptvChannelGuide(channels: List<IptvChannel>, current: IptvChannel, group: String?, television: Boolean,
    setGroup: (String?) -> Unit, tune: (IptvChannel) -> Unit, close: () -> Unit,
    previewChanged: (Rect) -> Unit = {}, status: String = "") {
    var query by remember { mutableStateOf("") }
    var browsingGroup by remember { mutableStateOf(group) }
    var visible by remember { mutableStateOf(emptyList<IptvChannel>()) }
    val groups = remember(channels, current.sourceId) { IptvNavigation.categories(channels, current.sourceId) }
    val numbering = remember(channels, current.sourceId) { IptvNavigation.numbers(channels, current.sourceId) }
    val logos = rememberIptvLogos()
    val scroll = rememberLazyListState(); val categories = rememberLazyListState()
    val entryFocus = remember { FocusRequester() }; val categoryFocus = remember { FocusRequester() }
    val target = IptvNavigation.entry(visible, current.id)
    var searching by remember { mutableStateOf(false) }
    var categoryFocused by remember { mutableStateOf(false) }
    BackHandler(onBack = close)
    val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize().padding(if (television) 24.dp else 14.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxWidth().height(maxHeight * .94f),
                color = Panel.copy(alpha = .96f), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Brand.BorderStrong)) {
                Column(Modifier.padding(if (television) 22.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Label("القنوات", if (television) 23 else 21, bold = true)
                        Spacer(Modifier.weight(1f)); IptvButton("إغلاق", onClick = close)
                    }
                    OutlinedTextField(query, { query = it.take(200) }, label = { Text("ابحث عن قناة") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { searching = it.isFocused })
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(if (television) 16.dp else 8.dp)) {
                        LazyColumn(state = categories, modifier = Modifier.width(if (television) 170.dp else 112.dp).focusGroup()
                            .onFocusChanged { categoryFocused = it.hasFocus }
                            .onPreviewKeyEvent { event ->
                                val key = event.nativeKeyEvent
                                if (television && key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && visible.isNotEmpty()) {
                                    if (key.action == KeyEvent.ACTION_DOWN) runCatching { entryFocus.requestFocus() }; true
                                } else false
                            }, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            item { IptvButton((if (browsingGroup == null) "✓ " else "") + "كل القنوات · ${numbering.size}",
                                if (browsingGroup == null) Modifier.focusRequester(categoryFocus) else Modifier) { browsingGroup = null; query = "" } }
                            items(groups.entries.toList(), key = { it.key }) { entry ->
                                IptvButton((if (browsingGroup == entry.key) "✓ " else "") + entry.key + " · " + entry.value,
                                    if (browsingGroup == entry.key) Modifier.focusRequester(categoryFocus) else Modifier) { browsingGroup = entry.key; query = "" }
                            }
                        }
                        LazyColumn(state = scroll, modifier = Modifier.weight(1f).focusGroup().onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent
                            if (television && key.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                                if (key.action == KeyEvent.ACTION_DOWN) runCatching { categoryFocus.requestFocus() }; true
                            } else false
                        }, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(6.dp)) {
                            items(visible, key = { it.id }) { channel ->
                                IptvChannelRow(channel, numbering[channel.id] ?: 0, channel.id == current.id, logos,
                                    if (channel.id == target) Modifier.focusRequester(entryFocus) else Modifier) {
                                    setGroup(browsingGroup); tune(channel)
                                }
                            }
                            if (visible.isEmpty()) item { Label("لا توجد قنوات تطابق البحث أو التصنيف.", 14, Brand.TextTertiary) }
                        }
                        if (television) Column(Modifier.weight(.85f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).onGloballyPositioned { previewChanged(it.boundsInRoot()) })
                            Label(current.name, 20, bold = true, lines = 2)
                            Label(current.group, 14, Brand.TextTertiary, lines = 1)
                            Label(status, 12, Brand.TextTertiary, lines = 2)
                            IptvButton("ملء الشاشة", onClick = close)
                            Label("اختر قناة للمعاينة. اضغط عليها مرة أخرى لملء الشاشة.", 13, Brand.TextTertiary)
                        }
                    }
                }
            }
        }
    }
    // TV stays in the same window as the one native video surface, allowing a real continuing preview.
    if (television) content() else Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
    LaunchedEffect(channels, browsingGroup, query) {
        val result = withContext(Dispatchers.Default) { IptvNavigation.channels(channels, current.sourceId, browsingGroup, query) }
        visible = result
        if (television) {
            val index = result.indexOfFirst { it.id == IptvNavigation.entry(result, current.id) }
            if (index >= 0) scroll.scrollToItem(index)
            if (searching || categoryFocused) return@LaunchedEffect
            repeat(12) {
                withFrameNanos { }
                if (runCatching { if (index >= 0) entryFocus.requestFocus() else categoryFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
    }
}
