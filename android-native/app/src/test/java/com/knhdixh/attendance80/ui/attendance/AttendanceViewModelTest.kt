package com.knhdixh.attendance80.ui.attendance

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.net.*
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceViewModelTest {
    @Test fun stateUsesPinnedClockAndRecomputesForNewScope() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val clock = object : Clock { override fun now() = 1793600000000L; override fun zone() = ZoneId.of("Europe/Paris") }
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
            val data = base.copy(courses = listOf(CourseDto(1, "Course")), events = listOf(EventDto(4, 1, "Past", start_at = clock.now() - 120000, end_at = clock.now() - 60000)))
            val model = AttendanceViewModel()
            model.update(data, clock.now(), clock.zone())
            val first = withTimeout(5000) { model.state.filterNotNull().first() }
            assertEquals(clock.now() / 60000 * 60000, first.now)
            assertEquals(clock.zone(), first.zone)
            assertEquals(listOf(4L), first.unmarked.map { it.id })
            val scoped = data.copy(events = emptyList())
            model.update(scoped, clock.now(), clock.zone())
            val next = withTimeout(5000) { model.state.filterNotNull().first { it.data === scoped } }
            assertTrue(next.courses.isEmpty()); assertTrue(next.unmarked.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
}
