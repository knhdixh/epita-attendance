package com.knhdixh.attendance80.ui.sessions

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
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
class SharedRowIntegrationTest {
    @get:Rule val ui = createComposeRule()
    private val now = 1793600040000L
    private fun check(route: String, listTag: String) {
        val base = screenshotData()
        val data = base.copy(courses = listOf(CourseDto(900, "Shared course")), events = listOf(EventDto(901, 900, "Shared session", start_at = now - 60000, end_at = now + 3600000)), homework = emptyList())
        var action: PaletteAction? = null
        ui.setContent { Attendance80Theme { Shell(ShellState(data = data, session = "owner"), {}, {}, {}, initialRoute = route, now = now, zone = ZoneId.of("Europe/Paris"), onAction = { action = it }) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag(listTag).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag(listTag).performScrollToNode(hasTestTag("session-row-901"))
        ui.onNode(hasText("Absent") and hasAnyAncestor(hasTestTag("session-row-901"))).performClick()
        ui.runOnIdle { assertEquals(PaletteAction.SessionStatus(901, "absent"), action) }
    }
    @Test fun today() = check("today", "today-list")
    @Test fun attendance() = check("attendance", "attendance-list")
    @Test fun course() = check("course/900", "course-detail")
}
