package com.knhdixh.attendance80.focus

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.logic.FocusTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyFocusMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val dir = Files.createTempDirectory("migration").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val clock = FakeClock()
    private val alarm = FakeAlarm()
    private val outbox = CountingOutbox()
    private val store = FocusStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("focus.preferences_pb") })
    private val controller = FocusController(store, clock, alarm, FakeNotifier(), CountingSurfaces(), outbox) { "new-run" }
    private val migration = LegacyFocusMigration(prefs, store, controller, outbox)
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    // What FocusTimer.toJson() in the TWA wrote.
    private fun runningJson(endsAt: Long = clock.now + 10 * 60_000L) =
        """{"phase":"focus","next":"focus","done":2,"target":{"homework_id":3,"course_id":null,"label":"Essay","tags":["study"]},"updatedAt":5,"run":"old-run-123","startedAt":${endsAt - 25 * 60_000L},"endsAt":$endsAt,"pausedAt":null,"pausedMs":0}"""
    private fun logJson(id: String) = """{"client_id":"$id","started_at":1000,"ended_at":1501000,"planned_seconds":1500,"focus_seconds":1500,"completed":true,"homework_id":null,"course_id":null,"label":"Lab","tags":["a"]}"""

    @Test fun aRunningTimerCarriesOnWithItsAlarmAndTheOldFileIsCleared() = runBlocking {
        prefs.edit().putString("timer", runningJson()).putString("settings", "{\"focus\":50}").commit()
        val r = migration.run()
        assertTrue(r.timer)
        val t = controller.timer.value
        assertEquals("old-run-123", t.run)
        assertEquals(2, t.done)
        assertEquals(3L, t.target?.homework_id)
        assertEquals(listOf("study"), t.target?.tags)
        assertEquals(t.endsAt, alarm.current)
        assertEquals(t, store.timer())
        assertTrue(prefs.all.isEmpty())
        assertTrue(store.migrated())
    }

    @Test fun unsentSessionsMoveToTheOutboxOnceAndABadOneIsSkipped() = runBlocking {
        prefs.edit().putString("logs", "[${logJson("aaaaaaaa-1")},{\"nope\":1},${logJson("bbbbbbbb-2")},${logJson("aaaaaaaa-1")}]").commit()
        val r = migration.run()
        assertEquals(3, r.logs)
        assertEquals(listOf("aaaaaaaa-1", "bbbbbbbb-2"), store.outbox().map { it.client_id })
        assertEquals(1, outbox.flushes)
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun pendingTasksAreKeptForTheAccount() = runBlocking {
        store.savePendingTasks(listOf("Newer"))
        prefs.edit().putString("pendingTasks", "[\"Thesis\",\"thesis\",\"Reading\"]").commit()
        assertEquals(2, migration.run().tasks)
        assertEquals(listOf("Thesis", "Reading", "Newer"), store.pendingTasks())
        assertEquals(1, outbox.flushes)
    }

    @Test fun aTimerRunningHereIsNotReplacedAndRunningTwiceDoesNothing() = runBlocking {
        controller.start()
        val mine = controller.timer.value
        prefs.edit().putString("timer", runningJson()).commit()
        migration.run()
        assertEquals(mine.run, controller.timer.value.run)
        assertTrue(prefs.all.isEmpty())
        prefs.edit().putString("logs", "[${logJson("cccccccc-3")}]").commit()
        assertEquals(0, migration.run().logs)
        assertTrue(store.outbox().isEmpty())
    }

    @Test fun anOldTimerThatEndedWhileUpdatingIsFinishedAndRecorded() = runBlocking {
        prefs.edit().putString("timer", runningJson(endsAt = clock.now - 60 * 60_000L)).commit()
        migration.run()
        assertEquals("idle", controller.timer.value.phase)
        assertEquals("old-run-123", store.outbox().single().client_id)
    }

    @Test fun nothingToMigrateIsFine() = runBlocking {
        assertEquals(LegacyFocusMigration.Result(false, 0, 0), migration.run())
        assertFalse(controller.timer.value.running())
        assertTrue(store.migrated())
        val broken = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
        broken.edit().putString("timer", "{not json").putString("logs", "nope").commit()
        // Already migrated: the junk is left alone and nothing throws.
        assertEquals(0, migration.run().logs)
    }
}
