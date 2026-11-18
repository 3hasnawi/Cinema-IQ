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

/** Call on IO. New files do not change existing subscription, history or catalogue storage keys. */
internal class IptvCategoryPolicyStore(context: Context) {
    private val record = IptvEncryptedLocalFile(context, "tv-iptv-category-policy-v1.enc", "cinemaiq.tv.iptv.category.v1", 20 * 1024 * 1024)

    fun read(): IptvCategoryPolicy = synchronized(lock) {
        try { record.read()?.let(IptvCategoryPolicyCodec::decode) ?: IptvCategoryPolicy() }
        catch (_: Exception) { throw IptvException(IptvException.Reason.Storage) }
    }

    fun write(value: IptvCategoryPolicy) = synchronized(lock) {
        try { record.write(IptvCategoryPolicyCodec.encode(value)) }
        catch (_: Exception) { throw IptvException(IptvException.Reason.Storage) }
    }

    companion object { private val lock = Any() }
}

/** Atomic, authenticated and excluded from backups. An unreadable record always fails closed. */
internal class IptvEncryptedLocalFile(context: Context, name: String, private val alias: String, private val maxBytes: Int) {
    private val file = AtomicFile(File(context.noBackupFilesDir, name))
    private val header = "CIQPOL1".toByteArray(Charsets.US_ASCII)

    fun read(): ByteArray? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val envelope = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes + 64)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(envelope.size >= header.size + 12 + 16)
        require(envelope.copyOfRange(0, header.size).contentEquals(header))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, envelope.copyOfRange(header.size, header.size + 12)))
        cipher.updateAAD(header)
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(envelope.copyOfRange(header.size + 12, envelope.size)).also { require(it.size <= maxBytes) }
    }

    fun write(bytes: ByteArray) {
        require(bytes.size <= maxBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(header)
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val encrypted = header + cipher.iv + cipher.doFinal(bytes)
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
