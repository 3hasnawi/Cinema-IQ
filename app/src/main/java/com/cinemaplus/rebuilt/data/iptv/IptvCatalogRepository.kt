package com.cinemaplus.rebuilt.data.iptv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

data class IptvCatalogUpdate(val catalog: IptvCatalog, val refreshing: Boolean)

/** Display saved channels before awaiting a provider, including when its refresh is slow or fails. */
class IptvCatalogRepository(private val cache: IptvCatalogCache, private val client: IptvClient,
    private val now: () -> Long = System::currentTimeMillis) {
    fun load(source: IptvSource, force: Boolean = false) = flow {
        val saved = withContext(Dispatchers.IO) { optional { cache.read(source) } }
        val refresh = force || saved == null || !saved.fresh(now())
        if (saved != null) emit(IptvCatalogUpdate(saved.catalog, refresh))
        if (refresh) {
            val fresh = try { client.load(source) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (saved != null) withContext(Dispatchers.IO) {
                    val wait = if ((e as? IptvException)?.status == 429) 300_000 else 60_000
                    optional { cache.write(source, saved.copy(retryAfter = now() + wait)) }
                }
                throw e
            }
            currentCoroutineContext().ensureActive()
            remember(source, fresh)
            emit(IptvCatalogUpdate(fresh, false))
        }
    }
    suspend fun remember(source: IptvSource, catalog: IptvCatalog) = withContext(Dispatchers.IO) {
        optional { cache.write(source, IptvCatalogSnapshot(now(), catalog)) }; Unit
    }
    suspend fun forget(sourceId: String) = withContext(Dispatchers.IO) { optional { cache.remove(sourceId) }; Unit }
    private inline fun <T> optional(action: () -> T): T? = try { action() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}
