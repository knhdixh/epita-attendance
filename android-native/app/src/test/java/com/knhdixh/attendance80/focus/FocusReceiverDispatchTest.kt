package com.knhdixh.attendance80.focus

import android.app.AlarmManager
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.logic.FocusTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** Review 8: the focus receiver re-arms its alarm when the exact-alarm permission is granted, as it does for clock changes. */
class FocusReceiverDispatchTest {
    private val dir = Files.createTempDirectory("focus-dispatch").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val clock = FakeClock()
    private val alarm = FakeAlarm()
    private lateinit var controller: FocusController

    @Before fun setUp() {
        val store = FocusStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("focus.preferences_pb") })
        runBlocking { store.setAccount(ME) }
        controller = FocusController(store, clock, alarm, FakeNotifier(), CountingSurfaces(), CountingOutbox()) { "run-1" }
    }
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    @Test fun grantingExactAlarmsArmsTheRunningTimersAlarmAgain() = runBlocking {
        controller.start(FocusTarget(label = "Essay"))
        val endsAt = controller.timer.value.endsAt
        alarm.schedule(null) // what the system did to the armed alarm
        assertNull(alarm.current)
        FocusReceiver.dispatch(controller, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        assertEquals(endsAt, alarm.current)
    }

    @Test fun clockChangesStillRearmIt() = runBlocking {
        controller.start(FocusTarget(label = "Essay"))
        val endsAt = controller.timer.value.endsAt
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            alarm.schedule(null)
            FocusReceiver.dispatch(controller, action)
            assertEquals(action, endsAt, alarm.current)
        }
    }

    @Test fun aRunningTimerIsNotRestartedByIt() = runBlocking {
        controller.start(FocusTarget(label = "Essay"))
        val before = controller.timer.value
        FocusReceiver.dispatch(controller, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        assertEquals(before, controller.timer.value)
    }
}
