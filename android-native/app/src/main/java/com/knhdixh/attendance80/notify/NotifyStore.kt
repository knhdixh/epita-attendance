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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A change made from a notification button, waiting for the network. Sent in order; [owner] is the account's email, so another account never sends it. */
@Serializable
data class PendingAction(
    val owner: String, val type: String, val ref: Long, val value: String? = null, val number: Int? = null,
    val tz: String? = null, val at: Long = 0,
)

val Context.notifyDataStore: DataStore<Preferences> by preferencesDataStore(name = "notify")

/**
 * What the reminders remember on the device: the three switches (this device only, unlike the class and habit
 * switches, which are the account's and shared with the website), which reminders were already posted, and the
 * changes from notification buttons that haven't reached the server yet.
 */
class NotifyStore(private val store: DataStore<Preferences>) {
    private val checkinKey = booleanPreferencesKey("checkin")
    private val homeworkKey = booleanPreferencesKey("homework")
    private val examKey = booleanPreferencesKey("exam")
    private val sentKey = stringPreferencesKey("sent")
    private val actionsKey = stringPreferencesKey("actions")

    val choices: Flow<NotifyChoices> = store.data.map { p ->
        NotifyChoices(checkin = p[checkinKey] ?: true, homework = p[homeworkKey], exam = p[examKey] ?: true)
    }

    suspend fun setCheckin(on: Boolean) { store.edit { it[checkinKey] = on } }
    suspend fun setHomework(on: Boolean) { store.edit { it[homeworkKey] = on } }
    suspend fun setExam(on: Boolean) { store.edit { it[examKey] = on } }

    /** Keys of reminders already posted (`<account>|<key>`), forgotten after three days. */
    suspend fun sent(now: Long): Set<String> = loadSent(store.data.first()).filterValues { it > now - KEEP }.keys

    suspend fun markSent(keys: Collection<String>, now: Long) {
        if (keys.isEmpty()) return
        store.edit { p -> p[sentKey] = json.encodeToString(SENT, loadSent(p).filterValues { it > now - KEEP } + keys.associateWith { now }) }
    }

    suspend fun pendingActions(): List<PendingAction> = loadActions(store.data.first())

    suspend fun addAction(action: PendingAction) {
        store.edit { p -> p[actionsKey] = json.encodeToString(ACTIONS, (loadActions(p) + action).takeLast(MAX_ACTIONS)) }
    }

    suspend fun removeActions(done: Collection<PendingAction>) {
        if (done.isEmpty()) return
        store.edit { p -> p[actionsKey] = json.encodeToString(ACTIONS, loadActions(p).filterNot { it in done }) }
    }

    private fun loadSent(p: Preferences): Map<String, Long> = p[sentKey]?.let { runCatching { json.decodeFromString(SENT, it) }.getOrNull() }.orEmpty()
    private fun loadActions(p: Preferences): List<PendingAction> = p[actionsKey]?.let { runCatching { json.decodeFromString(ACTIONS, it) }.getOrNull() }.orEmpty()

    companion object {
        private const val KEEP = 3 * 24 * 3600_000L
        const val MAX_ACTIONS = 200
        private val json = Json { ignoreUnknownKeys = true }
        private val SENT = MapSerializer(String.serializer(), Long.serializer())
        private val ACTIONS = ListSerializer(PendingAction.serializer())
    }
}
