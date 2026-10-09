package com.cinemaplus.rebuilt.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import com.cinemaplus.rebuilt.data.ProfileList
import com.cinemaplus.rebuilt.data.Profile
import com.google.firebase.firestore.*

/** Share stable profile IDs and built-in avatars; imported private image files stay on their device. */
class CloudProfileSync(context: Context) {
    private val prefs = context.getSharedPreferences(CloudAccount.prefsName("cinema_profiles"), Context.MODE_PRIVATE)
    private val db = FirebaseFirestore.getInstance(CloudAccount.app)
    private val collection = db.collection("users")
        .document(requireNotNull(CloudAccount.uid)).collection("profiles")
    private var previous = ProfileList.decode(prefs.getString("list", null))
    private var applying = false
    private var registration: ListenerRegistration? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if(key == "list" && !applying) {
            val next = ProfileList.decode(prefs.getString("list", null))
            val batch = db.batch()
            next.filter { p -> previous.none { it == p } }.forEach { p -> batch.set(collection.document(p.id), fields(p), SetOptions.merge()) }
            previous.filter { p -> next.none { it.id == p.id } && p.id != ProfileList.MAIN }.forEach { p ->
                batch.set(collection.document(p.id), mapOf("deleted" to true), SetOptions.merge())
            }
            previous = next; batch.commit().addOnFailureListener { CloudProfileSync.error = "تعذّرت مزامنة الملفات الشخصية" }
        }
    }
    private fun fields(p: Profile) = mapOf("name" to p.name, "color" to p.color,
        "avatar" to p.avatar.takeIf { it.startsWith("avatar-") }.orEmpty().ifBlank { "avatar-0" }, "deleted" to false)
    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        registration = collection.addSnapshotListener { snapshot, failure ->
            if(failure != null) { error = "تحقق من قواعد Firestore واتصال الإنترنت"; return@addSnapshotListener }
            if(snapshot == null || snapshot.metadata.isFromCache && snapshot.isEmpty) return@addSnapshotListener
            if(snapshot.isEmpty) {
                previous.forEach { collection.document(it.id).set(fields(it)) }
                return@addSnapshotListener
            }
            val profiles = snapshot.documents.filter { it.getBoolean("deleted") != true }.mapNotNull { doc ->
                val name = doc.getString("name") ?: return@mapNotNull null
                Profile(doc.id, name, doc.getLong("color") ?: ProfileList.default().color, previous.firstOrNull { it.id == doc.id }?.avatar?.takeIf { !it.startsWith("avatar-") } ?: doc.getString("avatar") ?: "avatar-0")
            }.sortedBy { if(it.id == ProfileList.MAIN) "" else it.id }.take(ProfileList.MAX)
            if(profiles.isNotEmpty()) {
                applying = true
                prefs.edit().putString("list", ProfileList.encode(profiles)).putString("list_backup", ProfileList.encode(profiles)).apply()
                previous = profiles; applying = false; revision++; error = null
            }
        }
    }
    fun close() { registration?.remove(); prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    companion object {
        var revision by mutableIntStateOf(0); private set
        var error by mutableStateOf<String?>(null); private set
    }
}
