package com.knhdixh.attendance80.ui.attendance

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.logic.applyScope
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class AttendanceTest {
    @get:Rule val ui = createComposeRule()
    private val now = 1793600000000L
    private val zone = ZoneId.of("Europe/Paris")
    private fun empty() = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
    private fun populated(): AppData = empty().copy(
        courses = listOf(CourseDto(1, "Risk"), CourseDto(2, "Failed"), CourseDto(3, "No attendance", attendance = false), CourseDto(4, "GR1 - Language", tracked = false)),
        events = listOf(EventDto(11, 1, "Optional", start_at = now - 3600000, end_at = now - 1, tag = "optional", cals = listOf(1)),
            EventDto(12, 1, "Unmarked", start_at = now - 3600000, end_at = now - 1, cals = listOf(1)),
            EventDto(21, 2, "Exam", start_at = now - 3600000, end_at = now - 1, status = "absent", tag = "exam", cals = listOf(2)),
            EventDto(31, 3, "Other", start_at = now - 1000, end_at = now + 1000)))
    @Test fun deriveFiltersSortsGroupsAndUnits() {
        val data = populated()
        val s = AttendanceViewModel.derive(data, now, zone)
        assertEquals(listOf(2L, 1L), s.courses.map { it.course.id })
        assertEquals(listOf(11L, 12L), s.unmarked.map { it.id })
        assertTrue(s.sessions.any { it.id == 31L })
        assertEquals(listOf("Language"), s.families)
        assertEquals(1, s.courses.first().stats.missedMustAttend.size)
        val selected = data.copy(courses = data.courses.map { if (it.id == 4L) it.copy(tracked = true) else it })
        assertTrue(AttendanceViewModel.derive(selected, now, zone).families.isEmpty())
        val hours = AttendanceViewModel.derive(data.copy(user = data.user.copy(unit = "hours")), now, zone)
        assertTrue(hours.overall.total > 1.0)
    }
    @Test fun scopeAndHourDisplay() {
        val data = populated().let { d -> d.copy(user = d.user.copy(unit = "hours"), calendars = listOf(CalendarDto(1, "file", "One"), CalendarDto(2, "file", "Two"))) }
        val scoped = data.copy(events = applyScope(data.events, data.calendars, listOf(2)))
        val state = AttendanceViewModel.derive(scoped, now, zone)
        assertEquals(listOf(2L), state.courses.map { it.course.id })
        assertTrue(state.unmarked.isEmpty())
        ui.setContent { Attendance80Theme { AttendanceScreen(scoped, now, zone, {}, {}) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("attendance-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("attendance-list").performScrollToNode(hasTestTag("attendance-course-2"))
        ui.onNodeWithText("over by 1 h  ›").assertIsDisplayed()
        ui.onNodeWithTag("threshold-2", useUnmergedTree = true).assertExists()
    }
    @Test fun emptyAndPopulatedScreenActions() {
        var action: PaletteAction? = null
        var route = ""
        val data = populated()
        ui.setContent { Attendance80Theme { AttendanceScreen(data, now, zone, { route = it }, { action = it }) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("attendance-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Mark 2 as attended").performClick()
        ui.runOnIdle { assertEquals(PaletteAction.BulkAttendance(listOf(11, 12)), action) }
        ui.onNodeWithText("Choose groups").performClick()
        ui.runOnIdle { assertEquals("courses", route) }
        ui.onNodeWithTag("attendance-list").performScrollToNode(hasTestTag("attendance-course-2"))
        ui.onNodeWithTag("attendance-course-2").performClick()
        ui.runOnIdle { assertEquals("course/2", route) }
    }
    @Test fun emptyScreen() {
        ui.setContent { Attendance80Theme { AttendanceScreen(empty(), now, zone, {}, {}) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("No tracked courses.").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("No tracked courses.").assertIsDisplayed()
    }
}
