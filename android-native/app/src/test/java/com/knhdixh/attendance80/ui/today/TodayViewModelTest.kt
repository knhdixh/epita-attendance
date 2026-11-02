package com.knhdixh.attendance80.ui.today

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.net.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi

class TodayViewModelTest {
    private val clock = object : Clock {
        override fun now() = 1793600000000L
        override fun zone() = ZoneId.of("Europe/Paris")
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun stateUpdatesFromPinnedClockAndScopedFixture() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data.json")!!.readText()).copy(events = emptyList())
            val model = TodayViewModel()
            model.update(data, clock.now(), clock.zone())
            val state = withTimeout(5000) { model.state.filterNotNull().first() }
            assertTrue(state.plan.todaySessions.isEmpty())
            assertEquals(clock.now() / 60000 * 60000, state.plan.now)
            assertEquals("2026-11-02", state.day)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun includesDueHabitPinnedCountdownAndOverlaps() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data.json")!!.readText())
        val now = clock.now()
        val data = base.copy(
            courses = listOf(CourseDto(1, "One"), CourseDto(2, "Two")),
            events = listOf(EventDto(1, 1, "One", start_at = now + 3600000, end_at = now + 7200000), EventDto(2, 2, "Two", start_at = now + 5400000, end_at = now + 9000000)),
            habits = HabitsDto(list = listOf(HabitDto(3, "Read", color = "blue", schedule = HabitScheduleDto("daily"), target = 1, position = 0, archived = false, created_at = now - 86400000))),
            countdowns = base.countdowns.map { it.copy(pinned = true, at = now + 86400000) })
        val state = TodayViewModel.derive(data, now, clock.zone())
        assertEquals(listOf(listOf(1L, 2L)), state.plan.week.clashes)
        assertEquals(listOf(3L), state.habits.map { it.habit.id })
        assertTrue(state.countdowns.isNotEmpty())
        assertTrue(state.plan.week.classHours > 0)
    }
    @Test fun derivesRankedPlanFromScopedSnapshot() {
        val fixture = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data.json")!!.readText())
        val now = clock.now()
        val data = fixture.copy(courses = listOf(CourseDto(99, "Scoped course")),
            events = listOf(EventDto(100, 99, "Next class", start_at = now + 3600000, end_at = now + 7200000)),
            homework = listOf(HomeworkDto(1, title = "Later start", due_at = now + 7200000, effort_minutes = 30), HomeworkDto(2, title = "Earlier start", due_at = now + 7200000, effort_minutes = 90)))
        val result = TodayViewModel.derive(data, now, clock.zone())
        assertEquals(listOf(2L, 1L), result.plan.todo.map { it.id })
        assertEquals(100L, result.plan.next)
        assertEquals("urgent", result.plan.todo.first().urgency)
        assertEquals(data, result.data)
    }
}
