package com.knhdixh.attendance80.focus

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.toLogic
import com.knhdixh.attendance80.logic.FocusSettings
import com.knhdixh.attendance80.logic.FocusTarget
import com.knhdixh.attendance80.logic.FocusTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId

class FakeClock(var now: Long = 1_800_000_000_000L) : Clock {
    override fun now() = now
    override fun zone(): ZoneId = ZoneId.of("UTC")
}

class FakeAlarm : PhaseAlarm {
    val calls = mutableListOf<Long?>()
    override fun schedule(endsAt: Long?) { calls += endsAt }
    val current get() = calls.lastOrNull()
}

class FakeNotifier : FocusNotifier {
    val timers = mutableListOf<FocusTimer>()
    val done = mutableListOf<Triple<String, String?, FocusTimer>>()
    var cleared = 0
    override fun clearDone() { cleared++ }
    override fun showTimer(t: FocusTimer, settings: FocusSettings, now: Long) { timers += t }
    override fun phaseDone(ended: String, label: String?, next: FocusTimer, settings: FocusSettings) { done += Triple(ended, label, next) }
}

class CountingSurfaces : FocusSurfaces { var refreshes = 0; override fun refresh() { refreshes++ } }
class CountingOutbox : OutboxScheduler { var flushes = 0; override fun requestFlush() { flushes++ } }

const val ME = "a@x.test"

class FocusControllerTest {
    private val dir = Files.createTempDirectory("focus-controller").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val clock = FakeClock()
    private val alarm = FakeAlarm()
    private val notifier = FakeNotifier()
    private val surfaces = CountingSurfaces()
    private val outbox = CountingOutbox()
    private var runs = 0
    private lateinit var store: FocusStore
    private lateinit var controller: FocusController

    @Before fun setUp() {
        store = FocusStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("focus.preferences_pb") })
        runBlocking { store.setAccount(ME) } // sessions are recorded for the signed-in account
        controller = FocusController(store, clock, alarm, notifier, surfaces, outbox) { "run-${++runs}" }
    }

    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private val min = 60_000L

    @Test fun startSchedulesTheAlarmAtEndsAtAndStampsUpdatedAt() = runBlocking {
        controller.start(FocusTarget(label = "Essay"))
        val t = controller.timer.value
        assertEquals("focus", t.phase)
        assertEquals(clock.now + 25 * min, t.endsAt)
        assertEquals(t.endsAt, alarm.current)
        assertEquals(clock.now, t.updatedAt)
        assertEquals("run-1", t.run)
        assertEquals("Essay", t.target?.label)
        assertEquals(t, store.timer())
        assertEquals(t, notifier.timers.last())
        assertEquals(1, surfaces.refreshes)
    }

    @Test fun pauseCancelsTheAlarmAndResumeMovesItByTheGap() = runBlocking {
        controller.start()
        val endsAt = controller.timer.value.endsAt
        clock.now += 5 * min
        controller.pause()
        assertNull(alarm.current)
        assertTrue(controller.timer.value.paused())
        clock.now += 10 * min
        controller.resume()
        assertEquals(endsAt + 10 * min, controller.timer.value.endsAt)
        assertEquals(endsAt + 10 * min, alarm.current)
    }

    @Test fun toggleStartsPausesAndResumes() = runBlocking {
        controller.toggle(); assertTrue(controller.timer.value.running())
        controller.toggle(); assertTrue(controller.timer.value.paused())
        controller.toggle(); assertFalse(controller.timer.value.paused())
    }

    @Test fun adjustMovesTheAlarmAndSetLengthChangesTheNextPhase() = runBlocking {
        controller.setLength(40)
        assertEquals(40, controller.timer.value.custom)
        controller.start()
        assertEquals(clock.now + 40 * min, alarm.current)
        controller.adjust(-5)
        assertEquals(clock.now + 35 * min, alarm.current)
        controller.setLength(10)
        assertEquals(clock.now + 10 * min, alarm.current)
    }

    @Test fun finishWithAutoBreaksLogsTheSessionAndStartsABreak() = runBlocking {
        controller.start(FocusTarget(homework_id = 7, label = "Lab"))
        clock.now += 25 * min
        controller.phaseEnded()
        val t = controller.timer.value
        assertEquals("short", t.phase)
        assertEquals(1, t.done)
        assertEquals(clock.now + 5 * min, alarm.current)
        val log = store.outbox().single()
        assertEquals("run-1", log.client_id)
        assertTrue(log.completed)
        assertEquals(25 * 60, log.focus_seconds)
        assertEquals(7L, log.homework_id)
        assertEquals(1, outbox.flushes)
        assertEquals("focus", notifier.done.single().first)
        assertEquals("Lab", notifier.done.single().second)
        assertTrue(notifier.done.single().third.running())
    }

    @Test fun restoreAndSettingsChangesLeavePhaseDoneAlone() = runBlocking {
        controller.start()
        val base = notifier.cleared
        clock.now += 25 * min
        controller.phaseEnded() // the break starts by itself and "phase done" is posted
        assertEquals(base, notifier.cleared)
        val again = FocusController(store, clock, alarm, notifier, surfaces, outbox) { "cold" } // a cold process
        again.restore()
        again.updateSettings(FocusSettings(goal = 3))
        assertEquals(base, notifier.cleared)
    }

    @Test fun commandsFromTheUserClearPhaseDone() = runBlocking {
        controller.start(); assertEquals(1, notifier.cleared)
        controller.pause(); assertEquals(2, notifier.cleared)
        controller.resume(); assertEquals(3, notifier.cleared)
        controller.skip(); assertEquals(4, notifier.cleared)
        controller.stop(); assertEquals(5, notifier.cleared)
        controller.toggle(); assertEquals(6, notifier.cleared)
        controller.focusOn(FocusTarget(label = "x")); assertEquals(7, notifier.cleared)
    }

    @Test fun finishWithoutAutoBreaksGoesIdleAndCancelsTheAlarm() = runBlocking {
        controller.updateSettings(FocusSettings(autoBreaks = false))
        controller.start()
        clock.now += 25 * min
        controller.phaseEnded()
        assertEquals("idle", controller.timer.value.phase)
        assertEquals("short", controller.timer.value.next)
        assertNull(alarm.current)
    }

    @Test fun aStaleFinishDoesNotStartTheNextPhase() = runBlocking {
        controller.start()
        clock.now += 25 * min + 10 * min
        controller.phaseEnded()
        assertEquals("idle", controller.timer.value.phase)
        assertEquals(1, store.outbox().size)
    }

    @Test fun aPhaseEndedAlarmBeforeTheEndOnlyReschedules() = runBlocking {
        controller.start()
        val endsAt = controller.timer.value.endsAt
        clock.now += min
        controller.phaseEnded()
        assertEquals("focus", controller.timer.value.phase)
        assertEquals(endsAt, alarm.current)
        assertTrue(store.outbox().isEmpty())
        assertTrue(notifier.done.isEmpty())
    }

    @Test fun noNotificationWhenTheNotifySettingIsOff() = runBlocking {
        controller.updateSettings(FocusSettings(notify = false))
        controller.start()
        clock.now += 25 * min
        controller.phaseEnded()
        assertTrue(notifier.done.isEmpty())
        assertEquals(1, store.outbox().size)
    }

    @Test fun stopLogsAStoppedSessionAndKeepsTheDoneCount() = runBlocking {
        controller.start()
        clock.now += 10 * min
        controller.stop()
        val log = store.outbox().single()
        assertFalse(log.completed)
        assertEquals(10 * 60, log.focus_seconds)
        assertEquals("idle", controller.timer.value.phase)
        assertNull(alarm.current)
    }

    @Test fun aSessionUnderAMinuteIsNotLogged() = runBlocking {
        controller.start()
        clock.now += 30_000
        controller.stop()
        assertTrue(store.outbox().isEmpty())
    }

    @Test fun skipEndsARunningFocusEarlyAndSkipsAnIdleBreak() = runBlocking {
        controller.updateSettings(FocusSettings(autoBreaks = false))
        controller.start()
        clock.now += 20 * min
        controller.skip()
        assertFalse(store.outbox().single().completed)
        assertEquals("idle", controller.timer.value.phase)
        assertEquals("short", controller.timer.value.next)
        controller.skip()
        assertEquals("focus", controller.timer.value.next)
    }

    @Test fun aRestartKeepsTheRunningTimerAndRestoreRearmsTheAlarm() = runBlocking {
        controller.start()
        val endsAt = controller.timer.value.endsAt
        val again = FocusController(store, clock, FakeAlarm().also { it.calls.clear() }, notifier, surfaces, outbox) { "other" }
        clock.now += 5 * min
        again.restore()
        assertEquals(endsAt, again.timer.value.endsAt)
        assertEquals("run-1", again.timer.value.run)
    }

    @Test fun restoreFinishesAPhaseThatEndedWhileTheDeviceWasOff() = runBlocking {
        controller.start()
        clock.now += 25 * min + 20 * min
        val again = FocusController(store, clock, alarm, notifier, surfaces, outbox) { "later" }
        again.restore()
        assertEquals("idle", again.timer.value.phase)
        assertEquals(1, store.outbox().size)
    }

    @Test fun adoptTakesAnImportedTimerOnlyWhenNothingIsRunning() = runBlocking {
        val imported = FocusTimer(phase = "focus", next = "focus", run = "old", startedAt = clock.now, endsAt = clock.now + 10 * min)
        controller.adopt(imported)
        assertEquals("old", controller.timer.value.run)
        assertEquals(imported.endsAt, alarm.current)
        controller.adopt(imported.copy(run = "second"))
        assertEquals("old", controller.timer.value.run)
    }

    @Test fun settingsSurviveARestart() = runBlocking {
        controller.updateSettings(FocusSettings(focus = 50, goal = 3))
        val again = FocusController(store, clock, alarm, notifier, surfaces, outbox)
        again.load()
        assertEquals(50, again.settings.value.focus)
    }

    @Test fun outboxKeepsOneEntryPerClientId() = runBlocking {
        controller.start(); clock.now += 5 * min; controller.stop()
        val log = store.outbox().single()
        store.addLogs(listOf(log, log.copy(client_id = "x")), ME)
        assertEquals(setOf(log.client_id, "x"), store.outbox().map { it.client_id }.toSet())
        store.removeLogs(setOf("x"))
        assertEquals(1, store.outbox().size)
    }

    // Commands with an intent act on the controller's own state, so a stale button or palette entry cannot do the opposite.
    @Test fun startIfIdleDoesNothingWhileRunningOrPaused() = runBlocking {
        controller.start()
        val running = controller.timer.value
        controller.startIfIdle()
        assertEquals(running, controller.timer.value)
        controller.pause()
        val paused = controller.timer.value
        controller.startIfIdle()
        assertEquals(paused, controller.timer.value)
        assertEquals(1, runs)
    }

    @Test fun startIfIdleStartsWhenIdle() = runBlocking {
        controller.startIfIdle()
        assertTrue(controller.timer.value.running())
    }

    @Test fun pauseIfRunningDoesNothingWhenIdleOrPausedAndPausesWhenRunning() = runBlocking {
        controller.pauseIfRunning()
        assertFalse(controller.timer.value.running())
        controller.start()
        clock.now += 5 * min
        controller.pauseIfRunning()
        assertTrue(controller.timer.value.paused())
        val paused = controller.timer.value
        clock.now += min
        controller.pauseIfRunning() // a second tap on a stale "Pause" does not resume it
        assertEquals(paused, controller.timer.value)
    }

    @Test fun resumeIfPausedDoesNothingWhenIdleOrRunningAndResumesWhenPaused() = runBlocking {
        controller.resumeIfPaused()
        assertFalse(controller.timer.value.running())
        controller.start()
        val running = controller.timer.value
        controller.resumeIfPaused()
        assertEquals(running, controller.timer.value)
        controller.pause()
        controller.resumeIfPaused()
        assertTrue(controller.timer.value.running() && !controller.timer.value.paused())
    }

    @Test fun aPhaseThatEndedIsFinishedBeforeTheCommandIsApplied() = runBlocking {
        controller.start()
        clock.now += 26 * min // ended while nobody looked; the widget still shows "Pause"
        controller.pauseIfRunning()
        // Like toggle(): the ended focus is finished (and counted) first, then the command applies to what follows.
        assertEquals("focus ended and was finished first", 1, controller.timer.value.done)
        assertNotEquals("focus", controller.timer.value.phase)
    }
}

class FocusSettingsMappingTest {
    @Test fun theAccountsSettingsReachTheController() {
        val dto = com.knhdixh.attendance80.net.FocusSettingsDto(focus = 50, short = 10, long = 20, longEvery = 3, goal = 6, autoBreaks = false, autoFocus = true, sound = false, notify = false, tasks = listOf("a"), tags = listOf("b"))
        assertEquals(FocusSettings(50, 10, 20, 3, 6, false, true, false, false, listOf("a"), listOf("b")), dto.toLogic())
    }
}
