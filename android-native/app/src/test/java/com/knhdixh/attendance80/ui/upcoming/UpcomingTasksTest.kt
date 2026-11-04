package com.knhdixh.attendance80.ui.upcoming

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.tasks.TaskEnv
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
class UpcomingTasksTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
    private fun at(day: String) = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun dayUnderIsTheHeadingAtOrAboveThePointer() {
        val tops = listOf("2026-10-28" to 100f, "2026-10-29" to 300f, "2026-10-30" to 500f)
        assertEquals("2026-10-29", dayUnder(tops, 320f))
        assertEquals("2026-10-30", dayUnder(tops, 900f))
        assertEquals("2026-10-28", dayUnder(tops, 10f))
        assertNull(dayUnder(emptyList(), 10f))
    }

    @Test fun draggingATaskOntoAnotherDayGivesItThatDate() {
        val rows = listOf(HomeworkDto(1, title = "Essay", due_date = "2026-10-28", due_at = at("2026-10-28")), HomeworkDto(2, title = "Lab", due_date = "2026-10-29", due_at = at("2026-10-29")))
        val data = base.copy(homework = rows)
        val moves = mutableListOf<Pair<Long, String>>()
        val env = TaskEnv(data, "2026-10-28", zone)
        compose.setContent { Attendance80Theme { UpcomingScreen(data, now, zone, 0, {}, {}, {}, taskEnv = env, onMoveToDay = { h, d -> moves += h.id to d }) } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("upcoming-homework-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("upcoming-homework-1").performTouchInput { down(center); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(0f, 230f), 400); up() }
        compose.waitForIdle()
        assertEquals(listOf(1L to "2026-10-29").map { it.first }, moves.map { it.first })
        assertTrue(moves.single().second > "2026-10-28")
    }

    @Test fun theAgendaKeepsGoingWhenItsEndIsReached() {
        // Two tasks on every day of the week make it longer than the screen, so it can be scrolled to its end.
        val rows = (28..31).flatMap { d -> (1..3).map { n -> HomeworkDto(d * 10L + n, title = "Task $d-$n", due_date = "2026-10-$d", due_at = at("2026-10-$d")) } } +
            (1..3).flatMap { d -> (1..3).map { n -> HomeworkDto(500L + d * 10 + n, title = "Nov $d-$n", due_date = "2026-11-0$d", due_at = at("2026-11-0$d")) } }
        compose.setContent { Attendance80Theme { UpcomingScreen(base.copy(homework = rows), now, zone, 0, {}, {}, {}) } }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("upcoming-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("upcoming-section-2026-11-03").assertDoesNotExist()
        compose.onNodeWithTag("upcoming-list").performScrollToNode(hasTestTag("upcoming-more-next"))
        compose.waitUntil(10000) { compose.onAllNodesWithTag("upcoming-section-2026-11-03").fetchSemanticsNodes().isNotEmpty() || runCatching { compose.onNodeWithTag("upcoming-list").performScrollToNode(hasTestTag("upcoming-section-2026-11-03")) }.isSuccess }
        compose.onNodeWithTag("upcoming-section-2026-11-03").assertExists()
    }
}
