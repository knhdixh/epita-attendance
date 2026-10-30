package com.knhdixh.attendance80.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.knhdixh.attendance80.logic.applyScope
import com.knhdixh.attendance80.logic.sanitizeScope
import com.knhdixh.attendance80.logic.toggleScope
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.CalendarDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class Scope(private val store: DataStore<Preferences>) {
    private val key = stringSetPreferencesKey("a80-scope")
    val selected: Flow<List<Long>> = store.data.map { preferences ->
        preferences[key].orEmpty().mapNotNull { it.toLongOrNull() }.sorted()
    }

    suspend fun toggle(value: String, calendars: List<CalendarDto>) {
        store.edit { preferences ->
            val current = preferences[key].orEmpty().mapNotNull { it.toLongOrNull() }
            preferences[key] = toggleScope(value, current, calendars).map { it.toString() }.toSet()
        }
    }

    suspend fun reconcile(calendars: List<CalendarDto>) {
        store.edit { preferences ->
            val current = preferences[key].orEmpty().mapNotNull { it.toLongOrNull() }
            preferences[key] = sanitizeScope(current, calendars).map { it.toString() }.toSet()
        }
    }

    fun apply(data: AppData, ids: List<Long>): AppData =
        data.copy(events = applyScope(data.events, data.calendars, ids))
}
