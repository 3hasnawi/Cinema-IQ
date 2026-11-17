package com.cinemaplus.rebuilt.data.iptv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Channel URLs may contain subscription credentials; cache them encrypted outside backups. */
internal class IptvCatalogStore(context: Context) : IptvCatalogCache {
    private val directory = File(context.cacheDir, "tv-iptv-catalog-v1")
    private val disk = IptvCatalogDiskCache(directory) { catalogKey() }
    override fun read(source: IptvSource): IptvCatalogSnapshot? = synchronized(lock) {
        val fingerprint = IptvCatalogCodec.fingerprint(source)
        recent?.takeIf { it.directory == directory.path && it.fingerprint.contentEquals(fingerprint) }?.snapshot
            ?: disk.read(source)?.also { keep(source, fingerprint, it) }
    }
    override fun write(source: IptvSource, snapshot: IptvCatalogSnapshot) = synchronized(lock) {
        try { disk.write(source, snapshot) }
        finally { keep(source, IptvCatalogCodec.fingerprint(source), snapshot) }
    }
    override fun remove(sourceId: String) = synchronized(lock) {
        if (recent?.sourceId == sourceId) recent = null
        disk.remove(sourceId)
    }
    private fun keep(source: IptvSource, fingerprint: ByteArray, snapshot: IptvCatalogSnapshot) {
        // Share one bounded immutable list across activity recreation; large lists stay disk-only.
        recent = if (snapshot.catalog.channels.size in 1..20_000 && snapshot.catalog.channels.all { it.sourceId == source.id })
            Entry(directory.path, source.id, fingerprint, snapshot) else null
    }
    private data class Entry(val directory: String, val sourceId: String, val fingerprint: ByteArray, val snapshot: IptvCatalogSnapshot)
    companion object { private val lock = Any(); private var recent: Entry? = null }
}

private fun catalogKey(): SecretKey {
    val alias = "cinemaiq.tv.iptv.catalog.v1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generateKey()
    }
}
