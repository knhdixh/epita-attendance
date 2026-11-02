package com.knhdixh.attendance80.focus

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review 5, finding 2: the receiver and the tile reach the controller before any activity, so the TWA migration runs
 * from the controller's first read, not from UI start-up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FocusMigrationEntryPointsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val dir = Files.createTempDirectory("migration-entry").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val clock = FakeClock()
    private val min = 60_000L
    private lateinit var store: FocusStore
    private val outbox = CountingOutbox()
    private val migrations = AtomicInteger()

    /** A cold process: a fresh controller on the on-disk store, whose only way to the migration is its own first read. */
    private fun coldController(alarm: FakeAlarm, notifier: FakeNotifier) = FocusController(store, clock, alarm, notifier, CountingSurfaces(), outbox,
        beforeFirstLoad = { migrations.incrementAndGet(); LegacyFocusMigration(prefs, store, outbox).run() }) { "new-run" }

    private fun runningJson(endsAt: Long) =
        """{"phase":"focus","next":"focus","done":2,"target":{"homework_id":3,"course_id":null,"label":"Essay","tags":["study"]},"updatedAt":5,"run":"old-run-123","startedAt":${endsAt - 25 * min},"endsAt":$endsAt,"pausedAt":null,"pausedMs":0}"""

    @Before fun setUp() { store = FocusStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("focus.preferences_pb") }); runBlocking { store.setAccount(ME) } }
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    @Test fun packageReplacedWithARunningLegacyTimerArmsTheAlarmAndShowsTheNotification() = runBlocking {
        val endsAt = clock.now + 10 * min
        prefs.edit().putString("timer", runningJson(endsAt)).commit()
        val alarm = FakeAlarm(); val notifier = FakeNotifier()
        // What FocusReceiver does for MY_PACKAGE_REPLACED, BOOT_COMPLETED and time changes: controller.restore(), nothing else first.
        coldController(alarm, notifier).restore()
        assertEquals(endsAt, alarm.current)
        val shown = notifier.timers.last()
        assertEquals("old-run-123", shown.run); assertTrue(shown.running())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun aTileToggleInAColdProcessAppliesToTheAdoptedTimerNotAFreshOne() = runBlocking {
        prefs.edit().putString("timer", runningJson(clock.now + 10 * min)).commit()
        val controller = coldController(FakeAlarm(), FakeNotifier())
        controller.toggle() // FocusTileService.onClick, before any activity
        val t = controller.timer.value
        assertEquals("old-run-123", t.run) // the TWA's timer, now paused: not a new one started on top of it
        assertTrue(t.paused())
        assertEquals(store.timer(), t)
    }

    @Test fun aTileToggleAfterTheLegacyPhaseEndedFinishesAndLogsItFirst() = runBlocking {
        prefs.edit().putString("timer", runningJson(clock.now - 5 * min)).commit()
        val controller = coldController(FakeAlarm(), FakeNotifier())
        controller.toggle()
        assertEquals(listOf("old-run-123"), store.outbox().map { it.client_id })
    }

    @Test fun twoEntryPointsAtOnceMigrateOnce() = runBlocking {
        prefs.edit().putString("timer", runningJson(clock.now + 10 * min)).putString("logs", "[{\"client_id\":\"aaaaaaaa-1\",\"started_at\":1000,\"ended_at\":1501000,\"planned_seconds\":1500,\"focus_seconds\":1500,\"completed\":true}]").commit()
        val controller = coldController(FakeAlarm(), FakeNotifier())
        listOf(async(Dispatchers.Default) { controller.load() }, async(Dispatchers.Default) { controller.restore() }, async(Dispatchers.Default) { controller.load() }).awaitAll()
        assertEquals(1, migrations.get())
        store.setAccount(ME) // the next snapshot of the signed-in account claims what the TWA left
        assertEquals(listOf("aaaaaaaa-1"), store.outbox().map { it.client_id })
        assertEquals("old-run-123", controller.timer.value.run)
    }
}
