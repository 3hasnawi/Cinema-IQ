package com.cinemaplus.rebuilt.data.iptv

import org.json.JSONArray
import org.json.JSONObject

/** Pure serialization, used only inside the encrypted vault. No existing preference/library keys change. */
object IptvSourceCodec {
    const val MAX_BYTES = 512 * 1024
    fun encode(sources: List<IptvSource>): ByteArray {
        if (sources.size > 20) throw IptvException(IptvException.Reason.SourceLimit)
        if (sources.map { it.id }.distinct().size != sources.size) throw IptvException(IptvException.Reason.Storage)
        val array = JSONArray()
        sources.forEach { raw ->
            val s = raw.validated()
            array.put(JSONObject().put("id", s.id).put("name", s.name).put("kind", s.kind.name)
                .put("address", s.address).put("username", s.username).put("password", s.password)
                .put("allowHttp", s.allowHttp).put("output", s.output.name))
        }
        val bytes = JSONObject().put("schema", 1).put("sources", array).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IptvException(IptvException.Reason.TooLarge)
        return bytes
    }
    fun decode(bytes: ByteArray): List<IptvSource> = try {
        if (bytes.size > MAX_BYTES) throw IptvException(IptvException.Reason.Storage)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        if (root.getInt("schema") != 1) throw IptvException(IptvException.Reason.Storage)
        val items = root.getJSONArray("sources")
        if (items.length() > 20) throw IptvException(IptvException.Reason.Storage)
        val sources = (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            IptvSource(o.getString("id"), o.getString("name"), IptvKind.valueOf(o.getString("kind")),
                o.getString("address"), o.optString("username"), o.optString("password"),
                o.optBoolean("allowHttp", false), IptvOutput.valueOf(o.optString("output", IptvOutput.Hls.name))).validated()
        }
        if (sources.map { it.id }.distinct().size != sources.size) throw IptvException(IptvException.Reason.Storage)
        sources
    } catch (_: Exception) { throw IptvException(IptvException.Reason.Storage) }
}
