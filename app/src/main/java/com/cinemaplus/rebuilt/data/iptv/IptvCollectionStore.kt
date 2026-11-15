package com.cinemaplus.rebuilt.data.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class IptvCollectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("iptv_channel_collection_v1", Context.MODE_PRIVATE)
    fun read(): IptvChannelCollection = runCatching {
        val raw = prefs.getString("ids", null) ?: return IptvChannelCollection()
        require(raw.length < 2_000_000)
        val json = JSONObject(raw)
        fun ids(key: String, max: Int): List<String> = (json.optJSONArray(key) ?: JSONArray()).let { a ->
            (0 until minOf(a.length(), max)).map { a.optString(it) }.filter { it.isNotBlank() && it.length <= 512 }.distinct()
        }
        IptvChannelCollection(ids("favorites", 2000).toSet(), ids("recent", 100))
    }.getOrDefault(IptvChannelCollection())
    fun write(value: IptvChannelCollection) {
        prefs.edit().putString("ids", JSONObject().put("favorites", JSONArray(value.favorites.toList())).put("recent", JSONArray(value.recent)).toString()).apply()
    }
}
