package com.cinemaplus.rebuilt.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One viewer of the app. Each profile keeps its own list, history and playback settings. */
data class Profile(val id: String, val name: String, val color: Long)

/** Pure list logic, kept apart from storage so it can be unit tested on the JVM. */
object ProfileList {
    const val MAIN = "main"
    const val MAX = 6
    private val palette = longArrayOf(0xFFFF8A2B, 0xFFFF5C7A, 0xFF3DD6C0, 0xFF8F7BFF, 0xFFFFB93B, 0xFF4DA3FF)

    fun default() = Profile(MAIN, "الملف الرئيسي", palette[0])

    fun encode(list: List<Profile>): String = JSONArray(list.map {
        JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)
    }).toString()

    /** Always returns at least the main profile, even when the stored value is missing or damaged. */
    fun decode(raw: String?): List<Profile> {
        val parsed = runCatching {
            val array = JSONArray(raw ?: "[]")
            (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull { o ->
                val id = o.optString("id"); val name = o.optString("name").trim()
                if (id.isBlank() || name.isEmpty()) null else Profile(id, name, o.optLong("color", palette[0]))
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
        return if (parsed.any { it.id == MAIN }) parsed else listOf(default()) + parsed
    }

    /** Returns null when the name is blank or the list is full. */
    fun add(list: List<Profile>, name: String, newId: String): List<Profile>? {
        val clean = name.trim().take(16)
        if (clean.isEmpty() || list.size >= MAX || list.any { it.id == newId }) return null
        return list + Profile(newId, clean, palette[list.size % palette.size])
    }

    /** The main profile owns data from before profiles existed, so it can never be removed. */
    fun remove(list: List<Profile>, id: String): List<Profile> =
        if (id == MAIN) list else list.filterNot { it.id == id }

    /** Falls back to the main profile if the stored active profile no longer exists. */
    fun resolveActive(list: List<Profile>, stored: String?): String =
        list.firstOrNull { it.id == stored }?.id ?: MAIN

    /** The main profile keeps the original preferences file so existing lists and history carry over. */
    fun prefsName(id: String) = if (id == MAIN) "cinema_library" else "cinema_library_$id"
}

class ProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("cinema_profiles", Context.MODE_PRIVATE)

    fun profiles(): List<Profile> = ProfileList.decode(prefs.getString("list", null))
    var activeId: String
        get() = ProfileList.resolveActive(profiles(), prefs.getString("active", null))
        set(value) { prefs.edit().putString("active", value).apply() }

    fun active(): Profile = profiles().first { it.id == activeId }

    fun add(name: String): Profile? {
        val id = "p" + System.currentTimeMillis().toString(36)
        val updated = ProfileList.add(profiles(), name, id) ?: return null
        prefs.edit().putString("list", ProfileList.encode(updated)).apply()
        return updated.first { it.id == id }
    }

    fun remove(id: String) {
        val updated = ProfileList.remove(profiles(), id)
        prefs.edit().putString("list", ProfileList.encode(updated)).apply()
        if (id != ProfileList.MAIN) {
            // Drop the removed profile's own data; the main profile's file is never touched.
            appContext.deleteSharedPreferences(ProfileList.prefsName(id))
        }
        if (prefs.getString("active", null) == id) prefs.edit().putString("active", ProfileList.MAIN).apply()
    }
}
