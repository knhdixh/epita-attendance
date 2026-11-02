package com.knhdixh.attendance80.focus

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.content.Context
import com.knhdixh.attendance80.logic.FocusLogEntry
import com.knhdixh.attendance80.logic.FocusSettings
import com.knhdixh.attendance80.logic.FocusTimer
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

val Context.focusDataStore: DataStore<Preferences> by preferencesDataStore(name = "focus")

/**
 * The only copy of the timer on the device, plus what it needs to survive a restart: the last settings (the alarm
 * receiver can run before the snapshot has loaded), finished sessions waiting to be sent, and tasks added offline.
 */
class FocusStore(private val store: DataStore<Preferences>) {
    private val timerKey = stringPreferencesKey("timer")
    private val settingsKey = stringPreferencesKey("settings")
    private val outboxKey = stringPreferencesKey("outbox")
    private val tasksKey = stringPreferencesKey("pending_tasks")
    private val migratedKey = booleanPreferencesKey("twa_migrated")

    suspend fun timer(): FocusTimer = read(timerKey, FocusTimer.serializer()) ?: FocusTimer()
    suspend fun saveTimer(t: FocusTimer) { store.edit { it[timerKey] = json.encodeToString(FocusTimer.serializer(), t) } }

    suspend fun settings(): FocusSettings = read(settingsKey, FocusSettings.serializer()) ?: FocusSettings()
    suspend fun saveSettings(s: FocusSettings) { store.edit { it[settingsKey] = json.encodeToString(FocusSettings.serializer(), s) } }

    suspend fun outbox(): List<FocusLogEntry> = read(outboxKey, logs) ?: emptyList()

    /** Adds finished sessions, once each (by `client_id`), keeping the newest [MAX_OUTBOX]. */
    suspend fun addLogs(entries: List<FocusLogEntry>) {
        if (entries.isEmpty()) return
        store.edit { p ->
            val now = p[outboxKey]?.let { runCatching { json.decodeFromString(logs, it) }.getOrNull() } ?: emptyList()
            val known = now.mapTo(HashSet()) { it.client_id }
            val next = (now + entries.filter { known.add(it.client_id) }).takeLast(MAX_OUTBOX)
            p[outboxKey] = json.encodeToString(logs, next)
        }
    }

    suspend fun removeLogs(ids: Set<String>) {
        if (ids.isEmpty()) return
        store.edit { p ->
            val now = p[outboxKey]?.let { runCatching { json.decodeFromString(logs, it) }.getOrNull() } ?: emptyList()
            p[outboxKey] = json.encodeToString(logs, now.filterNot { it.client_id in ids })
        }
    }

    suspend fun pendingTasks(): List<String> = read(tasksKey, ListSerializer(kotlinx.serialization.serializer<String>())) ?: emptyList()
    suspend fun savePendingTasks(tasks: List<String>) {
        store.edit { it[tasksKey] = json.encodeToString(ListSerializer(kotlinx.serialization.serializer<String>()), tasks) }
    }

    suspend fun migrated(): Boolean = store.data.first()[migratedKey] == true
    suspend fun markMigrated() { store.edit { it[migratedKey] = true } }

    private suspend fun <T> read(key: Preferences.Key<String>, serializer: kotlinx.serialization.KSerializer<T>): T? =
        store.data.first()[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    companion object {
        const val MAX_OUTBOX = 500
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val logs = ListSerializer(FocusLogEntry.serializer())
    }
}
