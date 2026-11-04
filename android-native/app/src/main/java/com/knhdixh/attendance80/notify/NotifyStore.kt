package com.knhdixh.attendance80.notify

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

val Context.notifyDataStore: DataStore<Preferences> by preferencesDataStore(name = "notify")

/**
 * What the reminders remember on the device: the three switches (this device only, unlike the class and habit
 * switches, which are the account's and shared with the website), which reminders were already posted, and the
 * (Changes from notification buttons go through the outbox, like every other change.)
 */
class NotifyStore(private val store: DataStore<Preferences>) {
    private val checkinKey = booleanPreferencesKey("checkin")
    private val homeworkKey = booleanPreferencesKey("homework")
    private val examKey = booleanPreferencesKey("exam")
    private val sentKey = stringPreferencesKey("sent")
    private val actionsKey = stringPreferencesKey("actions")
    private val tasksKey = booleanPreferencesKey("tasks")
    private val digestKey = stringPreferencesKey("digest_at")
    private val snoozeKey = stringPreferencesKey("snoozes_due")

    val choices: Flow<NotifyChoices> = store.data.map { p ->
        NotifyChoices(checkin = p[checkinKey] ?: true, homework = p[homeworkKey], exam = p[examKey] ?: true, tasks = p[tasksKey] ?: true, digestAt = p[digestKey])
    }

    suspend fun setCheckin(on: Boolean) { store.edit { it[checkinKey] = on } }
    suspend fun setHomework(on: Boolean) { store.edit { it[homeworkKey] = on } }
    suspend fun setExam(on: Boolean) { store.edit { it[examKey] = on } }
    suspend fun setTasks(on: Boolean) { store.edit { it[tasksKey] = on } }
    /** The digest's time (`HH:mm`), or null to turn it off. */
    suspend fun setDigest(at: String?) { store.edit { if (at == null) it.remove(digestKey) else it[digestKey] = at } }

    /** Snoozed task reminders for [owner]: task id to the snooze. Ones that ended long ago are forgotten. */
    suspend fun snoozes(owner: String, now: Long): Map<Long, TaskSnooze> =
        loadSnoozes(store.data.first()).mapNotNull { (k, v) ->
            val until = v.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            if (until <= now - KEEP || k.substringBefore('|') != owner) return@mapNotNull null
            k.substringAfter('|').toLong() to TaskSnooze(until, v.substringAfter('|'))
        }.toMap()

    suspend fun snooze(owner: String, taskId: Long, snooze: TaskSnooze) {
        store.edit { p -> p[snoozeKey] = json.encodeToString(TEXT, loadSnoozes(p) + ("$owner|$taskId" to "${snooze.until}|${snooze.due}")) }
    }

    private fun loadSnoozes(p: Preferences): Map<String, String> = p[snoozeKey]?.let { runCatching { json.decodeFromString(TEXT, it) }.getOrNull() }.orEmpty()

    /** Keys of reminders already posted (`<account>|<key>`), forgotten after three days. */
    suspend fun sent(now: Long): Set<String> = loadSent(store.data.first()).filterValues { it > now - KEEP }.keys

    suspend fun markSent(keys: Collection<String>, now: Long) {
        if (keys.isEmpty()) return
        store.edit { p -> p[sentKey] = json.encodeToString(SENT, loadSent(p).filterValues { it > now - KEEP } + keys.associateWith { now }) }
    }

    /** Sign-out: the account's posted-reminder keys go (and any button changes an older build left here; they are in the outbox now). */
    suspend fun signedOut() { store.edit { it.remove(sentKey); it.remove(actionsKey); it.remove(snoozeKey) } }

    private fun loadSent(p: Preferences, key: androidx.datastore.preferences.core.Preferences.Key<String> = sentKey): Map<String, Long> = p[key]?.let { runCatching { json.decodeFromString(SENT, it) }.getOrNull() }.orEmpty()

    companion object {
        private const val KEEP = 3 * 24 * 3600_000L
        private val json = Json { ignoreUnknownKeys = true }
        private val TEXT = MapSerializer(String.serializer(), String.serializer())
        private val SENT = MapSerializer(String.serializer(), Long.serializer())
    }
}
