package com.knhdixh.attendance80.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
        compose.onNodeWithText(emptyText).assertExists()
        compose.runOnIdle { data = populated }
        compose.onNodeWithText(populatedText).assertExists()
    }
    @Test fun today() = content("today", "No classes today.", "Mathématiques")
    @Test fun attendance() = content("attendance", "No tracked courses.", "Algorithmique")
    @Test fun homework() = content("homework", "Nothing due. Press Q to add homework.", "Maths Exercise 4")
    @Test fun focus() = content("focus", "Your focus sessions will show here. Press F to start one.", "Algorithms revision")
    @Test fun upcoming() = content("upcoming", "Nothing scheduled", "Algorithmique")
    @Test fun sessions() = content("sessions", "No sessions match.", "Algorithmique")
    @Test fun courses() = content("courses", "No tracked courses.", "Algorithmique")
    @Test fun calendar() = content("calendar", "Nothing scheduled", "Algorithmique")
    @Test fun habits() = content("habits", "Build habits that stick. Add one, tick it off each day, and watch the grid fill in.", "Read")
    @Test fun countdowns() = content("countdowns", "Count down to what matters, or count up from when something began.", "Exam")
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
        compose.onNodeWithText("Classes").performTouchInput { swipeDown(startY = 0f, endY = 500f) }
        compose.runOnIdle { assertEquals(1, refreshes); state = state.copy(error = "Try again") }
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }
    @Test fun oneCalendarHidesScope() {
        compose.setContent { Attendance80Theme { Shell(ShellState(data = populated), {}, {}, {}) } }
        compose.onNodeWithText("Count").assertDoesNotExist()
    }
}
