package com.cinemaplus.rebuilt.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import com.google.firebase.firestore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

/** One active profile subscription; durable outbox, offline SDK cache and server commit ordering. */
class CloudLibrarySync(context: Context, private val profileId: String, private val onRemote: () -> Unit) {
    private val uid = requireNotNull(CloudAccount.uid)
    private val db = FirebaseFirestore.getInstance(CloudAccount.app)
    private val collection = db.collection("users").document(uid).collection("profiles").document(profileId).collection("entries")
    private val prefs = context.getSharedPreferences(CloudAccount.prefsName(com.cinemaplus.rebuilt.data.ProfileList.prefsName(profileId)), Context.MODE_PRIVATE)
    private val outbox = context.getSharedPreferences("cloud_outbox_${uid}_$profileId", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var previous = CloudLibraryRecords.encode(prefs.all)
    private var applying = false
    private var job: Job? = null
    private var registration: ListenerRegistration? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if(!applying) {
            val current = CloudLibraryRecords.encode(prefs.all)
            val changes = CloudLibraryRecords.changes(previous, current)
            previous = current
            if(changes.isNotEmpty()) {
                val edit = outbox.edit(); changes.forEach { (k,v) -> edit.putString(k,v) }; edit.apply()
                schedule(changes.keys.any { !it.startsWith("playback/") })
            }
        }
    }
    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        registration = collection.orderBy("updatedAt", Query.Direction.DESCENDING).addSnapshotListener { snapshot, error ->
            if(error != null) { status = "تعذّرت المزامنة. تحقق من الاتصال وقواعد Firebase."; return@addSnapshotListener }
            if(snapshot == null || (snapshot.metadata.isFromCache && snapshot.isEmpty)) return@addSnapshotListener
            val records = linkedMapOf<String, String>()
            snapshot.documents.forEach { doc ->
                val key = doc.getString("key") ?: return@forEach
                val value = doc.getString("value") ?: return@forEach
                records[key] = value
            }
            // Unsent local edits win until sent. Firestore then orders writes using server timestamps.
            outbox.all.forEach { (key,value) -> if(value is String) records[key] = value }
            runCatching {
                applying = true
                val edit = prefs.edit()
                CloudLibraryRecords.decode(records, prefs.getString("watched", null)).forEach { (k,v) -> when(v) {
                    is String -> edit.putString(k,v); is Long -> edit.putLong(k,v); is Boolean -> edit.putBoolean(k,v)
                } }
                edit.apply(); previous = CloudLibraryRecords.encode(prefs.all)
                onRemote(); status = if(snapshot.metadata.isFromCache) "بيانات محفوظة؛ بانتظار الاتصال" else "المزامنة متصلة"
            }.onFailure { status = "تعذّر قراءة بيانات المزامنة" }
            applying = false
        }
        schedule(true)
    }
    private fun schedule(urgent: Boolean) {
        if(job?.isActive == true) return
        job = scope.launch {
            delay(if(urgent) 1000 else 30000)
            while(isActive && outbox.all.isNotEmpty()) {
                val pending = outbox.all.filterValues { it is String }.mapValues { it.value as String }.entries.take(100)
                try {
                    val batch = db.batch()
                    pending.forEach { (key,value) ->
                        val id = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                        batch.set(collection.document(id), mapOf("key" to key, "value" to value, "updatedAt" to FieldValue.serverTimestamp()))
                    }
                    status = "جارٍ المزامنة…"; batch.commit().await()
                    val edit = outbox.edit()
                    pending.forEach { (key,value) -> if(outbox.getString(key,null) == value) edit.remove(key) }
                    edit.apply(); status = "تمت المزامنة"
                } catch(e: CancellationException) { throw e }
                catch(_: Exception) { status = "التغييرات محفوظة محلياً؛ ستُرسل عند الاتصال"; delay(30000) }
            }
        }
    }
    fun flush() {
        // Cancel the delayed worker before queuing the newest values. SDK writes stay ordered and durable.
        job?.cancel(); job = null
        val chunks = outbox.all.filterValues { it is String }.mapValues { it.value as String }.entries.chunked(100)
        chunks.forEach { pending ->
            val batch = db.batch()
            pending.forEach { (key,value) ->
                val id = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
                batch.set(collection.document(id), mapOf("key" to key, "value" to value, "updatedAt" to FieldValue.serverTimestamp()))
            }
            batch.commit().addOnSuccessListener {
                val edit = outbox.edit()
                pending.forEach { (key,value) -> if(outbox.getString(key,null) == value) edit.remove(key) }
                edit.apply()
            }.addOnFailureListener { status = "التغييرات محفوظة؛ أعد المحاولة عند الاتصال" }
        }
    }
    fun close() {
        flush()
        prefs.unregisterOnSharedPreferenceChangeListener(listener); registration?.remove()
        // Firestore persists submitted writes; unsent edits remain in the per-account outbox.
        scope.cancel()
    }
    companion object { var status by mutableStateOf("لم تبدأ المزامنة"); private set }
}
