package com.knhdixh.attendance80.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.paletteDataStore by preferencesDataStore(name = "palette_recents")

class PaletteRecents(context: Context) {
    private val store = context.applicationContext.paletteDataStore
    private val key = stringPreferencesKey("ids")
    val ids = store.data.map { it[key].orEmpty().split('\n').filter(String::isNotBlank).distinct().take(5) }

    suspend fun record(id: String) {
        store.edit { prefs ->
            val previous = prefs[key].orEmpty().split('\n').filter(String::isNotBlank)
            prefs[key] = (listOf(id) + previous.filter { it != id }).take(5).joinToString("\n")
        }
    }
}
