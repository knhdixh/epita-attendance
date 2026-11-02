package com.knhdixh.attendance80.focus

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.ui.focus.TimerActions
import com.knhdixh.attendance80.ui.focus.TimerPanel
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

class RecordingActions : TimerActions {
    val calls = mutableListOf<String>()
    var chosen: FocusTarget? = null
    override fun toggle() { calls += "toggle" }
    override fun skip() { calls += "skip" }
    override fun stop() { calls += "stop" }
    override fun adjust(min: Int) { calls += "adjust $min" }
    override fun setLength(min: Int) { calls += "length $min" }
    override fun setTarget(target: FocusTarget?) { chosen = target; calls += "target" }
    override fun addTask(label: String) { calls += "task $label" }
    var focused: FocusTarget? = null
    override fun focusOn(target: FocusTarget) { focused = target; calls += "focusOn" }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class TimerPanelUiTest {
    @get:Rule val compose = createComposeRule()
    private val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data.json")!!.readText())
    private val now = 1_800_000_000_000L
    private val actions = RecordingActions()
    private val running = FocusTimer(phase = "focus", next = "focus", run = "r", startedAt = now, endsAt = now + 25 * 60_000L, done = 2)
    private val scopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() { scopes.forEach { it.cancel() } }

    private fun show(timer: FocusTimer, settings: FocusSettings = FocusSettings(), pending: List<String> = emptyList()) =
        compose.setContent { Attendance80Theme { TimerPanel(timer, settings, data, pending, { now }, actions) } }

    @Test fun idleShowsReadyTheLengthAndStartsFocus() {
        show(idle())
        compose.onNodeWithTag("timer-phase").assertTextEquals("Ready to focus")
        compose.onNodeWithTag("timer-clock").assertTextEquals("25:00")
        compose.onNodeWithTag("length-25").assertIsSelected()
        compose.onNodeWithTag("timer-toggle").assertTextEquals("Start focus")
        compose.onNodeWithTag("timer-skip").assertDoesNotExist()
        compose.onNodeWithTag("timer-stop").assertDoesNotExist()
        compose.onNodeWithTag("length-45").performClick()
        compose.onNodeWithTag("adjust--5").performClick()
        compose.onNodeWithTag("adjust-1").performClick()
        compose.onNodeWithTag("timer-toggle").performClick()
        assertEquals(listOf("length 45", "adjust -5", "adjust 1", "toggle"), actions.calls)
    }

    @Test fun anIdleBreakOffersSkipAndItsOwnPresets() {
        show(idle(next = "short"), FocusSettings(short = 7))
        compose.onNodeWithTag("timer-phase").assertTextEquals("Time for a short break")
        compose.onNodeWithTag("timer-toggle").assertTextEquals("Start break")
        compose.onNodeWithTag("length-7").assertIsSelected()
        compose.onNodeWithTag("length-3").assertExists()
        compose.onNodeWithTag("length-25").assertDoesNotExist()
        compose.onNodeWithTag("timer-skip").assertTextEquals("Skip break").performClick()
        assertEquals(listOf("skip"), actions.calls)
    }

    @Test fun runningShowsTimeLeftPauseTakeABreakAndStop() {
        show(running)
        compose.onNodeWithTag("timer-phase").assertTextEquals("Focus")
        compose.onNodeWithTag("timer-clock").assertTextEquals("25:00")
        compose.onNodeWithTag("length-25").assertDoesNotExist()
        compose.onNodeWithTag("timer-toggle").assertTextEquals("Pause")
        compose.onNodeWithTag("timer-skip").assertTextEquals("Take a break").performClick()
        compose.onNodeWithTag("timer-stop").performClick()
        assertEquals(listOf("skip", "stop"), actions.calls)
    }

    @Test fun pausedShowsTheTimeLeftAndResume() {
        show(running.copy(pausedAt = now + 5 * 60_000L).let { it })
        compose.onNodeWithTag("timer-phase").assertTextEquals("Focus · paused")
        compose.onNodeWithTag("timer-clock").assertTextEquals("20:00")
        compose.onNodeWithTag("timer-toggle").assertTextEquals("Resume")
    }

    @Test fun targetPickerListsTasksHomeworkAndCoursesAndAddsATask() {
        show(idle(), FocusSettings(tasks = listOf("Thesis")), pending = listOf("Offline one"))
        compose.onNodeWithTag("timer-target").assertTextContains("Nothing in particular", substring = true).performClick()
        compose.onNodeWithTag("target-task:Offline one").assertExists()
        compose.onNodeWithTag("target-task:Thesis").assertExists()
        compose.onNodeWithTag("target-hw:1").assertExists()
        compose.onNodeWithTag("target-course:2").assertExists()
        compose.onNodeWithTag("target-hw:1").performClick()
        assertEquals(FocusTarget(homework_id = 1, course_id = 1, label = "Maths Exercise 4"), actions.chosen)
        compose.onNodeWithTag("timer-target").performClick()
        compose.onNodeWithTag("target-new").performClick()
        compose.onNodeWithTag("timer-new-task").performTextInput("  Read   chapter 3 ")
        compose.onNodeWithText("Add").performClick()
        assertEquals("task Read chapter 3", actions.calls.last())
        compose.onNodeWithTag("timer-new-task").assertDoesNotExist()
    }

    @Test fun tagsToggleAndStopAtFivePerSession() {
        val tags = listOf("a", "b", "c", "d", "e", "f")
        show(idle().copy(target = FocusTarget(label = "x", tags = listOf("a", "b", "c", "d", "e"))), FocusSettings(tags = tags))
        compose.onNodeWithTag("tag-a").assertIsSelected().performClick()
        assertEquals(listOf("b", "c", "d", "e"), actions.chosen!!.tags)
        compose.onNodeWithTag("tag-f").performClick()
        assertEquals(listOf("a", "b", "c", "d", "e"), actions.chosen!!.tags) // the sixth is refused
    }

    @Test fun tileStateReadsTheTimer() {
        val s = FocusSettings(focus = 30)
        val time = { ms: Long -> "T$ms" }
        assertEquals(TileState("Focus", "Start 30 min", false), tileState(idle(), s, now, time))
        assertEquals(TileState("Focus", "Start 40 min", false), tileState(idle().copy(custom = 40), s, now, time))
        assertEquals(TileState("Focus", "Break 5 min", false), tileState(idle(next = "short"), s, now, time))
        assertEquals(TileState("Focus", "Until T${running.endsAt}", true), tileState(running, s, now, time))
        assertEquals(TileState("Focus", "Paused · 20:00", false), tileState(running.copy(pausedAt = now + 5 * 60_000L), s, now, time))
    }
}
