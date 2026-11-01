package com.knhdixh.attendance80.ui.upcoming

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class UpcomingUiTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
    @Test fun weekDayAndSharedActions() {
        val due = LocalDate.parse("2026-10-30").atStartOfDay(zone).toInstant().toEpochMilli()
        val data = base.copy(courses = listOf(CourseDto(1, "Course")), events = listOf(EventDto(1, 1, "Lesson", start_at = due, end_at = due + 3600000)), homework = listOf(HomeworkDto(1, title = "Assignment", due_at = due)))
        var offset by mutableIntStateOf(0)
        var route = ""
        var action: PaletteAction? = null
        compose.setContent { Attendance80Theme { UpcomingScreen(data, now, zone, offset, { offset = it }, { route = it }, { action = it }) } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("upcoming-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("upcoming-prev").assertIsNotEnabled()
        compose.onNodeWithTag("upcoming-day-2026-10-26").assertIsNotEnabled()
        compose.onNodeWithTag("upcoming-day-2026-10-30").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lesson").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("session/1", route) }
        compose.onNodeWithTag("upcoming-list").performScrollToNode(hasText("Assignment"))
        compose.onNodeWithText("Assignment").performClick()
        compose.runOnIdle { assertEquals("homework/1", route) }
        compose.onNodeWithContentDescription("Done: Assignment").performClick()
        compose.runOnIdle { assertEquals(PaletteAction.ToggleHomework(1, true), action) }
        compose.onNodeWithTag("upcoming-list").performScrollToNode(hasTestTag("upcoming-next"))
        compose.onNodeWithTag("upcoming-next").performClick()
        compose.waitUntil(10000) { offset == 1 && compose.onAllNodesWithText("Nothing scheduled").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("upcoming-today").performClick()
        compose.waitUntil(10000) { offset == 0 && compose.onAllNodesWithTag("upcoming-day-2026-10-28").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun largeTextStripAndControlsRemainReachable() {
        var offset by mutableIntStateOf(0)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Attendance80Theme { UpcomingScreen(base, now, zone, offset, { offset = it }, {}, {}) }
            }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("upcoming-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("upcoming-day-2026-11-01").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("upcoming-section-2026-11-01").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("upcoming-section-2026-11-01").assertIsDisplayed()
        compose.onNodeWithTag("upcoming-list").performScrollToNode(hasTestTag("upcoming-next"))
        compose.onNodeWithTag("upcoming-next").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("upcoming-prev").fetchSemanticsNodes().isNotEmpty() && offset == 1 }
        compose.onNodeWithTag("upcoming-prev").assertIsEnabled().performClick()
    }
    @Test fun bannerUsesGlobalGroupPredicateAndCountIsAccessible() {
        val date = LocalDate.parse("2026-10-30").atStartOfDay(zone).toInstant().toEpochMilli()
        var data by mutableStateOf(base.copy(courses = listOf(CourseDto(1, "GR1 - First", tracked = false), CourseDto(2, "GR2 - First", tracked = false), CourseDto(3, "GR1 - Second", tracked = false), CourseDto(4, "GR2 - Second", tracked = false))))
        compose.setContent { Attendance80Theme { UpcomingScreen(data, now, zone, 0, {}, {}, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Choose groups").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { data = data.copy(courses = data.courses.map { if (it.id == 1L) it.copy(tracked = true) else it }, events = (1L..5L).map { EventDto(it, 1, "Event $it", start_at = date, end_at = date + 3600000) }) }
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Friday 30 October: 5 sessions").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Choose groups").assertCountEquals(0)
        compose.onNodeWithContentDescription("Friday 30 October: 5 sessions").assertExists()
    }

    @Test fun homeworkShowsMetadataAndOpensRecording() {
        val due = LocalDate.parse("2026-10-28").atStartOfDay(zone).toInstant().toEpochMilli()
        val hw = HomeworkDto(9, title = "Catch up", notes = "Recording: https://example.test/lecture", due_at = due, kind = "catchup", remind_minutes = 60)
        var opened = ""
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened = uri } }) {
                Attendance80Theme { UpcomingScreen(base.copy(homework = listOf(hw)), now, zone, 0, {}, {}, {}) }
            }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("upcoming-homework-9").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Today · catch-up · reminder 1 hour before").assertExists()
        compose.onNodeWithText("recording ↗").performClick()
        compose.runOnIdle { assertEquals("https://example.test/lecture", opened) }
    }

}
