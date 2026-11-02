package com.knhdixh.attendance80.widget

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.focus.*
import com.knhdixh.attendance80.logic.FocusSettings
import com.knhdixh.attendance80.logic.FocusTarget
import com.knhdixh.attendance80.logic.FocusTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The controller tells its surfaces the timer after every change, and the widget store keeps it. */
class WidgetTimerWiringTest {
    private val dir = Files.createTempDirectory("widget-wiring").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val clock = FakeClock()
    private val widget = WidgetStore(dir.resolve("widget.json"))
    private val surfaces = object : FocusSurfaces {
        override fun refresh() {}
        override fun timerChanged(timer: FocusTimer, settings: FocusSettings, now: Long) { widget.updateTimer(buildWidgetTimer(timer, settings, now)) }
    }
    private val store = FocusStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("focus.preferences_pb") })
    private val controller = FocusController(store, clock, FakeAlarm(), FakeNotifier(), surfaces, CountingOutbox()) { "run-1" }

    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    @Test fun startPauseResumeStopReachTheWidget() = runBlocking {
        store.setAccount(ME)
        controller.start(FocusTarget(label = "Essay"))
        widget.read().timer.let { assertTrue(it.running); assertFalse(it.paused); assertEquals("Essay", it.label); assertEquals(clock.now + 25 * 60_000, it.endsAt) }
        clock.now += 5 * 60_000
        controller.pause()
        widget.read().timer.let { assertTrue(it.paused); assertEquals(20 * 60_000L, it.leftMs) }
        controller.resume()
        assertFalse(widget.read().timer.paused)
        controller.stop()
        widget.read().timer.let { assertFalse(it.running); assertEquals("focus", it.next) }
    }
}
