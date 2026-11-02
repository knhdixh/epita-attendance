package com.knhdixh.attendance80.ui.sessions

import com.knhdixh.attendance80.net.*
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    @Test fun orderingTagsHiddenAndUnitsAtDstBoundary() {
        val now = java.time.Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()
        val zone = ZoneId.of("Europe/Paris")
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val data = base.copy(courses = listOf(CourseDto(1, "Exam", tag = "exam"), CourseDto(2, "Hidden", tracked = false)), events = listOf(
            EventDto(1, 1, "Early", start_at = now - 7200000, end_at = now - 3600000, status = "absent"),
            EventDto(2, 1, "Optional", start_at = now - 3600000, end_at = now - 1800000, tag = "optional"),
            EventDto(3, 2, "Hidden", start_at = now - 120000, end_at = now - 60000)))
        fun ids(f: SessionFilter) = SessionsViewModel.derive(data, now, zone, f).events.map { it.id }
        assertEquals(listOf(2L, 1L), ids(SessionFilter(whenFilter = "past")))
        assertEquals(listOf(1L), ids(SessionFilter(whenFilter = "skips")))
        assertEquals(listOf(1L), ids(SessionFilter(whenFilter = "mustattend")))
        assertEquals(listOf(1L, 2L, 3L), ids(SessionFilter(whenFilter = "all", showHidden = true)))
        assertEquals(listOf(3L), ids(SessionFilter(course = 2, whenFilter = "all")))
        assertEquals("1 session", unitLabel(1.0, "sessions")); assertEquals("2 sessions", unitLabel(2.0, "sessions")); assertEquals("1.5 h", unitLabel(1.5, "hours"))
        assertNotNull(SessionsViewModel.derive(data, now, zone, SessionFilter(course = 1)).summary)
        assertNotNull(SessionsViewModel.derive(data.copy(user = data.user.copy(unit = "hours")), now, zone, SessionFilter(course = 1)).summary)
    }
    @Test fun filtersScopeAndValueEquality() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val now = 1793600040000L
            val zone = ZoneId.of("Europe/Paris")
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
            val data = base.copy(courses = listOf(CourseDto(1, "Tracked", tag = "exam"), CourseDto(2, "Hidden", tracked = false)), events = listOf(
                EventDto(1, 1, "Past", note = "needle", start_at = now - 120000, end_at = now - 60000),
                EventDto(2, 1, "Current", start_at = now - 60000, end_at = now + 60000),
                EventDto(3, 2, "Hidden", start_at = now + 60000, end_at = now + 120000)))
            val model = SessionsViewModel()
            model.update(data, now, zone, SessionFilter())
            val first = withTimeout(5000) { model.state.filterNotNull().first() }
            assertEquals(listOf(2L), first.events.map { it.id })
            model.update(data.copy(), now, zone, SessionFilter())
            assertSame(first, model.state.value)
            model.update(data, now, zone, SessionFilter(whenFilter = "unmarked"))
            assertEquals(listOf(1L, 2L), withTimeout(5000) { model.state.filterNotNull().first { it.filter.whenFilter == "unmarked" } }.events.map { it.id })
            model.update(data, now, zone, SessionFilter(course = 2, whenFilter = "all"))
            assertEquals(listOf(3L), withTimeout(5000) { model.state.filterNotNull().first { it.filter.course == 2L } }.events.map { it.id })
            model.update(data, now, zone, SessionFilter(whenFilter = "mustattend", query = "needle"))
            assertEquals(listOf(1L), withTimeout(5000) { model.state.filterNotNull().first { it.filter.query == "needle" } }.events.map { it.id })
            model.update(data.copy(events = emptyList()), now, zone, SessionFilter(whenFilter = "all"))
            assertTrue(withTimeout(5000) { model.state.filterNotNull().first { it.data.events.isEmpty() } }.events.isEmpty())
            model.update(null, now, zone, SessionFilter())
            assertNull(model.state.value)
        } finally { Dispatchers.resetMain() }
    }
}
