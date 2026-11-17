package com.cinemaplus.rebuilt.data.iptv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

data class IptvProgramme(val title: String, val startsAt: Long, val endsAt: Long)
data class IptvNowNext(val current: IptvProgramme? = null, val next: IptvProgramme? = null)

object IptvEpgParser {
    fun nowNext(values: List<IptvProgramme>, now: Long) = IptvNowNext(
        values.filter { it.startsAt <= now && it.endsAt > now }.maxByOrNull { it.startsAt },
        values.filter { it.startsAt > now }.minByOrNull { it.startsAt })

    fun xtream(data: ByteArray): List<IptvProgramme> {
        require(data.size <= 2 * 1024 * 1024)
        val rows = JSONObject(data.toString(Charsets.UTF_8)).optJSONArray("epg_listings") ?: return emptyList()
        return (0 until minOf(rows.length(), 100)).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val title = title(row.optString("title"))
            val start = timestamp(row.optString("start_timestamp")) ?: date(row.optString("start"), false)
            val end = timestamp(row.optString("stop_timestamp")) ?: date(row.optString("end").ifBlank { row.optString("stop") }, false)
            if (title.isEmpty() || start == null || end == null || end <= start) null else IptvProgramme(title, start, end)
        }
    }
    private fun title(raw: String): String {
        val plain = raw.trim()
        val decoded = runCatching { plain.decodeBase64()?.utf8() }.getOrNull()
        return (decoded?.takeIf { it.isNotBlank() && '\uFFFD' !in it && it.all { c -> c >= ' ' || c == '\n' } } ?: plain).take(200)
    }
    private fun timestamp(raw: String): Long? = raw.toLongOrNull()?.takeIf { it > 0 }?.let {
        if (it > 100_000_000_000L) it else it * 1000
    }
    private fun date(raw: String, xml: Boolean): Long? = runCatching {
        val value = raw.trim()
        val pattern = if (xml) { if (' ' in value) "yyyyMMddHHmmss Z" else "yyyyMMddHHmmss" } else "yyyy-MM-dd HH:mm:ss"
        SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            if (xml) timeZone = TimeZone.getTimeZone("UTC")
        }.parse(value)?.time
    }.getOrNull()

    /** Streaming XMLTV parser: retain a small current/upcoming index, never a DOM or past schedules. */
    fun xmltv(data: ByteArray, now: Long): Map<String, List<IptvProgramme>> {
        require(data.size <= IptvParsers.MAX_BYTES)
        val result = linkedMapOf<String, MutableList<IptvProgramme>>()
        var retained = 0
        val handler = object : DefaultHandler2() {
            var id = ""; var start: Long? = null; var end: Long? = null
            var readingTitle = false; var arabicTitle = false; val title = StringBuilder()
            override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DTD unsupported") }
            override fun resolveEntity(publicId: String?, systemId: String?) = InputSource("".reader())
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (qName?.ifBlank { localName.orEmpty() }) {
                    "programme" -> {
                        id = attributes.getValue("channel").orEmpty().take(8192)
                        start = date(attributes.getValue("start").orEmpty(), true)
                        end = date(attributes.getValue("stop").orEmpty(), true)
                        title.setLength(0); arabicTitle = false
                    }
                    "title" -> {
                        val arabic = attributes.getValue("lang").orEmpty().startsWith("ar", true)
                        readingTitle = title.isEmpty() || (arabic && !arabicTitle)
                        if (readingTitle && arabic) { title.setLength(0); arabicTitle = true }
                    }
                }
            }
            override fun characters(ch: CharArray, offset: Int, length: Int) {
                if (readingTitle && title.length < 200) title.append(ch, offset, minOf(length, 200 - title.length))
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (qName?.ifBlank { localName.orEmpty() }) {
                    "title" -> readingTitle = false
                    "programme" -> {
                        val from = start; val to = end; val label = title.toString().trim()
                        if (id.isNotBlank() && label.isNotEmpty() && from != null && to != null && to > from && to > now && from < now + 86_400_000) {
                            val entries = result[id] ?: if (result.size < 20_000 && retained < 50_000) mutableListOf<IptvProgramme>().also { result[id] = it } else null
                            if (entries != null && (retained < 50_000 || entries.size >= 6)) {
                                entries.add(IptvProgramme(label, from, to)); entries.sortBy { it.startsAt }; retained++
                                if (entries.size > 6) { entries.removeAt(entries.lastIndex); retained-- }
                            }
                        }
                        id = ""; readingTitle = false
                    }
                }
            }
        }
        val reader = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }.newSAXParser().xmlReader
        reader.contentHandler = handler; reader.entityResolver = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(data.inputStream()))
        return result
    }
}

/** Optional metadata requests never delay tuning. Cancel with the caller; bound caches per client. */
internal class IptvEpgClient(private val transport: IptvTransport, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private val short = linkedMapOf<String, Pair<Long, List<IptvProgramme>>>()
    private var xmlKey: String? = null
    private var xmlAt = 0L
    private var xmlDuration = 600_000L
    private var notBefore = 0L
    private var xml = emptyMap<String, List<IptvProgramme>>()
    suspend fun load(source: IptvSource, channel: IptvChannel, epgUrl: String?): IptvNowNext = withContext(Dispatchers.IO) {
        if (source.id != channel.sourceId) return@withContext IptvNowNext()
        lock.withLock {
            val time = now()
            if (time < notBefore) return@withLock IptvNowNext()
            val fingerprint = IptvCatalogCodec.fingerprint(source).joinToString("") { "%02x".format(it) }
            if (source.kind == IptvKind.Xtream) {
                val id = channel.url.toHttpUrl().pathSegments.last().substringBeforeLast('.')
                if (!id.matches(Regex("[1-9][0-9]{0,18}"))) return@withLock IptvNowNext()
                val key = "$fingerprint:$id"
                val cached = short[key]?.takeIf { time >= it.first && time - it.first < 60_000 }
                val entries = cached?.second ?: (optional {
                    val url = IptvClient.apiUrl(source, "get_short_epg").toHttpUrl().newBuilder()
                        .addQueryParameter("stream_id", id).addQueryParameter("limit", "6").build().toString()
                    IptvEpgParser.xtream(transport.get(url, source.allowHttp))
                } ?: emptyList()).also {
                    short.remove(key); short[key] = time to it
                    while (short.size > 48) short.remove(short.keys.first())
                }
                IptvEpgParser.nowNext(entries, now())
            } else {
                if (epgUrl == null || channel.tvgId.isBlank()) return@withLock IptvNowNext()
                val key = "$fingerprint:$epgUrl"
                if (xmlKey != key || time < xmlAt || time - xmlAt >= xmlDuration) {
                    val entries = optional {
                        IptvEpgParser.xmltv(transport.get(IptvUrls.validate(epgUrl, source.allowHttp).toString(), source.allowHttp), time)
                    }
                    xmlDuration = if (entries == null) 60_000 else 600_000
                    xmlKey = key; xmlAt = time; xml = entries ?: emptyMap()
                }
                IptvEpgParser.nowNext(xml[channel.tvgId].orEmpty(), now())
            }
        }
    }
    private suspend fun <T> optional(action: suspend () -> T): T? = try { withTimeoutOrNull(12_000) { action() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if ((e as? IptvException)?.status == 429) notBefore = now() + 60_000; null }

}
