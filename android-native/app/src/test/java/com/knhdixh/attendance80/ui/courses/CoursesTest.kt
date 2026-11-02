package com.knhdixh.attendance80.ui.courses

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.logic.countsAttendance
import com.knhdixh.attendance80.ui.course.groupSelection
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class CoursesTest {
    @get:Rule val ui = createComposeRule()
    private val now = 1793600000000L
    private val zone = ZoneId.of("Europe/Paris")
    private fun data(): AppData {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        return base.copy(courses = listOf(CourseDto(1, "GR10 - Language", tracked = false), CourseDto(2, "GR2 - Language"), CourseDto(3, "No attendance", attendance = false)),
            events = listOf(EventDto(10, 2, "Session", start_at = now - 3600000, end_at = now, status = "absent")))
    }
    @Test fun scopeCountsAndSelections() {
        val d = data()
        val state = CoursesViewModel.derive(d, now, zone)
        assertEquals(listOf(1L, 2L, 3L), state.courses.map { it.course.id })
        assertEquals(1, state.courses[1].count)
        assertEquals(1.0, state.courses[1].hours, 0.0)
        assertEquals(0.0, state.courses[1].stats.projectedRate, 0.0)
        assertFalse(countsAttendance(state.courses[0].course))
        assertFalse(countsAttendance(state.courses[2].course))
        assertEquals("GR2", groupSelection(state.families.single()))
        assertEquals("Not in this course", groupSelection(CoursesViewModel.derive(d.copy(courses = d.courses.map { it.copy(tracked = false) }), now, zone).families.single()))
        assertEquals("Several groups", groupSelection(CoursesViewModel.derive(d.copy(courses = d.courses.map { it.copy(tracked = true) }), now, zone).families.single()))
        assertEquals(0, CoursesViewModel.derive(d.copy(events = emptyList()), now, zone).courses[1].count)
    }
    @Test fun stateFlowTracksChangesAndClears() {
        val model = CoursesViewModel()
        val d = data()
        ui.runOnIdle { model.update(d, now + 1234, zone) }
        ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); model.state.value != null }
        assertEquals(now / 60000 * 60000, model.state.value!!.now)
        assertEquals(listOf(2L, 1L), model.state.value!!.families.single().courses.map { it.id })
        assertEquals(3, model.state.value!!.courses.size)
        ui.runOnIdle { model.update(d.copy(events = emptyList()), now, zone) }
        ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); model.state.value?.data?.events?.isEmpty() == true }
        ui.runOnIdle { model.update(null, now, zone) }
        assertNull(model.state.value)
    }
    @Test fun largeTextControlsRemainReachable() {
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Attendance80Theme { Shell(ShellState(data = data()), {}, {}, {}, initialRoute = "courses", now = now, zone = zone) }
            }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("courses-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("courses-list").performScrollToNode(hasTestTag("course-row-1"))
        val inRow = hasAnyAncestor(hasTestTag("course-row-1"))
        ui.onNode(hasTestTag("course-tracked") and inRow).performScrollTo().assertIsDisplayed()
        ui.onNode(hasText("Default tag: Normal") and inRow).performScrollTo().performClick()
        ui.onNodeWithText("Exam").assertIsDisplayed().performClick()
        ui.onNode(hasText("Add link") and inRow).performScrollTo().performClick()
        ui.onNodeWithTag("recording-url").assertIsDisplayed()
        ui.onNodeWithText("Cancel").performClick()
    }
    @Test fun shellRetainsEqualSnapshotAndClearsAccount() {
        val d = data()
        var shell by mutableStateOf(ShellState(data = d, session = "a"))
        ui.setContent { Attendance80Theme { Shell(shell, {}, {}, {}, initialRoute = "courses", now = now, zone = zone) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("courses-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("GR10 - Language").assertExists()
        ui.runOnIdle { shell = shell.copy(data = d.copy(), loading = true) }
        ui.onNodeWithText("GR10 - Language").assertExists()
        ui.runOnIdle { shell = ShellState(data = null) }
        ui.onAllNodesWithText("GR10 - Language").assertCountEquals(0)
        ui.runOnIdle { shell = ShellState(data = d.copy(courses = emptyList()), session = "b") }
        ui.waitUntil(10000) { ui.onAllNodesWithText("All courses").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("GR10 - Language").assertCountEquals(0)
    }
}
