package com.cinemaplus.rebuilt.cloud

import org.json.JSONArray
import org.json.JSONObject

/** Per-title records and explicit tombstones avoid replacing another device's whole library. */
object CloudLibraryRecords {
    fun encode(values: Map<String, *>): Map<String, String> = buildMap {
        for((pref, prefix) in listOf("favorites" to "favorite/", "recent" to "recent/", "followed_series" to "follow/")) {
            val list = runCatching { JSONArray(values[pref] as? String ?: "[]") }.getOrDefault(JSONArray())
            for(i in 0 until list.length()) {
                val film = list.optJSONObject(i) ?: continue
                val id = film.optString("nb")
                if(id.isBlank()) continue
                val group = if(pref == "recent" || pref == "followed_series") film.optString("seriesId").ifBlank { id } else id
                put(prefix + group, film.toString())
            }
        }
        val ids = values.keys.mapNotNull { key -> listOf("position_", "duration_", "completed_").firstOrNull { key.startsWith(it) }?.let { key.removePrefix(it) } }.toSet()
        for(id in ids) put("playback/$id", JSONObject().put("position", values["position_$id"] ?: 0L)
            .put("duration", values["duration_$id"] ?: 0L).put("completed", values["completed_$id"] ?: false).toString())
    }
    fun changes(before: Map<String, String>, after: Map<String, String>): Map<String, String> =
        (before.keys + after.keys).mapNotNull { key -> if(before[key] == after[key]) null else key to after[key].orEmpty() }.toMap()

    fun decode(records: Map<String, String>): Map<String, Any> = buildMap {
        for((pref, prefix) in listOf("favorites" to "favorite/", "recent" to "recent/", "followed_series" to "follow/")) {
            put(pref, JSONArray(records.filter { it.key.startsWith(prefix) && it.value.isNotBlank() }.values.map { JSONObject(it) }).toString())
        }
        records.filter { it.key.startsWith("playback/") && it.value.isNotBlank() }.forEach { (key, text) ->
            val id = key.removePrefix("playback/"); val json = JSONObject(text)
            put("position_$id", json.optLong("position").coerceAtLeast(0)); put("duration_$id", json.optLong("duration").coerceAtLeast(0))
            put("completed_$id", json.optBoolean("completed"))
        }
    }
}
