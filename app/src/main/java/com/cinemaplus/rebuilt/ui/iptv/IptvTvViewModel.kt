package com.cinemaplus.rebuilt.ui.iptv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cinemaplus.rebuilt.data.iptv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class IptvState(
    val sources: List<IptvSource> = emptyList(), val selectedId: String? = null,
    val channels: List<IptvChannel> = emptyList(), val visible: List<IptvChannel> = emptyList(),
    val query: String = "", val group: String? = null, val busy: Boolean = false,
    val filtering: Boolean = false, val storageReady: Boolean = false, val storageLoading: Boolean = true,
    val message: String? = null, val focusEpoch: Int = 0
) { val selected: IptvSource? get() = sources.firstOrNull { it.id == selectedId } }

internal class IptvTvViewModel(app: Application) : AndroidViewModel(app) {
    private val vault = IptvSourceStore(app)
    private val prefs = app.getSharedPreferences("tv_iptv_v1", 0)
    private val client = IptvClient()
    private val mutable = MutableStateFlow(IptvState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var filter: Job? = null
    private val cache = linkedMapOf<String, Pair<Long, IptvCatalog>>()
    init { viewModelScope.launch {
        try {
            val sources = withContext(Dispatchers.IO) { vault.read() }
            val selected = prefs.getString("selected", null)?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id
            mutable.value = mutable.value.copy(sources = sources, selectedId = selected, storageLoading = false, storageReady = true)
            reload()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(storageLoading = false, message = iptvMessage(e)) }
    } }
    fun select(source: IptvSource) {
        if (mutable.value.busy) return
        prefs.edit().putString("selected", source.id).apply()
        mutable.value = mutable.value.copy(selectedId = source.id, group = null, query = "", channels = emptyList(), visible = emptyList())
        reload()
    }
    fun query(value: String) { mutable.value = mutable.value.copy(query = value.take(200)); applyFilter(false) }
    fun group(value: String?) { mutable.value = mutable.value.copy(group = value); applyFilter(true) }
    fun reload(force: Boolean = false) {
        request?.cancel(); filter?.cancel()
        val source = mutable.value.selected ?: run { mutable.value = mutable.value.copy(channels = emptyList(), visible = emptyList(), busy = false); return }
        request = viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, message = null)
            try {
                val recent = cache[source.id]?.takeIf { android.os.SystemClock.elapsedRealtime() - it.first < 300_000 }
                val catalog = if (force || recent == null) client.load(source) else recent.second
                ensureActive(); putCache(source.id, catalog)
                mutable.value = mutable.value.copy(channels = catalog.channels, busy = false)
                applyFilter(true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, message = iptvMessage(e)) }
        }
    }
    fun connect(source: IptvSource, done: () -> Unit) {
        if (mutable.value.busy || !mutable.value.storageReady) return
        request?.cancel(); filter?.cancel()
        request = viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, message = null)
            try {
                val valid = source.validated()
                val catalog = client.load(valid)
                ensureActive()
                val next = mutable.value.sources.filterNot { it.id == valid.id } + valid
                // Once an atomic local commit starts, finish it and update memory together.
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { vault.write(next) }
                    prefs.edit().putString("selected", valid.id).apply(); putCache(valid.id, catalog)
                    mutable.value = mutable.value.copy(sources = next, selectedId = valid.id, channels = catalog.channels,
                        query = "", group = null, busy = false)
                    applyFilter(true)
                }
                done()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, message = iptvMessage(e)) }
        }
    }
    fun cancelConnection() {
        request?.cancel(); mutable.value = mutable.value.copy(busy = false, message = null)
        applyFilter(false)
    }
    fun remove(source: IptvSource, done: () -> Unit) {
        if (mutable.value.busy || !mutable.value.storageReady) return
        filter?.cancel()
        request = viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, message = null, filtering = false)
            try {
                val next = mutable.value.sources.filterNot { it.id == source.id }
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { vault.write(next) }
                    cache.remove(source.id)
                    val selected = mutable.value.selectedId?.takeIf { id -> next.any { it.id == id } } ?: next.firstOrNull()?.id
                    prefs.edit().putString("selected", selected).apply()
                    mutable.value = mutable.value.copy(sources = next, selectedId = selected, busy = false,
                        channels = if (source.id == mutable.value.selectedId) emptyList() else mutable.value.channels, group = null, query = "")
                }
                done(); reload()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, message = iptvMessage(e)) }
        }
    }
    suspend fun refreshPlayback(channel: IptvChannel): IptvChannel {
        val source = mutable.value.sources.firstOrNull { it.id == channel.sourceId }
            ?: throw IptvException(IptvException.Reason.NoChannels)
        val result = client.load(source)
        return result.channels.firstOrNull { it.id == channel.id } ?: throw IptvException(IptvException.Reason.NoChannels)
    }
    override fun onCleared() { request?.cancel(); filter?.cancel(); client.close(); super.onCleared() }
    private fun putCache(id: String, catalog: IptvCatalog) {
        cache[id] = android.os.SystemClock.elapsedRealtime() to catalog
        while (cache.size > 3) cache.remove(cache.keys.first())
    }
    private fun applyFilter(moveToFirst: Boolean) {
        filter?.cancel()
        val snapshot = mutable.value
        filter = viewModelScope.launch {
            mutable.value = mutable.value.copy(filtering = true)
            if (!moveToFirst) delay(150)
            val visible = withContext(Dispatchers.Default) {
                val query = snapshot.query.trim()
                snapshot.channels.filterIndexed { index, ch ->
                    if (index % 256 == 0) ensureActive()
                    (snapshot.group == null || ch.group == snapshot.group) && (query.isEmpty() || ch.name.contains(query, true))
                }
            }
            mutable.value = mutable.value.copy(visible = visible, filtering = false,
                focusEpoch = mutable.value.focusEpoch + if (moveToFirst) 1 else 0)
        }
    }
}
