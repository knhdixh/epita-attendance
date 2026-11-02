package com.knhdixh.attendance80.ui.homework

import com.knhdixh.attendance80.net.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkViewModelTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli()
    private fun base() = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
    @Test fun stateTracksEqualChangedNullAndNewData() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val model = HomeworkViewModel()
            val data = base().copy(courses = listOf(CourseDto(1, "Tracked"), CourseDto(2, "Untracked", tracked = false)), homework = listOf(
                HomeworkDto(1, title = "Today", due_date = "2026-03-28", due_at = now),
                HomeworkDto(2, title = "Past done", due_date = "2026-03-27", due_at = now - 1, done = true)))
            model.update(data, now, zone)
            val first = withTimeout(5000) { model.state.filterNotNull().first() }
            assertEquals(listOf(1L), first.tracked.map { it.id })
            assertEquals(listOf(1L), first.buckets.getValue(HomeworkBucket.Today).map { it.id })
            assertEquals(listOf(2L), first.buckets.getValue(HomeworkBucket.Done).map { it.id })
            model.update(data.copy(), now, zone); assertSame(first, model.state.value)
            val changed = data.copy(homework = emptyList())
            model.update(changed, now, zone)
            assertTrue(withTimeout(5000) { model.state.filterNotNull().first { it.data == changed } }.buckets.values.all { it.isEmpty() })
            model.update(null, now, zone); assertNull(model.state.value)
            model.update(base(), now, zone)
            assertTrue(withTimeout(5000) { model.state.filterNotNull().first() }.tracked.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
    @Test fun sessionsUseScopeStrictFutureAndFirstTen() {
        val events = (0L..12L).map { EventDto(it + 1, 4, "Session", start_at = now + it, end_at = now + it + 1) } + EventDto(30, 5, "Other", start_at = now + 100, end_at = now + 101)
        assertEquals((2L..11L).toList(), homeworkSessions(events, 4, now).map { it.id })
        assertTrue(homeworkSessions(events, null, now).isEmpty())
    }
    @Test fun fractionalFocusUsesWebRounding() {
        assertEquals("2 min", focusHours(1.5))
        assertEquals("1 h", focusHours(60.0))
        assertEquals("1.1 h", focusHours(63.0))
    }
}
