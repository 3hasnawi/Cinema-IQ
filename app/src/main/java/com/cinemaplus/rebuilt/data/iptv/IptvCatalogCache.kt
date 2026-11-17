package com.cinemaplus.rebuilt.data.iptv

import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class IptvCatalogSnapshot(val savedAt: Long, val catalog: IptvCatalog, val retryAfter: Long = 0) {
    fun fresh(now: Long): Boolean = now >= savedAt && (now - savedAt < 15 * 60_000L || now < retryAfter)
}

interface IptvCatalogCache {
    fun read(source: IptvSource): IptvCatalogSnapshot?
    fun write(source: IptvSource, snapshot: IptvCatalogSnapshot)
    fun remove(sourceId: String)
}

/** Small compressed records avoid reconstructing the provider's large JSON tree on every entry. */
object IptvCatalogCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_EXPANDED = 32 * 1024 * 1024
    fun fingerprint(raw: IptvSource): ByteArray {
        val s = raw.validated()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            listOf(s.id, s.name, s.kind.name, s.address, s.username, s.password, s.allowHttp.toString(), s.output.name).forEach(out::writeUTF)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    }
    fun encode(source: IptvSource, snapshot: IptvCatalogSnapshot): ByteArray {
        require(snapshot.savedAt >= 0 && snapshot.retryAfter >= 0 && snapshot.catalog.channels.size in 1..IptvParsers.MAX_CHANNELS)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(LimitedOutput(GZIPOutputStream(LimitedOutput(bytes, MAX_BYTES)), MAX_EXPANDED)).use { out ->
            out.writeInt(2); out.writeLong(snapshot.savedAt); out.writeLong(snapshot.retryAfter)
            out.writeBoolean(snapshot.catalog.epgUrl != null)
            snapshot.catalog.epgUrl?.let { IptvUrls.validate(it, source.allowHttp); out.writeUTF(it) }
            out.writeInt(snapshot.catalog.channels.size)
            snapshot.catalog.channels.forEach { ch ->
                checkChannel(source, ch)
                out.writeUTF(ch.id); out.writeUTF(ch.name); out.writeUTF(ch.group); out.writeUTF(ch.url)
                out.writeBoolean(ch.logo != null); ch.logo?.let(out::writeUTF); out.writeUTF(ch.tvgId)
            }
        }
        return bytes.toByteArray()
    }
    fun decode(source: IptvSource, bytes: ByteArray): IptvCatalogSnapshot {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(LimitedInput(GZIPInputStream(bytes.inputStream()), MAX_EXPANDED)).use { input ->
            require(input.readInt() == 2)
            val time = input.readLong(); val retryAfter = input.readLong()
            val epg = if (input.readBoolean()) input.readUTF().also { IptvUrls.validate(it, source.allowHttp) } else null
            val count = input.readInt()
            require(time >= 0 && retryAfter >= 0 && count in 1..IptvParsers.MAX_CHANNELS)
            val ids = HashSet<String>()
            val channels = ArrayList<IptvChannel>(count)
            repeat(count) {
                val ch = IptvChannel(input.readUTF(), source.id, input.readUTF(), input.readUTF(), input.readUTF(),
                    if (input.readBoolean()) input.readUTF() else null, input.readUTF())
                checkChannel(source, ch); require(ids.add(ch.id)); channels.add(ch)
            }
            require(input.read() == -1)
            IptvCatalogSnapshot(time, IptvCatalog(channels, epg), retryAfter)
        }
    }
    private fun checkChannel(source: IptvSource, ch: IptvChannel) {
        require(ch.sourceId == source.id && ch.id.startsWith(source.id + ":") && ch.id.length <= 512)
        require(ch.name.isNotBlank() && ch.name.length <= 200 && ch.group.length <= 150 && ch.tvgId.length <= 8192)
        IptvUrls.validate(ch.url, source.allowHttp); ch.logo?.let { IptvUrls.validate(it, source.allowHttp) }
    }
    private class LimitedOutput(out: OutputStream, private val max: Int) : FilterOutputStream(out) {
        private var count = 0L
        override fun write(b: Int) { require(++count <= max); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len; require(count <= max); out.write(b, off, len) }
    }
    private class LimitedInput(input: InputStream, private val max: Int) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = `in`.read().also { if (it >= 0) require(++count <= max) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) { count += it; require(count <= max) } }
    }
}

/** An optional device-local cache. Failure never overwrites the subscription vault. Call on IO. */
class IptvCatalogDiskCache(private val directory: File, private val key: () -> SecretKey) : IptvCatalogCache {
    private val header = "CIQCAT1".toByteArray(Charsets.US_ASCII)
    private val maxEnvelope = IptvCatalogCodec.MAX_BYTES + header.size + 28
    private fun file(id: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it) } + ".enc")
    override fun read(source: IptvSource): IptvCatalogSnapshot? = synchronized(lock) {
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        val file = file(source.id)
        if (!file.exists()) return@synchronized null
        try {
            require(file.length() in (header.size + 28L)..maxEnvelope.toLong())
            val bytes = file.readBytes()
            require(bytes.copyOfRange(0, header.size).contentEquals(header))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(header.size, header.size + 12)))
            cipher.updateAAD(header); cipher.updateAAD(IptvCatalogCodec.fingerprint(source))
            IptvCatalogCodec.decode(source, cipher.doFinal(bytes.copyOfRange(header.size + 12, bytes.size)))
                .also { file.setLastModified(System.currentTimeMillis()) }
        } catch (_: Exception) { file.delete(); null }
    }
    override fun write(source: IptvSource, snapshot: IptvCatalogSnapshot) = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs())
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(header); cipher.updateAAD(IptvCatalogCodec.fingerprint(source))
        val bytes = cipher.doFinal(IptvCatalogCodec.encode(source, snapshot))
        val target = file(source.id); val pending = File(target.path + ".tmp")
        try {
            FileOutputStream(pending).use { out -> out.write(header); out.write(cipher.iv); out.write(bytes); out.fd.sync() }
            check(pending.renameTo(target))
            val files = directory.listFiles()?.filter { it.name.endsWith(".enc") }.orEmpty().toMutableList()
            for (old in files.filter { it != target }.sortedBy { it.lastModified() }) {
                if (files.size <= 3 && files.sumOf { it.length() } <= 3L * maxEnvelope) break
                old.delete(); files.remove(old)
            }
        } finally { pending.delete() }
    }
    override fun remove(sourceId: String) { synchronized(lock) { file(sourceId).delete(); File(file(sourceId).path + ".tmp").delete() } }
    companion object { private val lock = Any() }
}
