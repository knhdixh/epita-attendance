package com.knhdixh.attendance80.ui.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AttendanceState(val data: AppData, val now: Long, val zone: ZoneId, val overall: AttendanceStats,
    val courses: List<CourseStats>, val unmarked: List<EventDto>, val sessions: List<EventDto>,
    val today: Boolean, val homework: List<HomeworkDto>, val openHomework: Int, val families: List<String>)

class AttendanceViewModel : ViewModel() {
    private val mutable = MutableStateFlow<AttendanceState?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0
    fun update(data: AppData?, now: Long, zone: ZoneId) {
        val minute = now / 60000 * 60000
        if (mutable.value?.let { it.data == data && it.now == minute && it.zone == zone } == true) return
        val version = ++generation
        if (data == null) { mutable.value = null; return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { derive(data, minute, zone) }
            if (version == generation) mutable.value = result
        }
    }
    companion object {
        fun derive(data: AppData, now: Long, zone: ZoneId): AttendanceState {
            val stats = statsByCourse(data.courses, data.events, data.user.threshold, data.user.unit, now)
            val order = mapOf("fail" to 0, "risk" to 1, "ok" to 2)
            val cards = stats.perCourse.filter { countsAttendance(it.course) && it.stats.sessions > 0 }
                .sortedWith(compareBy<CourseStats> { order[it.stats.level] ?: 3 }.thenBy { it.stats.remaining })
            val byId = data.courses.associateBy { it.id }
            val tracked = data.events.filter { byId[it.course_id]?.tracked == true }
            val unmarked = tracked.filter { it.start_at <= now && it.status.isNullOrEmpty() && countsAttendance(byId[it.course_id]) }
            val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val today = tracked.filter { Instant.ofEpochMilli(it.start_at).atZone(zone).toLocalDate() == date }
            val sessions = today.ifEmpty { listOfNotNull(tracked.firstOrNull { it.start_at > now }) }
            val open = data.homework.filterNot { it.done }
            val groups = groupFamilies(data.courses)
            return AttendanceState(data, now, zone, stats.overall, cards, unmarked, sessions, today.isNotEmpty(),
                open.filter { it.due_at != null && it.due_at < now + 7 * 86400000L }.take(6), open.size,
                if (groups.any { g -> g.courses.any { it.tracked } }) emptyList() else groups.map { it.family })
        }
    }
}
