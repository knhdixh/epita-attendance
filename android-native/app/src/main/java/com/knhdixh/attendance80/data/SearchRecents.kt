package com.knhdixh.attendance80.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.searchDataStore by preferencesDataStore(name = "search_recents")

/** The last searches typed in the Search tab, newest first. */
class SearchRecents(context: Context) {
    private val store = context.applicationContext.searchDataStore
    private val key = stringPreferencesKey("queries")
    val queries = store.data.map { it[key].orEmpty().split('\n').filter(String::isNotBlank).distinct().take(MAX) }

    suspend fun record(query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.length < 2) return
        store.edit { prefs ->
            val previous = prefs[key].orEmpty().split('\n').filter(String::isNotBlank)
            prefs[key] = (listOf(q) + previous.filter { !it.equals(q, true) }).take(MAX).joinToString("\n")
        }
    }
    suspend fun clear() { store.edit { it.remove(key) } }
    companion object { const val MAX = 8 }
}
