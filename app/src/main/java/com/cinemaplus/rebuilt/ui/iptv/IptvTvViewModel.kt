package com.cinemaplus.rebuilt.ui.iptv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cinemaplus.rebuilt.data.iptv.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

internal data class IptvState(
    val sources: List<IptvSource> = emptyList(), val selectedId: String? = null,
    val rawChannels: List<IptvChannel> = emptyList(), val channels: List<IptvChannel> = emptyList(), val visible: List<IptvChannel> = emptyList(),
    val epgUrl: String? = null, val query: String = "", val group: String? = null, val busy: Boolean = false,
    val filtering: Boolean = false, val refreshing: Boolean = false, val storageReady: Boolean = false, val storageLoading: Boolean = true,
    val message: String? = null, val focusEpoch: Int = 0, val collection: IptvChannelCollection = IptvChannelCollection(),
    val categoryPolicy: IptvCategoryPolicy = IptvCategoryPolicy(), val categoryAccess: IptvCategoryAccess = IptvCategoryAccess(),
    val pinConfigured: Boolean = false, val securityBusy: Boolean = false
) {
    val selected: IptvSource? get() = sources.firstOrNull { it.id == selectedId }
    val unlockedCategories: Set<String> get() = categoryAccess.unlockedCategories
}

internal class IptvTvViewModel(app: Application) : AndroidViewModel(app) {
    private val vault = IptvSourceStore(app)
    private val prefs = app.getSharedPreferences("tv_iptv_v1", 0)
    private val client = IptvClient()
    private val collectionStore = IptvCollectionStore(app)
    private val categoryStore = IptvCategoryPolicyStore(app)
    private val pinStore = IptvPinStore(app)
    private val catalogs = IptvCatalogRepository(IptvCatalogStore(app), client)
    private val mutable = MutableStateFlow(IptvState())
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var filter: Job? = null
    private var authorizationEpoch = 0L
    init { viewModelScope.launch {
        try {
            val (sources, selected, collection) = withContext(Dispatchers.IO) {
                val sources = vault.read()
                val selected = prefs.getString("selected", null)?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id
                Triple(sources, selected, collectionStore.read())
            }
            val (policy, pinConfigured) = withContext(Dispatchers.IO) { categoryStore.read() to pinStore.hasPin() }
            if (policy.locked.isNotEmpty() && !pinConfigured) throw IptvException(IptvException.Reason.Storage)
            mutable.value = mutable.value.copy(sources = sources, selectedId = selected, collection = collection,
                storageLoading = false, storageReady = true, categoryPolicy = policy, pinConfigured = pinConfigured)
            reload()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.value = mutable.value.copy(storageLoading = false, message = iptvMessage(e)) }
    } }
    fun noteWatched(channel: IptvChannel) {
        if (!canPlay(channel)) return
        if (mutable.value.collection.recent.firstOrNull() == channel.id) return
        val next = mutable.value.collection.watched(channel.id)
        collectionStore.write(next); mutable.value = mutable.value.copy(collection = next)
    }
    fun toggleFavorite(channel: IptvChannel) {
        if (!canPlay(channel)) return
        val next = mutable.value.collection.toggle(channel.id)
        collectionStore.write(next); mutable.value = mutable.value.copy(collection = next)
    }
    fun select(source: IptvSource) {
        if (mutable.value.busy) return
        clearUnlocks()
        prefs.edit().putString("selected", source.id).apply()
        mutable.value = mutable.value.copy(selectedId = source.id, group = null, query = "", rawChannels = emptyList(), channels = emptyList(), visible = emptyList(), epgUrl = null, refreshing = false, categoryAccess = IptvCategoryAccess())
        reload()
    }
    fun returnFromPlayer(group: String?) {
        mutable.value = mutable.value.copy(group = group, query = "")
        applyFilter(false)
    }
    fun query(value: String) { mutable.value = mutable.value.copy(query = value.take(200)); applyFilter(false) }
    fun group(value: String?) { mutable.value = mutable.value.copy(group = value); applyFilter(true) }
    fun reload(force: Boolean = false) {
        request?.cancel(); filter?.cancel()
        val source = mutable.value.selected ?: run { mutable.value = mutable.value.copy(rawChannels = emptyList(), channels = emptyList(), visible = emptyList(), busy = false, refreshing = false, filtering = false); return }
        request = viewModelScope.launch {
            val haveChannels = mutable.value.rawChannels.isNotEmpty()
            mutable.value = mutable.value.copy(busy = !haveChannels, refreshing = haveChannels, message = null, filtering = false)
            try {
                catalogs.load(source, force).collect { update ->
                    ensureActive()
                    if (mutable.value.selected != source) return@collect
                    val first = mutable.value.rawChannels.isEmpty()
                    mutable.value = mutable.value.copy(rawChannels = update.catalog.channels, epgUrl = update.catalog.epgUrl, busy = false, refreshing = update.refreshing)
                    applyFilter(first)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, refreshing = false, message = iptvMessage(e)) }
        }
    }

    fun connect(source: IptvSource, done: () -> Unit) {
        if (mutable.value.busy || !mutable.value.storageReady) return
        request?.cancel(); filter?.cancel()
        request = viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, message = null, filtering = false)
            try {
                val valid = source.validated()
                val catalog = client.load(valid)
                ensureActive()
                val next = mutable.value.sources.filterNot { it.id == valid.id } + valid
                // Once an atomic local commit starts, finish it and update memory together.
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { vault.write(next) }
                    authorizationEpoch++
                    prefs.edit().putString("selected", valid.id).apply(); catalogs.remember(valid, catalog)
                    mutable.value = mutable.value.copy(sources = next, selectedId = valid.id, rawChannels = catalog.channels, epgUrl = catalog.epgUrl,
                        query = "", group = null, busy = false, refreshing = false, categoryAccess = IptvCategoryAccess())
                    applyFilter(true)
                }
                done()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, refreshing = false, message = iptvMessage(e)) }
        }
    }
    fun cancelConnection() {
        request?.cancel(); mutable.value = mutable.value.copy(busy = false, refreshing = false, message = null)
        applyFilter(false)
    }
    fun remove(source: IptvSource, done: () -> Unit) {
        if (mutable.value.busy || !mutable.value.storageReady) return
        request?.cancel(); filter?.cancel()
        request = viewModelScope.launch {
            mutable.value = mutable.value.copy(busy = true, message = null, filtering = false)
            try {
                val next = mutable.value.sources.filterNot { it.id == source.id }
                withContext(NonCancellable) {
                    withContext(Dispatchers.IO) { vault.write(next) }
                    authorizationEpoch++
                    catalogs.forget(source.id)
                    val selected = mutable.value.selectedId?.takeIf { id -> next.any { it.id == id } } ?: next.firstOrNull()?.id
                    prefs.edit().putString("selected", selected).apply()
                    mutable.value = mutable.value.copy(sources = next, selectedId = selected, busy = false, refreshing = false,
                        rawChannels = if (source.id == mutable.value.selectedId) emptyList() else mutable.value.rawChannels,
                        channels = if (source.id == mutable.value.selectedId) emptyList() else mutable.value.channels,
                        group = null, query = "", categoryAccess = IptvCategoryAccess())
                }
                done(); reload()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, refreshing = false, message = iptvMessage(e)) }
        }
    }
    suspend fun refreshPlayback(channel: IptvChannel): IptvChannel {
        if (!canPlay(channel)) throw IptvException(IptvException.Reason.NoChannels)
        val source = mutable.value.sources.firstOrNull { it.id == channel.sourceId }
            ?: throw IptvException(IptvException.Reason.NoChannels)
        val epoch = authorizationEpoch
        fun ensureCurrentSource() {
            if (authorizationEpoch != epoch || mutable.value.selected != source || !canPlay(channel))
                throw CancellationException("IPTV playback source changed")
        }
        request?.cancel()
        mutable.value = mutable.value.copy(refreshing = false)
        val result = client.load(source)
        currentCoroutineContext().ensureActive()
        ensureCurrentSource()
        catalogs.remember(source, result)
        ensureCurrentSource()
        if (mutable.value.selectedId == source.id) {
            mutable.value = mutable.value.copy(rawChannels = result.channels, epgUrl = result.epgUrl)
            applyFilter(false)
        }
        return result.channels.firstOrNull { it.id == channel.id && canPlay(it) } ?: throw IptvException(IptvException.Reason.NoChannels)
    }
    suspend fun programmes(channel: IptvChannel): IptvNowNext {
        if (!canPlay(channel)) return IptvNowNext()
        val source = mutable.value.sources.firstOrNull { it.id == channel.sourceId } ?: return IptvNowNext()
        return client.programmes(source, channel, mutable.value.epgUrl.takeIf { source.id == mutable.value.selectedId })
    }
    override fun onCleared() { request?.cancel(); filter?.cancel(); client.close(); super.onCleared() }
    fun canPlay(channel: IptvChannel): Boolean {
        val snapshot = mutable.value
        if (!snapshot.storageReady || snapshot.selectedId != channel.sourceId) return false
        val canonical = snapshot.rawChannels.firstOrNull { it.id == channel.id && it.sourceId == channel.sourceId } ?: return false
        return snapshot.categoryAccess.canContinue(canonical, snapshot.categoryPolicy)
    }

    fun canStart(channel: IptvChannel): Boolean {
        val snapshot = mutable.value
        if (!snapshot.storageReady || snapshot.selectedId != channel.sourceId) return false
        val canonical = snapshot.rawChannels.firstOrNull { it.id == channel.id && it.sourceId == channel.sourceId } ?: return false
        return snapshot.categoryAccess.canStart(canonical, snapshot.categoryPolicy)
    }

    /** Called before opening a new decoder item, never from a watched/history callback. */
    fun notePlaying(channel: IptvChannel): Boolean {
        val snapshot = mutable.value
        if (!snapshot.storageReady || snapshot.selectedId != channel.sourceId) return false
        val canonical = snapshot.rawChannels.firstOrNull { it.id == channel.id && it.sourceId == channel.sourceId } ?: return false
        if (!snapshot.categoryAccess.canStart(canonical, snapshot.categoryPolicy)) return false
        mutable.value = snapshot.copy(categoryAccess = snapshot.categoryAccess.start(canonical, snapshot.categoryPolicy))
        return true
    }

    fun clearUnlocks() {
        authorizationEpoch++
        if (mutable.value.categoryAccess == IptvCategoryAccess()) return
        mutable.value = mutable.value.copy(categoryAccess = IptvCategoryAccess())
        applyFilter(false)
    }

    /** Leaving a browse visit must not cancel refresh/reconnect for the already-started station. */
    fun leaveCategory() {
        val snapshot = mutable.value
        if (snapshot.categoryAccess.visitKey == null) return
        mutable.value = snapshot.copy(categoryAccess = snapshot.categoryAccess.leaveVisit())
        applyFilter(false)
    }

    fun unlockCategory(group: String) {
        val source = mutable.value.selectedId ?: return
        mutable.value = mutable.value.copy(categoryAccess = mutable.value.categoryAccess.authorizeVisit(IptvCategoryPolicy.key(source, group)))
        applyFilter(false)
    }

    fun changeCategory(group: String, action: String, done: () -> Unit = {}) {
        val snapshot = mutable.value
        if (!snapshot.storageReady || snapshot.securityBusy) return
        val source = snapshot.selectedId ?: return
        val key = IptvCategoryPolicy.key(source, group)
        val next = when (action) {
            "hide" -> snapshot.categoryPolicy.hide(key)
            "restore" -> snapshot.categoryPolicy.restore(key)
            "lock" -> snapshot.categoryPolicy.lock(key)
            "unlock" -> snapshot.categoryPolicy.removeLock(key)
            else -> return
        }
        val epoch = authorizationEpoch
        viewModelScope.launch {
            mutable.value = mutable.value.copy(securityBusy = true, message = null)
            try {
                withContext(Dispatchers.IO) { categoryStore.write(next) }
                mutable.value = mutable.value.copy(categoryPolicy = next,
                    categoryAccess = mutable.value.categoryAccess.revoke(key))
                applyFilter(false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "تعذّر حفظ حماية التصنيف. لم تتغير إعداداتك."); return@launch }
            finally { mutable.value = mutable.value.copy(securityBusy = false) }
            if (authorizationEpoch == epoch) done()
        }
    }

    fun createPin(pin: String, done: () -> Unit) {
        if (!mutable.value.storageReady || mutable.value.securityBusy) return
        if (mutable.value.categoryPolicy.locked.isNotEmpty()) return
        val epoch = authorizationEpoch
        viewModelScope.launch {
            mutable.value = mutable.value.copy(securityBusy = true, message = null)
            try {
                withContext(Dispatchers.IO) { pinStore.setPin(pin) }
                mutable.value = mutable.value.copy(pinConfigured = true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "تعذّر حفظ رمز الحماية. أعد المحاولة."); return@launch }
            finally { mutable.value = mutable.value.copy(securityBusy = false) }
            if (authorizationEpoch == epoch) done()
        }
    }

    fun verifyPin(pin: String, result: (IptvPinResult) -> Unit) {
        if (!mutable.value.storageReady || mutable.value.securityBusy) return
        val epoch = authorizationEpoch
        viewModelScope.launch {
            mutable.value = mutable.value.copy(securityBusy = true, message = null)
            val checked = try { withContext(Dispatchers.IO) { pinStore.verify(pin) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = mutable.value.copy(message = "تعذّر التحقق من رمز الحماية. أعد المحاولة."); return@launch }
            finally { mutable.value = mutable.value.copy(securityBusy = false) }
            if (authorizationEpoch == epoch) result(checked)
        }
    }

    private fun applyFilter(moveToFirst: Boolean) {
        filter?.cancel()
        var snapshot = mutable.value
        val allowed = snapshot.categoryPolicy.available(snapshot.rawChannels, snapshot.unlockedCategories)
        snapshot = snapshot.copy(channels = allowed)
        mutable.value = snapshot
        if (snapshot.query.isBlank() && snapshot.group == null) {
            mutable.value = snapshot.copy(visible = snapshot.channels, filtering = false,
                focusEpoch = snapshot.focusEpoch + if (moveToFirst) 1 else 0)
            return
        }
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
