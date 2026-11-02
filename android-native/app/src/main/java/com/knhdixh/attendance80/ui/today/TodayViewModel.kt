package com.knhdixh.attendance80.ui.today

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

data class DueHabit(val habit: HabitDto, val stats: Habits.Stats)
data class TodayState(val data: AppData, val plan: DayPlan, val habits: List<DueHabit>, val countdowns: List<Countdowns.Sorted<CountdownDto>>, val focus: FocusStatsResult, val day: String)

class TodayViewModel : ViewModel() {
    private val mutable = MutableStateFlow<TodayState?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0
    fun update(data: AppData?, now: Long, zone: ZoneId) {
        val version = ++generation
        if (data == null) { mutable.value = null; return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { derive(data, now / 60000 * 60000, zone) }
            if (version == generation) mutable.value = result
        }
    }
    companion object {
        fun derive(data: AppData, now: Long, zone: ZoneId): TodayState {
            val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
            val habits = data.habits.list.filterNot { it.archived }.map { h ->
                val counts = data.habits.logs.filter { it.habit_id == h.id }.associate { it.day to it.count }
                val start = Instant.ofEpochMilli(h.created_at).atZone(zone).toLocalDate().toString()
                DueHabit(h, Habits.habitStats(h, counts, day, start))
            }.filter { it.stats.due }
            return TodayState(data, buildPlan(data.courses, data.events, data.homework, now, zone.id), habits,
                Countdowns.sortCountdowns(data.countdowns.filter { it.pinned }, now, zone).filter { it.v.state != "past" },
                focusStats(data.focus.sessions, now, zoneId = zone), day)
        }
    }
}
