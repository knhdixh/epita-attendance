package com.knhdixh.attendance80.focus

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

// Native graphics: the sheet's top-rounded clip is hit-tested with Path.contains (see CalendarUiTest).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class QuickFocusSheetTest {
    @get:Rule val compose = createComposeRule()
    private val dir = Files.createTempDirectory("quick-focus").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val clock = FakeClock()
    private val alarm = FakeAlarm()
    private val outbox = CountingOutbox()
    private val store = FocusStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("focus.preferences_pb") })
    private val controller = FocusController(store, clock, alarm, FakeNotifier(), CountingSurfaces(), outbox) { "run-1" }
    private val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data.json")!!.readText())
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    @Test fun pickingATargetAndStartingRunsTheTimerThroughTheController() {
        var closed = 0
        val actions = ControllerActions(controller, store, scope, outbox) { closed++ }
        compose.setContent {
            val timer by controller.timer.collectAsState()
            val settings by controller.settings.collectAsState()
            val pending by store.pendingTasksFlow.collectAsState(emptyList())
            Attendance80Theme { QuickFocusSheet(timer, settings, data, pending, { clock.now }, actions, onClose = { closed++ }) }
        }
        compose.onNodeWithTag("timer-target").performClick()
        compose.onNodeWithTag("target-hw:2").performClick()
        compose.waitUntil(5_000) { controller.timer.value.target?.homework_id == 2L }
        compose.onNodeWithTag("length-45").performClick()
        compose.waitUntil(5_000) { controller.timer.value.custom == 45 }
        compose.onNodeWithTag("timer-toggle").performClick()
        compose.waitUntil(5_000) { controller.timer.value.running() && closed == 1 }
        val t = controller.timer.value
        assertEquals(2L, t.target?.homework_id)
        assertEquals(clock.now + 45 * 60_000L, t.endsAt)
        assertEquals(t.endsAt, alarm.current)
    }

    @Test fun aTaskAddedHereIsKeptOnTheDeviceAndSelected() {
        val actions = ControllerActions(controller, store, scope, outbox)
        compose.setContent {
            val timer by controller.timer.collectAsState()
            val settings by controller.settings.collectAsState()
            val pending by store.pendingTasksFlow.collectAsState(emptyList())
            Attendance80Theme { QuickFocusSheet(timer, settings, data, pending, { clock.now }, actions, onClose = {}) }
        }
        compose.onNodeWithTag("timer-target").performClick()
        compose.onNodeWithTag("target-new").performClick()
        compose.onNodeWithTag("timer-new-task").performTextInput("Thesis")
        compose.onNodeWithText("Add").performClick()
        compose.waitUntil(5_000) { controller.timer.value.target?.label == "Thesis" && outbox.flushes == 1 }
        assertEquals(listOf("Thesis"), runBlocking { store.pendingTasks() })
        assertEquals(1, outbox.flushes)
        compose.onNodeWithTag("timer-target").assertTextContains("Working on: Thesis", substring = true)
    }

    @Test fun closeCallsBack() {
        var closed = false
        compose.setContent { Attendance80Theme { QuickFocusSheet(controller.timer.value, controller.settings.value, data, emptyList(), { clock.now }, RecordingActions(), { closed = true }) } }
        compose.onNodeWithTag("quick-focus-close").performClick()
        assertTrue(closed)
    }
}
