package com.knhdixh.attendance80.focus

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.data.FocusHistoryStatus
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.ui.focus.FocusScreen
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.shell.FocusTimerState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class FocusScreenTimerUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_800_000_000_000L
    private val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data.json")!!.readText()).let { it.copy(focus = it.focus.copy(since = 0)) }
    private val actions = RecordingActions()
    private var pending by mutableStateOf<PaletteAction.Focus?>(null)
    private var consumed = 0
    private val running = FocusTimer(phase = "focus", next = "focus", run = "r", startedAt = now, endsAt = now + 25 * 60_000L, target = FocusTarget(label = "Essay", tags = listOf("study")))

    private fun show(timer: FocusTimer) = compose.setContent {
        Attendance80Theme {
            FocusScreen(data, "a", now, ZoneId.of("UTC"), FocusHistoryStatus(), rememberLazyListState(), null, {}, {}, { false }, { false }, {}, pending, {},
                { null }, FocusTimerState(timer, data.focus.settings.let { FocusSettings(tags = listOf("study")) }, emptyList()), actions, { consumed++; pending = null }, { now })
        }
    }

    @Test fun theTimerIsAtTheTopOfTheScreen() {
        show(idle())
        compose.onNodeWithTag("timer-panel").assertExists()
        compose.onNodeWithTag("timer-toggle").assertTextEquals("Start focus").performClick()
        assertEquals(listOf("toggle"), actions.calls)
        compose.onNodeWithText("Timer controls are coming in the next phase.").assertDoesNotExist()
    }

    @Test fun withoutAControllerThereIsNoPanelAndNoPromise() {
        compose.setContent { Attendance80Theme { FocusScreen(data, "a", now, ZoneId.of("UTC"), FocusHistoryStatus(), rememberLazyListState(), null, {}, {}, { false }, { false }, {}, null, {}) } }
        compose.onNodeWithTag("timer-panel").assertDoesNotExist()
        compose.onNodeWithText("Timer controls are coming in the next phase.").assertDoesNotExist()
    }

    @Test fun paletteCommandsRunOnceAndClear() {
        show(running)
        pending = PaletteAction.Focus("pause"); compose.waitForIdle()
        assertEquals(listOf("toggle"), actions.calls); assertEquals(1, consumed); assertNull(pending)
        pending = PaletteAction.Focus("skip"); compose.waitForIdle()
        pending = PaletteAction.Focus("stop"); compose.waitForIdle()
        assertEquals(listOf("toggle", "skip", "stop"), actions.calls)
        pending = PaletteAction.Focus("start"); compose.waitForIdle()
        assertEquals("start does nothing while a timer runs", 3, actions.calls.size)
        assertEquals(4, consumed)
    }

    @Test fun focusOnHomeworkAndOwnTasksStartsThatTarget() {
        show(idle())
        pending = PaletteAction.Focus("target", homeworkId = 1); compose.waitForIdle()
        assertEquals("focusOn", actions.calls.last())
        assertEquals(FocusTarget(homework_id = 1, course_id = 1, label = "Maths Exercise 4"), actions.focused)
        pending = PaletteAction.Focus("target", task = "Thesis"); compose.waitForIdle()
        assertEquals(FocusTarget(label = "Thesis"), actions.focused)
        assertEquals(2, consumed)
    }

    @Test fun newTaskCommandOpensTheTaskField() {
        show(idle())
        pending = PaletteAction.Focus("new-task"); compose.waitForIdle()
        compose.onNodeWithTag("timer-new-task").assertExists()
    }
}
