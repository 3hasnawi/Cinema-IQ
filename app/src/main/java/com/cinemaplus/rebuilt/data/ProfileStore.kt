package com.cinemaplus.rebuilt.data

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One viewer of the app. Each profile keeps its own list, history and playback settings. */
data class Profile(val id: String, val name: String, val color: Long, val avatar: String = "avatar-0")

/** Pure list logic, kept apart from storage so it can be unit tested on the JVM. */
object ProfileList {
    const val MAIN = "main"
    const val MAX = 6
    val avatars = (0..5).map { "avatar-$it" }
    private val palette = longArrayOf(0xFFFF8A2B, 0xFFFF5C7A, 0xFF3DD6C0, 0xFF8F7BFF, 0xFFFFB93B, 0xFF4DA3FF)

    fun default() = Profile(MAIN, "الملف الرئيسي", palette[0])

    fun encode(list: List<Profile>): String = JSONArray(list.map {
        JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("avatar", it.avatar)
    }).toString()

    /** Always returns at least the main profile, even when the stored value is missing or damaged. */
    fun decode(raw: String?): List<Profile> = decodeOrNull(raw) ?: listOf(default())

    /** Null when nothing is stored or the text is damaged, so a good backup can be tried first. */
    fun decodeOrNull(raw: String?): List<Profile>? {
        if (raw == null) return null
        val parsed = runCatching {
            val array = JSONArray(raw ?: "[]")
            (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull { o ->
                val id = o.optString("id"); val name = o.optString("name").trim()
                if (id.isBlank() || name.isEmpty()) null else Profile(id, name, o.optLong("color", palette[0]), o.optString("avatar", "avatar-0"))
            }.distinctBy { it.id }
        }.getOrNull() ?: return null
        return if (parsed.any { it.id == MAIN }) parsed else listOf(default()) + parsed
    }

    /** Returns null when the name is blank or the list is full. */
    fun add(list: List<Profile>, name: String, newId: String, avatar: String = "avatar-${list.size % 6}"): List<Profile>? {
        val clean = name.trim().take(16)
        if (clean.isEmpty() || list.size >= MAX || list.any { it.id == newId }) return null
        return list + Profile(newId, clean, palette[list.size % palette.size], avatar)
    }

    /** Editing never changes the profile ID or the preferences file associated with it. */
    fun update(list: List<Profile>, id: String, name: String, avatar: String): List<Profile>? {
        val clean = name.trim().take(16)
        if(clean.isBlank() || list.none { it.id == id } || avatar.isBlank()) return null
        return list.map { if(it.id == id) it.copy(name = clean, avatar = avatar) else it }
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
    private val prefs = appContext.getSharedPreferences(com.cinemaplus.rebuilt.cloud.CloudAccount.prefsName("cinema_profiles"), Context.MODE_PRIVATE)

    /**
     * A damaged list falls back to the last good copy; the damaged text is kept aside and never overwritten
     * silently, so profiles other than the main one are not lost to a single bad write.
     */
    fun profiles(): List<Profile> {
        val raw = prefs.getString("list", null)
        ProfileList.decodeOrNull(raw)?.let { return it }
        if (raw != null) prefs.edit().putString("list_damaged", raw).apply()
        return ProfileList.decodeOrNull(prefs.getString("list_backup", null)) ?: listOf(ProfileList.default())
    }
    private fun save(list: List<Profile>) {
        val json = ProfileList.encode(list)
        val previous = prefs.getString("list", null)?.takeIf { ProfileList.decodeOrNull(it) != null } ?: json
        prefs.edit().putString("list_backup", previous).putString("list", json).apply()
    }
    var activeId: String
        get() = ProfileList.resolveActive(profiles(), prefs.getString("active", null))
        set(value) { prefs.edit().putString("active", value).apply() }

    fun active(): Profile = profiles().first { it.id == activeId }

    fun add(name: String, avatar: String = "avatar-${profiles().size % 6}"): Profile? {
        val id = "p" + System.currentTimeMillis().toString(36)
        val updated = ProfileList.add(profiles(), name, id, avatar) ?: return null
        save(updated)
        return updated.first { it.id == id }
    }

    fun update(id: String, name: String, avatar: String): Profile? {
        val old = profiles().firstOrNull { it.id == id } ?: return null
        val updated = ProfileList.update(profiles(), id, name, avatar) ?: return null
        save(updated)
        if(old.avatar != avatar) discardImportedImage(old.avatar)
        return updated.first { it.id == id }
    }

    /** Copy a bounded square image into private storage; no external URI permission is needed afterwards. */
    suspend fun importAvatar(uri: Uri): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "الصورة غير صالحة" }
        var sample = 1
        while(bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val bitmap = appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("تعذّر قراءة الصورة")
        val orientation = runCatching { appContext.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } }.getOrNull()
        val matrix = Matrix().apply {
            when(orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val square = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val edge = minOf(oriented.width, oriented.height)
        val x = (oriented.width - edge) / 2; val y = (oriented.height - edge) / 2
        Canvas(square).drawBitmap(oriented, Rect(x, y, x + edge, y + edge), Rect(0, 0, 512, 512), Paint(Paint.FILTER_BITMAP_FLAG))
        val directory = File(appContext.filesDir, "profile-images").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.png")
        try {
            target.outputStream().use { check(square.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            target.absolutePath
        } catch(e: Exception) { target.delete(); throw e }
        finally { square.recycle(); if(oriented !== bitmap) oriented.recycle(); bitmap.recycle() }
    }

    fun discardImportedImage(path: String) {
        val image = File(path)
        if(image.parentFile?.canonicalPath == File(appContext.filesDir, "profile-images").canonicalPath) image.delete()
    }

    fun remove(id: String) {
        val old = profiles().firstOrNull { it.id == id }
        val updated = ProfileList.remove(profiles(), id)
        if(id != ProfileList.MAIN) old?.let { discardImportedImage(it.avatar) }
        save(updated)
        if (id != ProfileList.MAIN) {
            // Drop the removed profile's own data; the main profile's file is never touched.
            appContext.deleteSharedPreferences(ProfileList.prefsName(id))
        }
        if (prefs.getString("active", null) == id) prefs.edit().putString("active", ProfileList.MAIN).apply()
    }
}
