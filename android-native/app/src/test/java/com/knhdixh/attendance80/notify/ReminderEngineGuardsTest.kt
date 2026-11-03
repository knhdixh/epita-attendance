package com.knhdixh.attendance80.notify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** The review-6 pattern in the engine: a null snapshot is no sign-out, and a cancelled run still records what it posted. */
class ReminderEngineGuardsTest {
    private val dir = Files.createTempDirectory("notify").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var store: NotifyStore
    private val alarm = FakeAlarm()
    private val notifier = FakeNotifier()
    private val now = at("2026-11-02T10:05:00")

    @Before fun setUp() { store = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("notify.preferences_pb") }) }
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private val data = emptyData(courses = listOf(course(1, "Mathématiques")), events = listOf(event(10, 1, "2026-11-02T08:00:00", "2026-11-02T10:00:00")))

    @Test fun aNullSnapshotWhileTheTokenIsPresentLeavesTheAlarmAlone() = runBlocking {
        val engine = ReminderEngine(testClock(now), store, alarm, notifier)
        engine.run(data)
        val armed = alarm.at
        val calls = alarm.calls
        engine.onSnapshot(null) { false } // the cache is not loaded yet; the token store says we are signed in
        assertEquals(armed, alarm.at)
        assertEquals(calls, alarm.calls)
    }

    @Test fun aNullSnapshotIsSignOutOnlyOnceTheTokenStoreSaysSo() = runBlocking {
        val engine = ReminderEngine(testClock(now), store, alarm, notifier)
        engine.run(data)
        val asked = CompletableDeferred<Unit>()
        engine.onSnapshot(null) { asked.complete(Unit); true }
        assertTrue(asked.isCompleted)
        assertNull(alarm.at)
    }

    @Test fun aSnapshotThatLoadsLateIsPlannedNormally() = runBlocking {
        val engine = ReminderEngine(testClock(now), store, alarm, notifier)
        engine.onSnapshot(null) { false }
        engine.onSnapshot(data) { false }
        assertEquals(1, notifier.posted.size)
        assertNotNull(alarm.at)
    }

    @Test fun aRunCancelledAfterPostingStillRecordsItSoTheNextRunDoesNotAlertAgain() = runBlocking {
        lateinit var job: kotlinx.coroutines.Job
        val cancelling = object : ReminderNotifier by notifier {
            override fun post(reminder: Reminder, owner: String) { notifier.post(reminder, owner); job.cancel() }
        }
        val engine = ReminderEngine(testClock(now), store, alarm, cancelling)
        job = launch(Dispatchers.Default) { engine.run(data) }
        job.join()
        assertEquals(1, notifier.posted.size)
        ReminderEngine(testClock(now), store, alarm, notifier).run(data)
        assertEquals("a second run must not post it again", 1, notifier.posted.size)
    }
}
