package com.knhdixh.attendance80.ui.today

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
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
class TodayUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun emptyDataShowsTodayEmptyStates() {
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        compose.setContent { Attendance80Theme { TodayScreen(data, 1793600000000L, ZoneId.of("Europe/Paris"), {}, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nothing due in the next 7 days.").assertIsDisplayed()
        compose.onNodeWithTag("today-list").performScrollToNode(hasText("No classes today."))
        compose.onNodeWithText("No classes today.").assertIsDisplayed()
    }
    @Test fun overlappingPlannedItemsBothRenderAndScroll() {
        val now = 1793600000000L
        val start = now + 2 * 3600000L
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText()).copy(
            homework = listOf(
                HomeworkDto(101, title = "First planned task", due_at = now + 10 * 86400000L, planned_at = start, planned_minutes = 60),
                HomeworkDto(102, title = "Second planned task", due_at = now + 10 * 86400000L, planned_at = start, planned_minutes = 30),
            ))
        compose.setContent { Attendance80Theme { TodayScreen(data, now, ZoneId.of("Europe/Paris"), {}, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("today-list").performScrollToNode(hasText("First planned task"))
        compose.onNodeWithText("First planned task").assertIsDisplayed()
        compose.onNodeWithTag("today-list").performScrollToNode(hasText("Second planned task"))
        compose.onNodeWithText("Second planned task").assertIsDisplayed()
        compose.onNodeWithTag("today-list").performScrollToNode(hasText("First planned task"))
        compose.onNodeWithText("First planned task").assertIsDisplayed()
    }
    @Test fun habitCheckAndHomeworkNavigation() {
        val now = 1793600000000L
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText()).copy(
            habits = HabitsDto(list = listOf(HabitDto(7, "Read daily", color = "blue", schedule = HabitScheduleDto("daily"), target = 1, position = 0, archived = false, created_at = now - 86400000))),
            homework = listOf(HomeworkDto(8, title = "Prepare report", due_at = now + 3600000)))
        var action: PaletteAction? = null
        var route = ""
        compose.setContent { Attendance80Theme { TodayScreen(data, now, ZoneId.of("Europe/Paris"), { route = it }, { action = it }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Prepare report").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Prepare report").onFirst().performClick()
        compose.runOnIdle { assertEquals("homework/8", route) }
        compose.onNodeWithTag("today-list").performScrollToNode(hasText("Read daily"))
        compose.onNodeWithContentDescription("Check Read daily").performClick()
        compose.runOnIdle { assertEquals(PaletteAction.CheckHabit(7, "2026-11-02", 1), action) }
    }
}
