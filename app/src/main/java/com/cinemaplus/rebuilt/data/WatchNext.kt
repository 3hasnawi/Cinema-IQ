package com.cinemaplus.rebuilt.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.cinemaplus.rebuilt.MainActivity

/**
 * Publishes the profile's continue-watching list to the Android TV home screen. The system owns the row;
 * the app only inserts, updates and deletes its own entries. Phones have no such row, so nothing happens there.
 */
object WatchNext {
    const val EXTRA_FILM_ID = "open_film_id"
    const val EXTRA_PROFILE_ID = "open_profile_id"
    private val syncLock = Any()

    fun sync(context: Context, library: Library) = synchronized(syncLock) {
        // View models survive profile changes. Never let an old profile republish its row.
        if(ProfileStore(context).activeId != library.profileId) return@synchronized
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return@synchronized
        try {
            val resolver = context.contentResolver
            val existing = mutableMapOf<String, Long>()
            val duplicates = mutableListOf<Long>()
            resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val program = WatchNextProgram.fromCursor(cursor)
                    val key = program.internalProviderId ?: continue
                    if (existing.containsKey(key)) duplicates += program.id else existing[key] = program.id
                }
            }
            val plan = WatchNextPlan.plan(library.recent(), existing.keys)
            (duplicates + plan.deleteKeys.mapNotNull { existing[it] }).forEach {
                resolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null)
            }
            val now = System.currentTimeMillis()
            plan.upsert.forEachIndexed { index, film ->
                // Newest first: the home screen orders the row by last engagement time.
                val values = build(context, library, film, now - index * 1000L).toContentValues()
                val id = existing[WatchNextPlan.key(film)]
                if (id != null) resolver.update(TvContractCompat.buildWatchNextProgramUri(id), values, null, null)
                else resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
            }
        } catch (_: Exception) {
            // The home screen provider may be missing or refuse access; the app works the same without the row.
        }
    }

    private fun build(context: Context, library: Library, film: Film, engagedAt: Long): WatchNextProgram {
        val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_FILM_ID, film.id)
            .putExtra(EXTRA_PROFILE_ID, library.profileId)
        val art = film.backdrop.ifBlank { film.poster }
        val builder = WatchNextProgram.Builder()
            .setType(if (film.series) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(engagedAt)
            .setTitle(film.title)
            .setDescription(film.description.ifBlank { if (film.series) "الموسم ${film.season} · الحلقة ${film.episode}" else "" })
            .setInternalProviderId(WatchNextPlan.key(film))
            .setIntentUri(Uri.parse(open.toUri(Intent.URI_INTENT_SCHEME)))
        if (film.series && film.episode > 0) builder.setSeasonNumber(film.season).setEpisodeNumber(film.episode)
        if (art.isNotBlank()) builder.setPosterArtUri(Uri.parse(art))
            .setPosterArtAspectRatio(if (film.backdrop.isNotBlank()) TvContractCompat.WatchNextPrograms.ASPECT_RATIO_16_9
                else TvContractCompat.WatchNextPrograms.ASPECT_RATIO_2_3)
        val duration = library.duration(film.id)
        if (duration > 0) builder.setDurationMillis(duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setLastPlaybackPositionMillis(library.progress(film.id).coerceIn(0, duration).toInt())
        return builder.build()
    }
}
