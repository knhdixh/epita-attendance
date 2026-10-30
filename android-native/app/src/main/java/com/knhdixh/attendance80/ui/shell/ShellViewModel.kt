package com.knhdixh.attendance80.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.knhdixh.attendance80.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.logic.Habits
import com.knhdixh.attendance80.logic.Countdowns
import com.knhdixh.attendance80.net.HabitLogRequest
import kotlinx.serialization.json.*

data class ShellState(
    val data: com.knhdixh.attendance80.net.AppData? = null,
    val selected: List<Long> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val session: String? = null,
    val lastSynced: Long? = null,
)

class ShellViewModel(private val container: AppContainer) : ViewModel() {
    val state = combine(container.dataRepository.sessionSnapshot, container.calendarScope.selected,
        container.dataRepository.loading, container.dataRepository.error) { owned, selected, loading, error ->
        ShellState(owned.second?.let { container.calendarScope.apply(it, selected) }, selected, loading, error, owned.first, owned.third)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellState())

    val clock get() = container.clock

    fun refresh() { viewModelScope.launch { container.dataRepository.refresh() } }
    suspend fun validatePendingSession(): Boolean {
        val token = container.tokenStore.currentToken() ?: return false
        val refreshed = container.dataRepository.refresh()
        return refreshed && container.tokenStore.currentToken() == token
    }
    fun dispatch(action: PaletteAction) { viewModelScope.launch {
        val repo = container.dataRepository
        val api = container.api
        when (action) {
            is PaletteAction.BulkAttendance -> repo.mutate(
                optimistic = { d -> d.copy(events = d.events.map { if (it.id in action.ids) it.copy(status = "present") else it }) },
                request = { api.bulkAttendance(buildJsonObject { put("ids", JsonArray(action.ids.map(::JsonPrimitive))); put("status", "present") }) },
                apply = { d, _ -> d }, reload = true)
            is PaletteAction.SyncCalendar -> repo.mutate({ it }, { api.syncCalendar(action.id) }, { d, _ -> d }, reload = true)
            is PaletteAction.CheckHabit -> repo.mutate({ d -> d.copy(habits = d.habits.copy(logs = d.habits.logs.filterNot { it.habit_id == action.id && it.day == action.day } + com.knhdixh.attendance80.net.HabitLogDto(action.id, action.day, action.count))) }, { api.logHabit(action.id, HabitLogRequest(action.day, action.count)) },
                { d, log -> d.copy(habits = d.habits.copy(logs = d.habits.logs.filterNot { it.habit_id == log.habit_id && it.day == log.day } + log)) })
            is PaletteAction.AddHabit -> {
                val parsed = Habits.parseHabit(action.input)
                val schedule = parsed.schedule ?: return@launch
                val body = buildJsonObject {
                    put("name", parsed.name); put("target", parsed.target); put("color", "blue")
                    parsed.icon?.let { put("icon", it) }; parsed.unit?.let { put("unit", it) }
                    put("schedule", buildJsonObject { put("type", schedule.type); schedule.n?.let { put("n", it) }; schedule.days?.let { put("days", JsonArray(it.map(::JsonPrimitive))) } })
                }
                repo.mutate({ it }, { api.createHabit(body) }, { d, h -> d.copy(habits = d.habits.copy(list = d.habits.list + h)) })
            }
            is PaletteAction.AddCountdown -> {
                val parsed = Countdowns.parseCountdown(action.input, clock.now(), clock.zone()) ?: return@launch
                val body = buildJsonObject { put("title", parsed.title); put("at", parsed.at); put("all_day", parsed.all_day); put("repeat", parsed.repeat); put("after", parsed.after) }
                repo.mutate({ it }, { api.createCountdown(body) }, { d, c -> d.copy(countdowns = d.countdowns + c) })
            }
            else -> Unit
        }
    } }
    fun toggleScope(value: String) {
        val calendars = container.dataRepository.snapshot.value?.calendars ?: return
        viewModelScope.launch { container.calendarScope.toggle(value, calendars) }
    }
    fun signOut() { viewModelScope.launch { container.authRepository.logout() } }
}
