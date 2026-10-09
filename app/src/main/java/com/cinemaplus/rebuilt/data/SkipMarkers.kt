package com.cinemaplus.rebuilt.data

import org.json.JSONObject

/** Explicit segment boundaries, in milliseconds. Missing or invalid segments remain absent. */
data class SkipMarkers(val introStartMs: Long = 0, val introEndMs: Long = 0, val creditsStartMs: Long = 0) {
    fun withFallback(other: SkipMarkers) = copy(
        introStartMs = if(introEndMs > introStartMs) introStartMs else other.introStartMs,
        introEndMs = if(introEndMs > introStartMs) introEndMs else other.introEndMs,
        creditsStartMs = creditsStartMs.takeIf { it > 0 } ?: other.creditsStartMs)
    companion object {
        fun from(o: JSONObject): SkipMarkers {
            fun normalized(o: JSONObject): Map<String, Any> = o.keys().asSequence().associate { it.lowercase().replace("_", "").replace("-", "") to o.get(it) }
            fun boundary(map: Map<String, Any>, names: List<String>): Long? {
                for(suffix in listOf("ms", "milliseconds", "sec", "seconds", "")) for(name in names) {
                    val value = map[name + suffix] ?: continue
                    return time(value, suffix == "ms" || suffix == "milliseconds")
                }
                return null
            }
            val containers = listOf(o) + listOf("markers", "skipMarkers", "timings").mapNotNull { o.optJSONObject(it) }
            var invalidIntroStart = false
            fun find(types: List<String>, edge: String): Long? {
                for(container in containers) {
                    val root = normalized(container)
                    val flat = types.map { it + edge }
                    if(flat.any { n -> listOf("ms","milliseconds","sec","seconds","").any { root.containsKey(n+it) } }) return boundary(root, flat).also { if(it == null && edge == "start" && "intro" in types) invalidIntroStart = true }
                    for(type in types) {
                        val nested = root[type] as? JSONObject ?: continue
                        val fields = normalized(nested)
                        if(listOf("ms","milliseconds","sec","seconds","").any { fields.containsKey(edge+it) }) return boundary(fields, listOf(edge)).also { if(it == null && edge == "start" && "intro" in types) invalidIntroStart = true }
                    }
                }
                return null
            }
            val start = find(listOf("intro", "opening"), "start")
            val end = find(listOf("intro", "opening"), "end") ?: 0
            val valid = !invalidIntroStart && (start != null && end > start || start == null && end > 0)
            return SkipMarkers(if(valid) start ?: 0 else 0, if(valid) end else 0,
                find(listOf("credits", "outro", "ending"), "start") ?: 0)
        }
        /** Unit comes from the schema/key. Unsuffixed, explicitly whitelisted source fields use seconds. */
        internal fun time(value: Any?, milliseconds: Boolean): Long? {
            val text = value?.toString()?.trim() ?: return null
            val seconds = if(':' in text) {
                val parts = text.split(':').map { it.toDoubleOrNull() ?: return null }
                if(parts.size !in 2..3 || parts.any { !it.isFinite() || it < 0 } || parts.drop(1).any { it >= 60 }) return null
                parts.fold(0.0) { n,p -> n*60+p }
            } else {
                val n = text.toDoubleOrNull() ?: return null
                if(milliseconds) n / 1000 else n
            }
            return (seconds * 1000).takeIf { it.isFinite() && it in 0.0..86_400_000.0 }?.toLong()
        }
    }
}
