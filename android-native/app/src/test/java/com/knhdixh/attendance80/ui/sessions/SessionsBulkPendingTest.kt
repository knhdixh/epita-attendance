package com.knhdixh.attendance80.ui.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** After a filter change the old list stays until the new one is worked out; "mark these N" must not use it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionsBulkPendingTest {
    @get:Rule val ui = createComposeRule()

    @Test fun markShownWaitsForTheListOfTheCurrentFilter() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val now = 1793600040000L
        val events = listOf(
            EventDto(7, 1, "Unmarked", start_at = now - 7200000, end_at = now - 3600000),
            EventDto(8, 1, "Marked", start_at = now - 7200000, end_at = now - 3600000, status = "present"),
        )
        val data = base.copy(courses = listOf(CourseDto(1, "Course")), events = events)
        val dispatcher = StandardTestDispatcher()
        val model = SessionsViewModel(dispatcher)
        var filter by mutableStateOf(SessionFilter(whenFilter = "all"))
        val actions = mutableListOf<PaletteAction>()
        ui.setContent { Attendance80Theme { SessionsScreen(data, now, ZoneId.of("UTC"), filter, { filter = it }, null, {}, {}, { actions += it }, model) } }
        dispatcher.scheduler.advanceUntilIdle()
        ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() }

        filter = SessionFilter(whenFilter = "unmarked") // the "all" list (two sessions) is still on screen
        ui.waitForIdle()
        ui.onNodeWithTag("sessions-mark-shown").assertIsNotEnabled()
        ui.onNodeWithTag("sessions-mark-shown").performClick()
        assertTrue("nothing is sent from the previous filter's list", actions.isEmpty())

        dispatcher.scheduler.advanceUntilIdle()
        ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); ui.onAllNodesWithTag("sessions-mark-shown").fetchSemanticsNodes().isNotEmpty() && runCatching { ui.onNodeWithTag("sessions-mark-shown").assertIsEnabled() }.isSuccess }
        ui.onNodeWithTag("sessions-mark-shown").performClick()
        assertEquals(PaletteAction.BulkAttendance(listOf(7L)), actions.single())
    }
}
