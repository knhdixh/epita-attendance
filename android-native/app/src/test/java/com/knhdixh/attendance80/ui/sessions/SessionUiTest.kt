package com.knhdixh.attendance80.ui.sessions

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import java.time.ZoneId
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun buttonsAndSwipeDispatchRealActions() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val event = EventDto(7, 1, "Lecture", start_at = 1000, end_at = 2000, status = "present")
        val data = base.copy(courses = listOf(CourseDto(1, "Course")), events = listOf(event))
        val actions = mutableListOf<PaletteAction>()
        ui.setContent { Attendance80Theme { SessionRow(event, data, 3000, ZoneId.of("UTC"), {}, { actions.add(it) }) } }
        ui.onNodeWithText("Attended").performClick()
        assertEquals(PaletteAction.SessionStatus(7, null), actions.last())
        ui.onNodeWithTag("session-row-7").performTouchInput { swipeLeft() }
        assertEquals(PaletteAction.SessionStatus(7, "absent"), actions.last())
        ui.onNodeWithTag("session-row-7").performTouchInput { swipeRight() }
        assertEquals(PaletteAction.SessionStatus(7, null), actions.last())
    }
    @Test fun selectionUsesVisibleIdsAndClearsWhenFilterChanges() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val now = 1793600040000L
        val events = listOf(EventDto(7, 1, "First", start_at = now + 60000, end_at = now + 120000), EventDto(8, 1, "Second", start_at = now + 120000, end_at = now + 180000))
        val data = base.copy(courses = listOf(CourseDto(1, "Course")), events = events)
        val actions = mutableListOf<PaletteAction>()
        ui.setContent { Attendance80Theme { Shell(ShellState(data = data, session = "owner"), {}, {}, {}, initialRoute = "sessions", now = now, zone = ZoneId.of("UTC"), onAction = { actions.add(it) }) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("sessions-list").performScrollToNode(hasTestTag("session-row-7"))
        ui.onNode(hasText("Select") and hasAnyAncestor(hasTestTag("session-row-7"))).performClick()
        ui.onNodeWithTag("sessions-list").performScrollToNode(hasTestTag("session-row-8"))
        ui.onNode(hasText("Select") and hasAnyAncestor(hasTestTag("session-row-8"))).performClick()
        ui.onNodeWithTag("sessions-list").performScrollToNode(hasSetTextAction())
        ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("sessions-list"))).performTextInput("First")
        ui.waitUntil(10000) { ui.onAllNodes(hasText("Deselect") and hasAnyAncestor(hasTestTag("session-row-7"))).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("sessions-list").performScrollToNode(hasText("Absent"))
        ui.onNodeWithText("Absent").performClick()
        assertEquals(PaletteAction.BulkAttendance(listOf(7), "absent"), actions.last())
    }
    @Test fun detailTagAndNoteDispatch() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val event = EventDto(7, 1, "Lecture", start_at = 1000, end_at = 2000)
        val data = base.copy(courses = listOf(CourseDto(1, "Course")), events = listOf(event), homework = listOf(HomeworkDto(8, 1, "Due here", due_at = event.start_at), HomeworkDto(9, 1, "Catch up", kind = "catchup", event_id = 7)))
        val actions = mutableListOf<PaletteAction>()
        var route = ""; var closed = false
        ui.setContent { Attendance80Theme { SessionDetail(data, 7, 3000, ZoneId.of("UTC"), { closed = true }, { route = it }, { actions.add(it) }) } }
        ui.onNodeWithText("Due here").performClick(); assertEquals("homework/8", route)
        ui.onNodeWithText("Open catch-up").performClick(); assertEquals("homework/9", route)
        ui.onNodeWithText("Exam").performScrollTo().performClick()
        assertEquals(PaletteAction.SessionField(7, "tag", "exam"), actions.last())
        ui.onNodeWithText("Course default").performScrollTo().performClick()
        assertEquals(PaletteAction.SessionField(7, "tag", null), actions.last())
        ui.onNodeWithText("Note").performScrollTo().performTextInput("Read slides")
        ui.onNodeWithText("Save note").performScrollTo().performClick()
        assertEquals(PaletteAction.SessionField(7, "note", "Read slides"), actions.last())
        ui.onNodeWithText("Note").performScrollTo().performTextReplacement("x".repeat(2100))
        ui.onNodeWithText("Save note").performScrollTo().performClick()
        assertEquals(2000, (actions.last() as PaletteAction.SessionField).value!!.length)
        ui.onNodeWithText("Remove catch-up").performScrollTo().performClick()
        assertEquals(PaletteAction.SessionCatchup(7, true), actions.last())
        val count = actions.size
        ui.onNodeWithText("Note").performScrollTo().performTextReplacement("unsaved")
        ui.onNodeWithText("Close").performClick()
        assertTrue(closed); assertEquals(count, actions.size)
    }
    @Test fun bannerDisappearsWhenAnyFamilyHasASelection() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val courses = listOf(CourseDto(1, "GR1 - Alpha", tracked = true), CourseDto(2, "GR2 - Alpha", tracked = false), CourseDto(3, "GR1 - Beta", tracked = false), CourseDto(4, "GR2 - Beta", tracked = false))
        ui.setContent { Attendance80Theme { Shell(ShellState(data = base.copy(courses = courses), session = "owner"), {}, {}, {}, initialRoute = "sessions", now = 1793600040000L, zone = ZoneId.of("UTC")) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Choose groups").assertCountEquals(0)
    }
}
