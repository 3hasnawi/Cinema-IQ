package com.cinemaplus.rebuilt.data.iptv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Call from Dispatchers.IO. Fail closed on unreadable data; never replace it with an empty subscription list. */
class IptvSourceStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "tv-iptv-sources-v1.enc"))
    private val alias = "cinemaiq.tv.iptv.v1"
    private val header = "CIQIPTV1".toByteArray(Charsets.US_ASCII)
    @Synchronized fun read(): List<IptvSource> { return try {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val envelope = file.openRead().use { it.readBytesBounded(IptvSourceCodec.MAX_BYTES + 64) }
        if (envelope.size < header.size + 12 + 16 || !envelope.copyOfRange(0, header.size).contentEquals(header))
            throw IptvException(IptvException.Reason.Storage)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, envelope.copyOfRange(header.size, header.size + 12)))
        cipher.updateAAD(header)
        IptvSourceCodec.decode(cipher.doFinal(envelope.copyOfRange(header.size + 12, envelope.size)))
    } catch (_: Exception) { throw IptvException(IptvException.Reason.Storage) }
    }
    @Synchronized fun write(sources: List<IptvSource>) {
        val bytes = IptvSourceCodec.encode(sources)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(header)
            val encrypted = header + cipher.iv + cipher.doFinal(bytes)
            val output = file.startWrite()
            try { output.write(encrypted); file.finishWrite(output) }
            catch (e: Exception) { file.failWrite(output); throw e }
        } catch (_: Exception) { throw IptvException(IptvException.Reason.Storage) }
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw IptvException(IptvException.Reason.Storage)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
        while (true) {
            val n = read(chunk); if (n < 0) break
            if (output.size() + n > limit) throw IptvException(IptvException.Reason.Storage)
            output.write(chunk, 0, n)
        }
        return output.toByteArray()
    }
}
