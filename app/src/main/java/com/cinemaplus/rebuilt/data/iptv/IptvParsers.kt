package com.cinemaplus.rebuilt.data.iptv

import org.json.JSONArray
import org.json.JSONObject

object IptvParsers {
    const val MAX_BYTES = 20 * 1024 * 1024
    const val MAX_CHANNELS = 50_000
    private fun checked(data: ByteArray): String {
        if (data.size > MAX_BYTES) throw IptvException(IptvException.Reason.TooLarge)
        return data.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    }
    fun playlist(data: ByteArray, source: IptvSource): IptvCatalog {
        val base = IptvUrls.validate(source.address, source.allowHttp)
        val text = checked(data)
        if (text.lineSequence().firstOrNull()?.trim() != "#EXTM3U") throw IptvException(IptvException.Reason.InvalidPlaylist)
        // HLS manifests describe segments/variants, not a list of TV channels.
        if (text.lineSequence().any { it.startsWith("#EXT-X-") }) return IptvCatalog(listOf(
            IptvChannel(IptvUrls.channelId(source.id, "direct-hls"), source.id, source.name, "عام", base.toString())))
        val channels = mutableListOf<IptvChannel>(); val seen = mutableSetOf<String>()
        var title = ""; var group = "عام"; var tvg = ""; var logo: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.length > 16384) { title = ""; continue }
            if (line.startsWith("#EXTINF:", true)) {
                val (metadata, label) = splitInfo(line)
                val attrs = attributes(metadata)
                title = label.ifBlank { attrs["tvg-name"].orEmpty() }.trim().take(200)
                group = attrs["group-title"]?.trim()?.take(150)?.ifBlank { "عام" } ?: "عام"
                tvg = attrs["tvg-id"].orEmpty().take(200)
                logo = attrs["tvg-logo"]?.let { IptvUrls.resolve(it, base, source.allowHttp) }
            } else if (line.isNotEmpty() && !line.startsWith('#')) {
                val url = IptvUrls.resolve(line, base, source.allowHttp)
                if (title.isNotBlank() && url != null) {
                    // Query tokens can expire; public identity must remain stable across refresh/reordering.
                    val path = IptvUrls.validate(url, source.allowHttp).let { it.host + ":" + it.port + it.encodedPath }
                    val id = IptvUrls.channelId(source.id, if (tvg.isNotEmpty()) "tvg:$tvg" else "$title|$group|$path")
                    if (seen.add(id)) channels += IptvChannel(id, source.id, title, group, url, logo, tvg)
                    if (channels.size > MAX_CHANNELS) throw IptvException(IptvException.Reason.TooLarge)
                }
                title = ""; group = "عام"; tvg = ""; logo = null
            }
        }
        if (channels.isEmpty()) throw IptvException(IptvException.Reason.NoChannels)
        return IptvCatalog(channels)
    }
    private fun splitInfo(line: String): Pair<String, String> {
        var quoted = false; var escaped = false
        line.forEachIndexed { i, c ->
            if (escaped) escaped = false
            else if (quoted && c == '\\') escaped = true
            else if (c == '"') quoted = !quoted
            else if (c == ',' && !quoted) return line.substring(0, i) to line.substring(i + 1)
        }
        return line to ""
    }
    /** Linear scanner: malformed or escaped quotes must not cause regex backtracking on large catalogues. */
    private fun attributes(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>(); var i = 0
        while (i < text.length) {
            if (!(text[i].isLetterOrDigit() || text[i] == '-')) { i++; continue }
            val start = i
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '-')) i++
            val key = text.substring(start, i).lowercase()
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '=') continue
            i++; while (i < text.length && text[i].isWhitespace()) i++
            val value = StringBuilder()
            if (i < text.length && text[i] == '"') {
                i++
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\' && i + 1 < text.length) i++
                    value.append(text[i++])
                }
                if (i < text.length) i++
            } else while (i < text.length && !text[i].isWhitespace()) value.append(text[i++])
            result[key] = value.toString()
        }
        return result
    }
    fun authentication(data: ByteArray, nowSeconds: Long = System.currentTimeMillis() / 1000) {
        val info = jsonObject(data).optJSONObject("user_info") ?: throw IptvException(IptvException.Reason.InvalidResponse)
        if (info.optString("auth") !in listOf("1", "true")) throw IptvException(IptvException.Reason.Credentials)
        if (!info.optString("status").equals("Active", true)) throw IptvException(IptvException.Reason.Inactive)
        val expires = info.optString("exp_date").toLongOrNull()
        if (expires != null && expires > 0 && expires <= nowSeconds) throw IptvException(IptvException.Reason.Inactive)
    }
    fun xtream(data: ByteArray, categories: ByteArray?, source: IptvSource): IptvCatalog {
        val groups = mutableMapOf<String, String>()
        if (categories != null) runCatching {
            val list = jsonArray(categories)
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { groups[it.optString("category_id")] = it.optString("category_name").take(150) }
        }
        val list = jsonArray(data)
        if (list.length() > MAX_CHANNELS) throw IptvException(IptvException.Reason.TooLarge)
        val base = IptvUrls.validate(source.address, source.allowHttp)
        val seen = mutableSetOf<String>(); val channels = mutableListOf<IptvChannel>()
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            val id = obj.optString("stream_id")
            val name = obj.optString("name").trim().take(200)
            if (!id.matches(Regex("[1-9][0-9]{0,18}")) || name.isEmpty() || !seen.add(id)) continue
            val url = IptvClient.liveUrl(source, id)
            val logo = obj.optString("stream_icon").let { IptvUrls.resolve(it, base, source.allowHttp) }
            channels += IptvChannel(IptvUrls.channelId(source.id, "xtream:$id"), source.id, name,
                groups[obj.optString("category_id")]?.ifBlank { "عام" } ?: "عام", url, logo, obj.optString("epg_channel_id"))
        }
        if (channels.isEmpty()) throw IptvException(IptvException.Reason.NoChannels)
        return IptvCatalog(channels)
    }
    private fun jsonObject(data: ByteArray): JSONObject = try { JSONObject(checked(data)) }
        catch (e: IptvException) { throw e } catch (_: Exception) { throw IptvException(IptvException.Reason.InvalidResponse) }
    private fun jsonArray(data: ByteArray): JSONArray = try { JSONArray(checked(data)) }
        catch (e: IptvException) { throw e } catch (_: Exception) { throw IptvException(IptvException.Reason.InvalidResponse) }
}
