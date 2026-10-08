package com.cinemaplus.rebuilt.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Per-profile storage. The default profile reads the original file, so nothing is lost on upgrade. */
class Library(context: Context, profileId: String = ProfileList.MAIN) {
    private val prefs = context.getSharedPreferences(ProfileList.prefsName(profileId), Context.MODE_PRIVATE)
    var sourceChoice: SourceChoice
        get() = SourceChoice.restore(prefs.getString("content_source", null))
        set(value) { prefs.edit().putString("content_source", value.name).apply() }
    fun favorites(): List<Film> = JsonMapper.films(JSONArray(prefs.getString("favorites", "[]")))
    fun toggle(film: Film) {
        val all = favorites().toMutableList()
        if (!all.removeAll { it.id == film.id }) all.add(film)
        prefs.edit().putString("favorites", JSONArray(all.map(::json)).toString()).apply()
    }
    fun isCompleted(id: String) = prefs.getBoolean("completed_$id", false)

    fun progress(id: String) = prefs.getLong("position_$id", 0L)
    fun beginWatching(film: Film) {
        if (prefs.getBoolean("completed_${film.id}", false)) {
            prefs.edit().putBoolean("completed_${film.id}", false)
                .putLong("position_${film.id}", 0).apply()
        }
    }
    /** Durable checkpoints retain shows even when their last available episode is finished. */
    fun followedSeries(): List<Film> = JsonMapper.films(JSONArray(prefs.getString("followed_series", "[]")))
    fun complete(film: Film, next: Film?, episodeIds: Set<String>) {
        if (prefs.getBoolean("completed_${film.id}", false)) return
        val updated = ContinueWatching.replace(recent(), film, next, episodeIds)
        val edit = prefs.edit().putBoolean("completed_${film.id}", true)
            .putString("recent", JSONArray(updated.map(::json)).toString())
        if (film.seriesId.isNotBlank()) {
            val followed = followedSeries()
            val previous = followed.firstOrNull { it.seriesId == film.seriesId }
            // Rewatching an older episode must not move the checkpoint backwards.
            if (previous == null || ContinueWatching.isLater(film, previous)) {
                edit.putString("followed_series", JSONArray(
                    (listOf(film) + followed.filterNot { it.seriesId == film.seriesId }).map(::json)).toString())
            }
        }
        if (next != null) edit.putLong("position_${next.id}", 0)
            .putLong("duration_${next.id}", 0).putBoolean("completed_${next.id}", false)
        edit.apply()
    }
    /** Revalidate the checkpoint after network I/O before inserting a newly released episode. */
    fun offerNewEpisode(checkpoint: Film, next: Film, playing: Film?): Boolean {
        if (followedSeries().none { it.seriesId == checkpoint.seriesId && it.id == checkpoint.id }) return false
        val recent = recent()
        if (!ContinueWatching.canOffer(checkpoint, next, recent, playing)) return false
        if (prefs.getBoolean("completed_${next.id}", false)) return false
        prefs.edit().putLong("position_${next.id}", 0).putLong("duration_${next.id}", 0)
            .putString("recent", JSONArray((listOf(next) + recent).take(30).map(::json)).toString()).apply()
        return true
    }
    fun saveProgress(film: Film, position: Long, duration: Long) {
        // End-of-playback callbacks must not restore a completed episode.
        if (prefs.getBoolean("completed_${film.id}", false)) return
        prefs.edit().putLong("position_${film.id}", position).putLong("duration_${film.id}", duration).apply()
        val recent = recent().filter { it.id != film.id && (film.seriesId.isBlank() || it.seriesId != film.seriesId) }.toMutableList().apply { add(0, film) }.take(30)
        prefs.edit().putString("recent", JSONArray(recent.map(::json)).toString()).apply()
    }
    fun recent(): List<Film> = JsonMapper.films(JSONArray(prefs.getString("recent", "[]")))
    fun fraction(id: String): Float {
        val d = prefs.getLong("duration_$id", 0)
        return if (d > 0) (progress(id).toFloat() / d).coerceIn(0f, 1f) else 0f
    }
    private fun json(f: Film) = JSONObject().put("nb", f.id).put("ar_title", f.title)
        .put("imgMediumThumbObjUrl", f.poster).put("imgObjUrl", f.backdrop).put("year", f.year).put("stars", f.rating).put("imdbRating", f.imdbRating).put("en_title", f.enTitle).put("trailer", f.trailer)
        .put("originalTitle", f.originalTitle).put("contentQuality", f.contentQuality).put("edition", f.edition).put("seriesId", f.seriesId).put("kind", if (f.series) 2 else 1).put("season", f.season).put("episodeNummer", f.episode)
    var titleLanguage: TitleLanguage
        get() = TitleLanguage.restore(prefs.getString("title_language", null))
        set(value) { prefs.edit().putString("title_language", value.name).apply() }
    var demo: Boolean
        get() = prefs.getBoolean("demo", false)
        set(v) { prefs.edit().putBoolean("demo", v).apply() }
    var quality: String
        get() = prefs.getString("quality", "تلقائي") ?: "تلقائي"
        set(v) { prefs.edit().putString("quality", v).apply() }
    /** Trailer previews start muted unless the user turns sound on in settings. */
    var trailerMuted: Boolean
        get() = prefs.getBoolean("trailer_muted", true)
        set(v) { prefs.edit().putBoolean("trailer_muted", v).apply() }
    /** Whether focused cards expand with a trailer. Off: they still expand, showing the description instead. */
    var trailerPreview: Boolean
        get() = prefs.getBoolean("trailer_preview", true)
        set(v) { prefs.edit().putBoolean("trailer_preview", v).apply() }
    /** Skips scenes flagged by the subtitle analysis (see SceneFilter). */
    var sceneFilter: Boolean
        get() = prefs.getBoolean("scene_filter", false)
        set(v) { prefs.edit().putBoolean("scene_filter", v).apply() }
    var subtitleSize: Float
        get() = prefs.getFloat("subtitle_size", 22f)
        set(v) { prefs.edit().putFloat("subtitle_size", v).apply() }
    var subtitleLanguage: String
        get() = prefs.getString("subtitle_language", "ar") ?: "ar"
        set(v) { prefs.edit().putString("subtitle_language", v).apply() }
    var subtitleBackground: Float
        get() = prefs.getFloat("subtitle_background", .5f)
        set(v) { prefs.edit().putFloat("subtitle_background", v.coerceIn(0f, 1f)).apply() }
    /** Distance of the subtitles from the bottom edge, as a fraction of the video height. */
    var subtitleHeight: Float
        get() = prefs.getFloat("subtitle_height", .08f)
        set(v) { prefs.edit().putFloat("subtitle_height", v.coerceIn(0f, .5f)).apply() }
}
