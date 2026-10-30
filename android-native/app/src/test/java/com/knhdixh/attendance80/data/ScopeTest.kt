package com.knhdixh.attendance80.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.CalendarDto
import com.knhdixh.attendance80.net.EventDto
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ScopeTest {
    @Test fun persistsAndReconcilesAndKeepsUnattachedEvents() = runBlocking {
        val dir = Files.createTempDirectory("scope").toFile()
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { dir.resolve("scope.preferences_pb") }
        try {
            val scope = Scope(store)
            val calendars = listOf(CalendarDto(1, "file", "A"), CalendarDto(2, "file", "B"))
            scope.toggle("1", calendars)
            assertEquals(listOf(1L), Scope(store).selected.first())
            val raw = javaClass.classLoader!!.getResourceAsStream("api/data.json")!!.bufferedReader().readText()
            val data = ApiClient.json.decodeFromString<AppData>(raw).copy(calendars = calendars, events = listOf(
                EventDto(1, 1, "A", start_at = 0, end_at = 1, cals = listOf(1)),
                EventDto(2, 1, "B", start_at = 0, end_at = 1, cals = listOf(2)),
                EventDto(3, 1, "Shared", start_at = 0, end_at = 1)))
            assertEquals(listOf(1L, 3L), scope.apply(data, scope.selected.first()).events.map { it.id })
            scope.toggle("2", calendars)
            assertTrue(scope.selected.first().isEmpty())
            assertEquals(3, scope.apply(data, scope.selected.first()).events.size)
            scope.toggle("1", calendars)
            scope.reconcile(listOf(calendars[1]))
            assertTrue(scope.selected.first().isEmpty())
        } finally { job.cancel(); dir.deleteRecursively() }
    }
    @Test fun selectionSurvivesDataStoreRestart() = runBlocking {
        val dir = Files.createTempDirectory("scope-restart").toFile()
        val firstJob = SupervisorJob()
        val secondJob = SupervisorJob()
        try {
            val file = dir.resolve("scope.preferences_pb")
            val first = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + firstJob)) { file }
            val calendars = listOf(CalendarDto(1, "file", "A"), CalendarDto(2, "file", "B"))
            Scope(first).toggle("1", calendars)
            firstJob.cancelAndJoin()
            val second = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + secondJob)) { file }
            assertEquals(listOf(1L), Scope(second).selected.first())
        } finally { firstJob.cancel(); secondJob.cancelAndJoin(); dir.deleteRecursively() }
    }
}
