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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A finished session waiting to be sent, and the account it was recorded under. Storage only: the POST body is the entry alone. */
@kotlinx.serialization.Serializable data class OutboxItem(val owner: String? = null, val entry: FocusLogEntry)
@kotlinx.serialization.Serializable data class PendingTask(val owner: String? = null, val text: String)

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
    private val outboxV2Key = stringPreferencesKey("outbox_v2")
    private val tasksV2Key = stringPreferencesKey("pending_tasks_v2")
    private val accountKey = stringPreferencesKey("account")
    private val askedKey = booleanPreferencesKey("notifications_asked")
    private val migratedKey = booleanPreferencesKey("twa_migrated")

    suspend fun timer(): FocusTimer = read(timerKey, FocusTimer.serializer()) ?: FocusTimer()
    suspend fun saveTimer(t: FocusTimer) { store.edit { it[timerKey] = json.encodeToString(FocusTimer.serializer(), t) } }

    suspend fun settings(): FocusSettings = read(settingsKey, FocusSettings.serializer()) ?: FocusSettings()
    suspend fun saveSettings(s: FocusSettings) { store.edit { it[settingsKey] = json.encodeToString(FocusSettings.serializer(), s) } }

    /** The account the timer's sessions and tasks belong to: the user's email, set from the snapshot, cleared on sign-out. */
    suspend fun account(): String? = store.data.first()[accountKey]

    /**
     * Sets (or, with null, clears) the signed-in account. Items from the old TWA have no owner (it kept none); the first
     * account to sign in after the migration takes them over, and nobody else ever does.
     */
    suspend fun setAccount(email: String?) {
        store.edit { p ->
            if (email == null) p.remove(accountKey) else p[accountKey] = email.lowercase()
            if (email != null) {
                val owner = email.lowercase()
                saveItems(p, loadItems(p).map { if (it.owner == null) it.copy(owner = owner) else it })
                savePending(p, loadPending(p).map { if (it.owner == null) it.copy(owner = owner) else it })
            }
        }
    }

    /** Finished sessions of [owner] (default: the signed-in account), oldest first. */
    suspend fun outbox(owner: String? = null): List<FocusLogEntry> {
        val who = (owner ?: account())?.lowercase() ?: return emptyList()
        return loadItems(store.data.first()).filter { it.owner == who }.map { it.entry }
    }

    /** Adds finished sessions for [owner], once each (by `client_id`), keeping the newest [MAX_OUTBOX]. The owner is storage only, never sent. */
    suspend fun addLogs(entries: List<FocusLogEntry>, owner: String?) {
        if (entries.isEmpty()) return
        store.edit { p ->
            val now = loadItems(p)
            val known = now.mapTo(HashSet()) { it.entry.client_id }
            val next = (now + entries.filter { known.add(it.client_id) }.map { OutboxItem(owner?.lowercase(), it) }).takeLast(MAX_OUTBOX)
            saveItems(p, next)
        }
    }

    suspend fun removeLogs(ids: Set<String>) {
        if (ids.isEmpty()) return
        store.edit { p -> saveItems(p, loadItems(p).filterNot { it.entry.client_id in ids }) }
    }

    /** Tasks of the signed-in account that the server hasn't been told about yet (none when signed out). */
    suspend fun pendingTasks(): List<String> {
        val who = account() ?: return emptyList()
        return loadPending(store.data.first()).filter { it.owner == who }.map { it.text }
    }

    /** Replaces the signed-in account's pending tasks, leaving every other account's alone. No account: nothing to attribute them to. */
    suspend fun savePendingTasks(tasks: List<String>) {
        store.edit { p ->
            val who = p[accountKey] ?: return@edit
            savePending(p, loadPending(p).filter { it.owner != who } + tasks.map { PendingTask(who, it) })
        }
    }

    /** What the old TWA left behind: no owner until an account signs in. */
    suspend fun addLegacy(logs: List<FocusLogEntry>, tasks: List<String>) {
        store.edit { p ->
            val now = loadItems(p)
            val known = now.mapTo(HashSet()) { it.entry.client_id }
            saveItems(p, (now + logs.filter { known.add(it.client_id) }.map { OutboxItem(null, it) }).takeLast(MAX_OUTBOX))
            val have = loadPending(p)
            savePending(p, tasks.filter { t -> have.none { it.owner == null && it.text.equals(t, true) } }.map { PendingTask(null, it) } + have)
        }
    }

    /** Account deletion: nothing of that account stays on the device. */
    suspend fun purgeAccount(email: String) {
        val who = email.lowercase()
        store.edit { p ->
            saveItems(p, loadItems(p).filter { it.owner != who })
            savePending(p, loadPending(p).filter { it.owner != who })
        }
    }

    /** Tasks typed on the quick-focus sheet or the timer that the server hasn't been told about yet. */
    val pendingTasksFlow: Flow<List<String>> get() = store.data.map { p ->
        val who = p[accountKey]
        if (who == null) emptyList() else loadPending(p).filter { it.owner == who }.map { it.text }
    }

    private fun loadItems(p: Preferences): List<OutboxItem> =
        p[outboxV2Key]?.let { runCatching { json.decodeFromString(items, it) }.getOrNull() }
            // Before owners existed: the same list, with no owner.
            ?: p[outboxKey]?.let { runCatching { json.decodeFromString(logs, it) }.getOrNull() }?.map { OutboxItem(null, it) }
            ?: emptyList()
    private fun saveItems(p: androidx.datastore.preferences.core.MutablePreferences, list: List<OutboxItem>) { p[outboxV2Key] = json.encodeToString(items, list); p.remove(outboxKey) }
    private fun loadPending(p: Preferences): List<PendingTask> =
        p[tasksV2Key]?.let { runCatching { json.decodeFromString(pendingSerializer, it) }.getOrNull() }
            ?: p[tasksKey]?.let { runCatching { json.decodeFromString(ListSerializer(kotlinx.serialization.serializer<String>()), it) }.getOrNull() }?.map { PendingTask(null, it) }
            ?: emptyList()
    private fun savePending(p: androidx.datastore.preferences.core.MutablePreferences, list: List<PendingTask>) { p[tasksV2Key] = json.encodeToString(pendingSerializer, list); p.remove(tasksKey) }

    /** The notification permission is asked in context, the first time a timer starts, and at most once. */
    suspend fun notificationsAsked(): Boolean = store.data.first()[askedKey] == true
    suspend fun markNotificationsAsked() { store.edit { it[askedKey] = true } }

    suspend fun migrated(): Boolean = store.data.first()[migratedKey] == true
    suspend fun markMigrated() { store.edit { it[migratedKey] = true } }

    private suspend fun <T> read(key: Preferences.Key<String>, serializer: kotlinx.serialization.KSerializer<T>): T? =
        store.data.first()[key]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    companion object {
        const val MAX_OUTBOX = 500
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val logs = ListSerializer(FocusLogEntry.serializer())
        private val items = ListSerializer(OutboxItem.serializer())
        private val pendingSerializer = ListSerializer(PendingTask.serializer())
    }
}
