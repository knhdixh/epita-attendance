package com.knhdixh.attendance80.ui.upcoming

import com.knhdixh.attendance80.net.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpcomingViewModelTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun millis(day: String) = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun base() = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
    @Test fun boundariesDstTrackingAndOrder() {
        listOf("2026-03-29" to 23L, "2026-10-25" to 25L).forEach { (sunday, hours) ->
            val date = LocalDate.parse(sunday)
            val start = millis(sunday)
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            assertEquals(hours * 3600000, end - start)
            val data = base().copy(courses = listOf(CourseDto(1, "Tracked", attendance = false), CourseDto(2, "Hidden", tracked = false)), events = listOf(
                EventDto(1, 1, "Last", start_at = end - 1, end_at = end), EventDto(2, 1, "First", start_at = start, end_at = end),
                EventDto(3, 2, "Hidden", start_at = start, end_at = end), EventDto(4, 1, "Next week", start_at = end, end_at = end + 1)),
                homework = listOf(HomeworkDto(2, title = "Midnight", due_at = start), HomeworkDto(3, title = "Done", due_at = start, done = true), HomeworkDto(4, title = "Monday", due_at = end)))
            val result = UpcomingViewModel.derive(data, date.minusDays(2), zone, 0)
            val day = result.days.last()
            assertEquals(listOf(2L, 1L), day.sessions.map { it.id })
            assertEquals(listOf(2L), day.homework.map { it.id })
            assertEquals(7, result.days.size)
            assertTrue(result.days.first().past)
            assertTrue(result.days[4].sessions.isEmpty())
            assertEquals(listOf(4L), UpcomingViewModel.derive(data, date.minusDays(2), zone, 1).days.first().sessions.map { it.id })
        }
        val year = UpcomingViewModel.derive(base(), LocalDate.parse("2026-12-31"), zone, 0)
        assertEquals(LocalDate.parse("2027-01-03"), year.days.last().date)
        assertEquals(LocalDate.parse("2027-01-04"), UpcomingViewModel.derive(base(), LocalDate.parse("2026-12-31"), zone, 1).days.first().date)
    }
    @Test fun realModelEqualChangedNullAndNewOwner() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val clock = Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), zone)
            val data = base()
            val model = UpcomingViewModel()
            model.update(data, clock, zone, 0)
            val first = withTimeout(5000) { model.state.filterNotNull().first() }
            model.update(data.copy(), clock, zone, 0)
            assertSame(first, model.state.value)
            val changed = data.copy(homework = listOf(HomeworkDto(1, title = "New", due_at = millis("2026-10-25"))))
            model.update(changed, clock, zone, 0)
            assertEquals("New", withTimeout(5000) { model.state.filterNotNull().first { it.data == changed } }.days.last().homework.single().title)
            model.update(null, clock, zone, 0)
            assertNull(model.state.value)
            val newOwner = UpcomingViewModel()
            newOwner.update(data, clock, zone, 1)
            assertEquals(1, withTimeout(5000) { newOwner.state.filterNotNull().first() }.offset)
        } finally { Dispatchers.resetMain() }
    }
}
