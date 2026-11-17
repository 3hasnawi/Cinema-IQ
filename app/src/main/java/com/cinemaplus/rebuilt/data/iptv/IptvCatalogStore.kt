package com.cinemaplus.rebuilt.data.iptv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Channel URLs may contain subscription credentials; cache them encrypted outside backups. */
internal class IptvCatalogStore(context: Context) : IptvCatalogCache by IptvCatalogDiskCache(
    File(context.cacheDir, "tv-iptv-catalog-v1"), { catalogKey() })

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
