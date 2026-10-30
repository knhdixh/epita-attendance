package com.knhdixh.attendance80.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.CalendarDto
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class ShellUiTest {
    @get:Rule val compose = createComposeRule()
    private val empty = ApiClient.json.decodeFromString<com.knhdixh.attendance80.net.AppData>(
        object {}.javaClass.getResource("/api/data_empty.json")!!.readText())
    private val populated = screenshotData()
    private fun content(route: String, emptyText: String, populatedText: String) {
        var data by mutableStateOf(empty)
        compose.setContent { Attendance80Theme { Shell(ShellState(data = data), {}, {}, {}, initialRoute = route,
            now = 1793600000000, zone = java.time.ZoneId.of("Europe/Paris")) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(emptyText).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(emptyText).assertExists()
        compose.runOnIdle { data = populated }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(populatedText).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(populatedText).assertExists()
    }
    @Test fun today() {
        var data by mutableStateOf(empty)
        compose.setContent { Attendance80Theme { Shell(ShellState(data = data), {}, {}, {}, now = 1793600000000, zone = java.time.ZoneId.of("Europe/Paris")) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Nothing due in the next 7 days.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { data = populated.copy(events = populated.events.map { it.copy(status = "present") }) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Do next").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("today-list").performScrollToNode(hasText("Mathématiques"))
        compose.onNodeWithText("Mathématiques").assertExists()
    }
    @Test fun attendance() = content("attendance", "No tracked courses.", "Algorithmique")
    @Test fun homework() = content("homework", "Nothing due.", "Maths Exercise 4")
    @Test fun focus() = content("focus", "Your focus sessions will show here.", "Algorithms revision")
    @Test fun upcoming() = content("upcoming", "Nothing scheduled", "Algorithmique")
    @Test fun sessions() = content("sessions", "No sessions match.", "Algorithmique")
    @Test fun courses() = content("courses", "No tracked courses.", "Algorithmique")
    @Test fun calendar() = content("calendar", "Nothing scheduled", "Algorithmique")
    @Test fun habits() = content("habits", "Build habits that stick. Add one, tick it off each day, and watch the grid fill in.", "Read")
    @Test fun countdowns() = content("countdowns", "Count down to what matters, or count up from when something began.", "Exam")
    @Test fun homeworkDetail() = content("homework/1", "Nothing due.", "Maths Exercise 4")
    @Test fun habitDetail() = content("habits/1", "Build habits that stick. Add one, tick it off each day, and watch the grid fill in.", "Read")
    @Test fun countdownDetail() = content("countdowns/1", "Count down to what matters, or count up from when something began.", "Exam")
    @Test fun newHabit() = content("habits/new", "Build habits that stick. Add one, tick it off each day, and watch the grid fill in.", "Read")
    @Test fun newCountdown() = content("countdowns/new", "Count down to what matters, or count up from when something began.", "Exam")
    @Test fun sessionDetail() = content("session/1", "No sessions match.", "Mathématiques")
    @Test fun courseDetail() {
        var data by mutableStateOf(empty)
        compose.setContent { Attendance80Theme { Shell(ShellState(data = data), {}, {}, {}, initialRoute = "course/1", now = 1793600000000, zone = java.time.ZoneId.of("Europe/Paris")) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Course not found.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { data = populated }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Mathématiques").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("course-detail").assertIsDisplayed()
    }
    @Test fun newHomeworkRoute() = content("homework/new", "Nothing due.", "Maths Exercise 4")
    @Test fun pendingRouteIsRetainedUntilRefreshSucceeds() {
        var state by mutableStateOf(ShellState(data = populated))
        var consumed = 0
        var validated = ""
        compose.setContent { Attendance80Theme { Shell(state, {}, {}, {}, pendingRoute = "session/1", onPendingConsumed = { consumed++ }, onValidatePending = { validated = it }) } }
        compose.onNodeWithTag("shell-title").assertTextEquals("Session")
        compose.runOnIdle { assertEquals(0, consumed); assertEquals("", validated); state = state.copy(session = "token", lastSynced = 1) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, consumed); assertEquals("session/1", validated) }
    }
    @Test fun offlineLinkBackDoesNotReturnAfterSavedStateRecreation() {
        val dispatcher = OnBackPressedDispatcher()
        val owner = object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = dispatcher
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        var pending by mutableStateOf<String?>("session/1")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                Attendance80Theme { Shell(ShellState(data = populated, session = "offline"), {}, {}, {},
                    pendingRoute = pending, onPendingConsumed = { pending = null }) }
            }
        }
        compose.onNodeWithTag("shell-title").assertTextEquals("Session")
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithTag("shell-title").assertTextEquals("Today")
        compose.runOnIdle { assertEquals(null, pending) }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("shell-title").assertTextEquals("Today")
    }
    @Test fun breaks() = content("breaks", "Saved breaks", "All Saints Break")
    @Test fun settings() = content("settings", "newuser", "student")

    @Test fun scopeNavigationAndSignOut() {
        var selected = ""
        var signedOut = false
        val data = populated.copy(calendars = listOf(CalendarDto(1, "link", "First"), CalendarDto(2, "link", "Second")))
        compose.setContent { Attendance80Theme { Shell(ShellState(data = data), {}, { selected = it }, { signedOut = true }) } }
        compose.onNodeWithText("First").performClick()
        compose.runOnIdle { assertEquals("1", selected) }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.onNodeWithText("Sign out").performClick()
        compose.runOnIdle { assertEquals(true, signedOut) }
    }
    @Test fun refreshAndServerError() {
        var refreshes = 0
        var state by mutableStateOf(ShellState(data = populated))
        compose.setContent { Attendance80Theme { Shell(state, { refreshes++ }, {}, {}) } }
        compose.onNodeWithTag("today-list").performTouchInput { swipeDown(startY = 0f, endY = 500f) }
        compose.runOnIdle { assertEquals(1, refreshes); state = state.copy(error = "Try again") }
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }
    @Test fun oneCalendarHidesScope() {
        compose.setContent { Attendance80Theme { Shell(ShellState(data = populated), {}, {}, {}) } }
        compose.onNodeWithText("Count").assertDoesNotExist()
    }
}
