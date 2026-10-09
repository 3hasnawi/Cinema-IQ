package com.cinemaplus.rebuilt.cloud

import android.content.Context
import com.cinemaplus.rebuilt.data.ProfileList

/** Explicit import merges title records; never copies source credentials or device settings. */
object CloudImport {
    fun importLocal(context: Context) {
        val uid = requireNotNull(CloudAccount.uid)
        val profilePrefs = context.getSharedPreferences("cinema_profiles", Context.MODE_PRIVATE)
        val profiles = ProfileList.decode(profilePrefs.getString("list", null))
        val cloudProfiles = context.getSharedPreferences(CloudAccount.prefsName("cinema_profiles"), Context.MODE_PRIVATE)
        val existing = ProfileList.decode(cloudProfiles.getString("list", null))
        val combined = (existing + profiles.filter { p -> existing.none { it.id == p.id } }).take(ProfileList.MAX)
        cloudProfiles.edit().putString("list", ProfileList.encode(combined)).apply()
        profiles.filter { p -> combined.any { it.id == p.id } }.forEach { profile ->
            val source = context.getSharedPreferences(ProfileList.prefsName(profile.id), Context.MODE_PRIVATE)
            val destination = context.getSharedPreferences(CloudAccount.prefsName(ProfileList.prefsName(profile.id)), Context.MODE_PRIVATE)
            val imported = CloudLibraryRecords.encode(source.all)
            val records = CloudLibraryRecords.encode(destination.all) + imported
            val edit = destination.edit()
            CloudLibraryRecords.decode(records).forEach { (k,v) -> when(v) {
                is String -> edit.putString(k,v); is Long -> edit.putLong(k,v); is Boolean -> edit.putBoolean(k,v)
            } }
            edit.apply()
            val outbox = context.getSharedPreferences("cloud_outbox_${uid}_${profile.id}", Context.MODE_PRIVATE).edit()
            imported.forEach { (k,v) -> outbox.putString(k,v) }; outbox.apply()
            CloudLibrarySync(context, profile.id) {}.close()
        }
    }
}
