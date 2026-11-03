package com.knhdixh.attendance80.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

private val Context.uiPrefsDataStore by preferencesDataStore(name = "ui_prefs")

/**
 * Choices the website keeps in the browser, kept on the device per account: the Calendar's view and layers, and the exam
 * suggestions dismissed on Countdowns. Keyed by a hash of the account's email, so one account never sees another's.
 */
class UiPrefs(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.uiPrefsDataStore)

    data class Calendar(val view: String, val layers: Map<String, Boolean>)

    private fun owner(email: String) = MessageDigest.getInstance("SHA-256").digest(email.lowercase().toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun calendarKey(email: String) = stringPreferencesKey("calendar.${owner(email)}")
    private fun dismissedKey(email: String) = stringPreferencesKey("dismissed.${owner(email)}")

    fun calendar(email: String): Flow<Calendar?> = store.data.map { prefs ->
        prefs[calendarKey(email)]?.let { raw ->
            val parts = raw.split('|', limit = 2)
            Calendar(parts[0], parts.getOrNull(1).orEmpty().split(',').filter { it.contains(':') }.associate { it.substringBefore(':') to (it.substringAfter(':') == "1") })
        }
    }
    suspend fun saveCalendar(email: String, value: Calendar) {
        store.edit { it[calendarKey(email)] = value.view + "|" + value.layers.entries.joinToString(",") { (k, v) -> "$k:${if (v) 1 else 0}" } }
    }

    fun dismissed(email: String): Flow<List<Long>> = store.data.map { prefs ->
        prefs[dismissedKey(email)].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
    }
    suspend fun saveDismissed(email: String, ids: List<Long>) {
        store.edit { it[dismissedKey(email)] = ids.takeLast(200).joinToString(",") }
    }
}
