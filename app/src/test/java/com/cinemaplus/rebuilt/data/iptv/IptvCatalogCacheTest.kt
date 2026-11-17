package com.cinemaplus.rebuilt.data.iptv

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import javax.crypto.KeyGenerator

class IptvCatalogCacheTest {
    private val source = IptvSource("one", "اشتراكي", IptvKind.Xtream, "https://provider.example", "fixture-user", "fixture-password")
    private fun snapshot(s: IptvSource = source, count: Int = 2) = IptvCatalogSnapshot(1_000,
        IptvCatalog(List(count) { i -> IptvChannel("${s.id}:$i", s.id, "الأخبار $i", "أخبار", "https://media.example/$i.m3u8?token=fixture-secret", "https://media.example/logo.png", "epg-$i") }))
    private fun key() = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()

    @Test fun compressedCodecPreservesLargeArabicCatalogueAndChannelOrder() {
        val expected = snapshot(count = 5_000)
        val bytes = IptvCatalogCodec.encode(source, expected)
        assertEquals(expected, IptvCatalogCodec.decode(source, bytes))
        assertTrue(bytes.size < 1_000_000)
    }
    @Test fun newCacheInstanceRestoresEncryptedFilesWithoutTheOldViewModel() {
        val directory = Files.createTempDirectory("iptv-cache").toFile()
        try {
            val key = key()
            IptvCatalogDiskCache(directory) { key }.write(source, snapshot())
            assertEquals(snapshot(), IptvCatalogDiskCache(directory) { key }.read(source))
            val raw = directory.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("fixture-secret")); assertFalse(raw.contains("fixture-password"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun changedServerCredentialsOutputOrHttpConsentCannotReuseOldSnapshot() {
        val directory = Files.createTempDirectory("iptv-cache").toFile()
        try {
            val key = key(); val cache = IptvCatalogDiskCache(directory) { key }
            for (changed in listOf(source.copy(password = "different"), source.copy(address = "https://other.example"),
                source.copy(output = IptvOutput.TransportStream), source.copy(allowHttp = true))) {
                cache.write(source, snapshot())
                assertNull(cache.read(changed))
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun malformedOrOversizedCataloguesCannotReplaceASavedSnapshot() {
        val directory = Files.createTempDirectory("iptv-cache").toFile()
        try {
            val key = key(); val cache = IptvCatalogDiskCache(directory) { key }
            cache.write(source, snapshot())
            for (invalid in listOf(IptvCatalog(emptyList()), IptvCatalog(listOf(snapshot().catalog.channels[0].copy(sourceId = "foreign"))),
                IptvCatalog(java.util.Collections.nCopies(IptvParsers.MAX_CHANNELS + 1, snapshot().catalog.channels[0])))) {
                try { cache.write(source, IptvCatalogSnapshot(2_000, invalid)); fail("Invalid catalogue accepted") }
                catch (_: IllegalArgumentException) { }
                assertEquals(snapshot(), cache.read(source))
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun corruptionAndWrongEncryptionKeyAreCacheMisses() {
        val directory = Files.createTempDirectory("iptv-cache").toFile()
        try {
            val original = key(); val other = key(); val cache = IptvCatalogDiskCache(directory) { original }
            cache.write(source, snapshot())
            assertNull(IptvCatalogDiskCache(directory) { other }.read(source))
            cache.write(source, snapshot())
            directory.listFiles()!!.single().writeBytes(byteArrayOf(1, 2, 3))
            assertNull(cache.read(source))
        } finally { directory.deleteRecursively() }
    }
    @Test fun removalAndEvictionAffectOnlyCachedCatalogues() {
        val directory = Files.createTempDirectory("iptv-cache").toFile()
        try {
            val key = key(); val cache = IptvCatalogDiskCache(directory) { key }
            for (i in 1..4) { val s = source.copy(id = "$i"); cache.write(s, snapshot(s)) }
            assertEquals(3, directory.listFiles()!!.count { it.name.endsWith(".enc") })
            assertNotNull(cache.read(source.copy(id = "4")))
            cache.remove("4"); assertNull(cache.read(source.copy(id = "4")))
        } finally { directory.deleteRecursively() }
    }
    @Test fun clockRollbackAndStaleSnapshotsRequireBackgroundRefresh() {
        assertTrue(snapshot().fresh(1_001))
        assertFalse(snapshot().fresh(999))
        assertFalse(snapshot().fresh(1_000 + 15 * 60_000))
    }
}
