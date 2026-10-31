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
    val allEvents: List<com.knhdixh.attendance80.net.EventDto>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val session: String? = null,
    val lastSynced: Long? = null,
)

class ShellViewModel(private val container: AppContainer) : ViewModel() {
    val state = combine(container.dataRepository.sessionSnapshot, container.calendarScope.selected,
        container.dataRepository.loading, container.dataRepository.error) { owned, selected, loading, error ->
        ShellState(data = owned.second?.let { container.calendarScope.apply(it, selected) }, selected = selected, allEvents = owned.second?.events, loading = loading, error = error, session = owned.first, lastSynced = owned.third)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellState())

    private val recentsStore = com.knhdixh.attendance80.data.PaletteRecents(container.context)
    val recents = recentsStore.ids.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun recordRecent(id: String) { viewModelScope.launch { recentsStore.record(id) } }

    val clock get() = container.clock

    fun refresh() { viewModelScope.launch { container.dataRepository.refresh() } }
    fun consumeError(message: String, owner: String?) {
        if (container.dataRepository.sessionSnapshot.value.first == owner) container.dataRepository.consumeError(message)
    }
    suspend fun validatePendingSession(): Boolean {
        val token = container.tokenStore.currentToken() ?: return false
        val refreshed = container.dataRepository.refresh()
        return refreshed && container.tokenStore.currentToken() == token
    }
    fun dispatch(action: PaletteAction) = dispatch(action, container.dataRepository.sessionSnapshot.value.first)
    fun dispatch(action: PaletteAction, owner: String?) { val submittedOwner = owner; viewModelScope.launch {
        if (submittedOwner == null) return@launch
        val repo = container.dataRepository
        val api = container.api
        when (action) {
            is PaletteAction.CourseField -> {
                val change: (com.knhdixh.attendance80.net.AppData) -> com.knhdixh.attendance80.net.AppData = { d ->
                    d.copy(courses = d.courses.map { c -> if (c.id != action.id) c else when (action.field) {
                        "tracked" -> c.copy(tracked = action.value == "true")
                        "tag" -> c.copy(tag = action.value)
                        "recording_url" -> c.copy(recording_url = action.value.trim().ifEmpty { null })
                        else -> c
                    } })
                }
                val body = buildJsonObject { if (action.field == "tracked") put(action.field, action.value == "true") else put(action.field, action.value) }
                repo.mutate(change, { api.updateCourse(action.id, body) }, { d, _ -> d }, reload = true, expectedOwner = submittedOwner)
            }
            is PaletteAction.CourseGroups -> {
                val updates = action.ids.map { com.knhdixh.attendance80.net.CourseTrackedUpdate(it, it == action.selected) }
                repo.mutate({ d -> d.copy(courses = d.courses.map { c -> updates.find { it.id == c.id }?.let { c.copy(tracked = it.tracked) } ?: c }) },
                    { api.updateCoursesTracked(com.knhdixh.attendance80.net.CourseTrackedBulkRequest(updates)) }, { d, _ -> d }, reload = true, expectedOwner = submittedOwner)
            }
            is PaletteAction.BulkAttendance -> repo.mutate(
                optimistic = { d -> d.copy(events = d.events.map { if (it.id in action.ids) it.copy(status = action.status) else it }) },
                request = { api.bulkAttendance(buildJsonObject { put("ids", JsonArray(action.ids.map(::JsonPrimitive))); put("status", action.status?.let(::JsonPrimitive) ?: JsonNull) }) },
                apply = { d, _ -> d }, reload = true, expectedOwner = submittedOwner)
            is PaletteAction.SessionField -> {
                if (action.field !in setOf("tag", "note")) return@launch
                val owner = submittedOwner ?: return@launch
                repo.mutate({ d -> d.copy(events = d.events.map { e ->
                    if (e.id != action.id) e else if (action.field == "tag") e.copy(tag = action.value) else e.copy(note = action.value.orEmpty().take(2000))
                }) }, { api.updateAttendance(action.id, buildJsonObject {
                    put(action.field, action.value?.let(::JsonPrimitive) ?: JsonNull)
                }) }, { d, _ -> d }, expectedOwner = owner)
            }
            is PaletteAction.SessionStatus -> {
                val owned = repo.sessionSnapshot.value
                val owner = submittedOwner ?: return@launch
                if (owned.first != owner) return@launch
                val event = owned.second?.events?.find { it.id == action.id } ?: return@launch
                repo.mutate({ d -> d.copy(events = d.events.map { if (it.id == action.id) it.copy(status = action.status) else it }) },
                    { api.updateAttendance(action.id, buildJsonObject { put("status", action.status?.let(::JsonPrimitive) ?: JsonNull) }) },
                    { d, _ -> d }, expectedOwner = owner)
                if (event.start_at <= clock.now() && repo.sessionSnapshot.value.first == owner) {
                    val existing = repo.sessionSnapshot.value.second?.homework?.find { it.kind == "catchup" && it.event_id == event.id }
                    if (action.status == "absent" && existing == null) {
                        repo.mutate({ it }, { api.createCatchup(com.knhdixh.attendance80.net.CatchupRequest(event.id, clock.zone().id)) },
                            { d, h -> d.copy(homework = (d.homework.filterNot { it.id == h.id } + h).sortedBy { it.due_at }) }, expectedOwner = owner, preserveError = true)
                    } else if (action.status != "absent" && existing != null && !existing.done && existing.created_at == existing.updated_at) {
                        repo.mutate({ it }, { api.deleteHomework(existing.id) },
                            { d, _ -> d.copy(homework = d.homework.filterNot { it.id == existing.id }) }, expectedOwner = owner, preserveError = true)
                    }
                }
            }
            is PaletteAction.SessionCatchup -> {
                val owned = repo.sessionSnapshot.value
                val owner = submittedOwner ?: return@launch
                if (owned.first != owner) return@launch
                val event = owned.second?.events?.find { it.id == action.id } ?: return@launch
                val existing = owned.second?.homework?.find { it.kind == "catchup" && it.event_id == event.id }
                if (action.remove && existing != null) repo.mutate({ it }, { api.deleteHomework(existing.id) },
                    { d, _ -> d.copy(homework = d.homework.filterNot { it.id == existing.id }) }, expectedOwner = owner)
                else if (!action.remove && existing == null) repo.mutate({ it },
                    { api.createCatchup(com.knhdixh.attendance80.net.CatchupRequest(event.id, clock.zone().id)) },
                    { d, h -> d.copy(homework = (d.homework.filterNot { it.id == h.id } + h).sortedBy { it.due_at }) }, expectedOwner = owner)
            }
            is PaletteAction.SyncCalendar -> repo.mutate({ it }, { api.syncCalendar(action.id) }, { d, _ -> d }, reload = true, expectedOwner = submittedOwner)
            is PaletteAction.ToggleHomework -> repo.mutate(
                { d -> d.copy(homework = d.homework.map { if (it.id == action.id) it.copy(done = action.done) else it }) },
                { api.updateHomework(action.id, buildJsonObject { put("done", action.done) }) },
                { d, h -> d.copy(homework = d.homework.map { if (it.id == h.id) h else it }) }, expectedOwner = submittedOwner)
            is PaletteAction.CheckHabit -> repo.mutate({ d -> d.copy(habits = d.habits.copy(logs = d.habits.logs.filterNot { it.habit_id == action.id && it.day == action.day } + com.knhdixh.attendance80.net.HabitLogDto(action.id, action.day, action.count))) }, { api.logHabit(action.id, HabitLogRequest(action.day, action.count)) },
                { d, log -> d.copy(habits = d.habits.copy(logs = d.habits.logs.filterNot { it.habit_id == log.habit_id && it.day == log.day } + log)) }, expectedOwner = submittedOwner)
            is PaletteAction.AddHabit -> {
                val parsed = Habits.parseHabit(action.input)
                val schedule = parsed.schedule ?: return@launch
                val body = buildJsonObject {
                    put("name", parsed.name); put("target", parsed.target); put("color", "blue")
                    parsed.icon?.let { put("icon", it) }; parsed.unit?.let { put("unit", it) }
                    put("schedule", buildJsonObject { put("type", schedule.type); schedule.n?.let { put("n", it) }; schedule.days?.let { put("days", JsonArray(it.map(::JsonPrimitive))) } })
                }
                repo.mutate({ it }, { api.createHabit(body) }, { d, h -> d.copy(habits = d.habits.copy(list = d.habits.list + h)) }, expectedOwner = submittedOwner)
            }
            is PaletteAction.AddCountdown -> {
                val parsed = Countdowns.parseCountdown(action.input, clock.now(), clock.zone()) ?: return@launch
                val body = buildJsonObject { put("title", parsed.title); put("at", parsed.at); put("all_day", parsed.all_day); put("repeat", parsed.repeat); put("after", parsed.after) }
                repo.mutate({ it }, { api.createCountdown(body) }, { d, c -> d.copy(countdowns = d.countdowns + c) }, expectedOwner = submittedOwner)
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
