package com.knhdixh.attendance80.ui.sessions

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import java.time.ZoneId
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class SessionsScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun lightMain() = capture(false, 1f, "main")
    @Test fun darkMain() = capture(true, 1f, "main")
    @Test fun lightMain2() = capture(false, 2f, "main")
    @Test fun darkMain2() = capture(true, 2f, "main")
    @Test fun lightEmpty() = capture(false, 1f, "empty")
    @Test fun darkEmpty() = capture(true, 1f, "empty")
    @Test fun lightSheet() = capture(false, 1f, "sheet")
    @Test fun darkSheet() = capture(true, 1f, "sheet")
    @Test fun lightMenu() = capture(false, 1f, "menu")
    @Test fun darkMenu() = capture(true, 1f, "menu")
    @Test fun lightSelected() = capture(false, 1f, "selected")
    @Test fun darkSelected() = capture(true, 1f, "selected")
    @Test fun lightPast() = capture(false, 1f, "past")
    @Test fun darkPast() = capture(true, 1f, "past")
    @Test fun lightPastSheet() = capture(false, 1f, "pastSheet")
    @Test fun darkPastSheet() = capture(true, 1f, "pastSheet")
    @Test fun lightPastSheetScrolled() = capture(false, 1f, "pastSheetScrolled")
    @Test fun darkPastSheetScrolled() = capture(true, 1f, "pastSheetScrolled")
    @Test fun lightPastSheet2() = capture(false, 2f, "pastSheet")
    @Test fun darkPastSheet2() = capture(true, 2f, "pastSheet")
    @Test fun lightPastSheetScrolled2() = capture(false, 2f, "pastSheetScrolled")
    @Test fun darkPastSheetScrolled2() = capture(true, 2f, "pastSheetScrolled")
    private fun capture(dark: Boolean, scale: Float, kind: String) {
        val now = 1793600000000L
        val base = screenshotData()
        val course = CourseDto(900, "A very long course name for studying advanced systems")
        val past = kind == "past" || kind == "pastSheet" || kind == "pastSheetScrolled"
        val event = EventDto(901, 900, "A long session title for the examination", location = "A long room name", start_at = if (past) now - 3600000 else now + 60000, end_at = if (past) now - 60000 else now + 3600000, tag = if (past) "exam" else null, note = if (past) "Review the lecture" else "", status = if (past) "absent" else null)
        val homework = if (past) listOf(HomeworkDto(902, 900, "Homework due here", due_at = event.start_at), HomeworkDto(903, 900, "Catch up", kind = "catchup", event_id = event.id)) else emptyList()
        val data = base.copy(courses = listOf(course), events = if (kind == "empty") emptyList() else listOf(event), homework = homework)
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = data, session = "test"), {}, {}, {}, initialRoute = "sessions", now = now, zone = ZoneId.of("Europe/Paris")) }
        } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() }
        if (past) { ui.onNodeWithText("Past").performClick(); ui.waitUntil(10000) { ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() } }
        if (kind == "menu") ui.onNodeWithText("All tracked courses").performClick()
        if (kind in listOf("selected", "sheet", "past", "pastSheet", "pastSheetScrolled")) {
            ui.onNodeWithTag("sessions-list").performScrollToNode(hasTestTag("session-row-901"))
            if (kind == "selected") ui.onNodeWithText("Select").performClick() else if (kind != "past") ui.onNodeWithText(event.summary).performClick()
        }
        if (kind == "sheet" || kind == "pastSheet" || kind == "pastSheetScrolled") ui.mainClock.advanceTimeBy(500)
        if (kind == "pastSheetScrolled") ui.onNodeWithText("Remove catch-up").performScrollTo()
        val target = if (kind == "sheet" || kind == "pastSheet" || kind == "pastSheetScrolled" || kind == "menu") ui.onNode(isDialog()) else ui.onRoot()
        target.captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_sessions_${kind}_${scale.toInt()}.png")
    }
}
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneSessionsScreenshotTest : SessionsScreenshotTest() { override val device = "phone411x891" }
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletSessionsScreenshotTest : SessionsScreenshotTest() { override val device = "tablet840x1000" }
