package com.cinemaplus.rebuilt.ui.iptv

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cinemaplus.rebuilt.data.iptv.*
import com.cinemaplus.rebuilt.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A modal focus boundary over continuing playback: arrows browse here and never accidentally tune the background. */
@Composable internal fun IptvChannelGuide(channels: List<IptvChannel>, current: IptvChannel, group: String?, television: Boolean,
    setGroup: (String?) -> Unit, tune: (IptvChannel) -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(emptyList<IptvChannel>()) }
    val groups = remember(channels) { channels.filter { it.sourceId == current.sourceId }.map { it.group }.distinct().sorted() }
    val scroll = rememberLazyListState(); val categories = rememberLazyListState()
    val first = remember { FocusRequester() }; val all = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val target = IptvNavigation.entry(visible, current.id)
    var searching by remember { mutableStateOf(false) }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.CenterStart) {
            Surface(Modifier.fillMaxWidth(if (television) .78f else 1f).height(maxHeight * .92f),
                color = Panel.copy(alpha = .96f), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Brand.BorderStrong)) {
                Column(Modifier.padding(if (television) 26.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Label("القنوات", 23, bold = true); Spacer(Modifier.weight(1f)); IptvButton("إغلاق", onClick = close)
                    }
                    OutlinedTextField(query, { query = it.take(200) }, label = { Text("ابحث عن قناة") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { searching = it.isFocused })
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        LazyColumn(state = categories, modifier = Modifier.width(if (television) 220.dp else 126.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { IptvButton(if (group == null) "✓ كل القنوات" else "كل القنوات", Modifier.focusRequester(all)) { searching = false; setGroup(null); query = ""; focusManager.clearFocus() } }
                            items(groups, key = { it }) { value -> IptvButton((if (value == group) "✓ " else "") + value) {
                                searching = false; setGroup(value); query = ""; focusManager.clearFocus()
                            } }
                        }
                        LazyColumn(state = scroll, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(8.dp)) {
                            items(visible, key = { it.id }) { channel ->
                                IptvButton((if (channel.id == current.id) "▶ " else "") + channel.name,
                                    Modifier.fillMaxWidth().then(if (channel.id == target) Modifier.focusRequester(first) else Modifier)) { tune(channel) }
                            }
                            if (visible.isEmpty()) item { Label("لا توجد قنوات تطابق البحث أو التصنيف.", 14, Brand.TextTertiary) }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(channels, group, query) {
        val result = withContext(Dispatchers.Default) { IptvNavigation.channels(channels, current.sourceId, group, query) }
        visible = result
        if (television && !searching) {
            val index = result.indexOfFirst { it.id == IptvNavigation.entry(result, current.id) }
            if (index >= 0) scroll.scrollToItem(index)
            repeat(12) {
                withFrameNanos { }
                if (runCatching { if (index >= 0) first.requestFocus() else all.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
    }
}
