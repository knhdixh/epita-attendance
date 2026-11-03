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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.knhdixh.attendance80.net.HomeworkDto
import com.knhdixh.attendance80.data.LocalEffects
import com.knhdixh.attendance80.data.Mutations
import com.knhdixh.attendance80.data.QueuedMutation

/** A delete waiting out its Undo window. It is already in the outbox (so closing the app can't lose it), held for [ShellViewModel.UNDO_MS]. */
data class PendingHomeworkDelete(val owner: String, val item: HomeworkDto, val sequence: Long, val rows: List<HomeworkDto> = listOf(item), val clientId: String = "")

data class ShellState(
    val data: com.knhdixh.attendance80.net.AppData? = null,
    val selected: List<Long> = emptyList(),
    val allEvents: List<com.knhdixh.attendance80.net.EventDto>? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val session: String? = null,
    val lastSynced: Long? = null,
)

/** What the Focus screen shows of the timer. */
data class FocusTimerState(val timer: com.knhdixh.attendance80.logic.FocusTimer, val settings: com.knhdixh.attendance80.logic.FocusSettings, val pendingTasks: List<String>)

class ShellViewModel(private val container: AppContainer) : ViewModel() {
    /** Null when there is no controller (tests that don't need one). */
    val focusTimer: kotlinx.coroutines.flow.StateFlow<FocusTimerState?> by lazy {
        val c = container.focusController; val store = container.focusStore
        if (c == null || store == null) MutableStateFlow<FocusTimerState?>(null).asStateFlow()
        else combine(c.timer, c.settings, store.pendingTasksFlow) { t, s, p -> FocusTimerState(t, s, p) as FocusTimerState? }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }
    val timerActions: com.knhdixh.attendance80.ui.focus.TimerActions? by lazy {
        val c = container.focusController; val store = container.focusStore
        if (c == null || store == null) null
        else com.knhdixh.attendance80.focus.ControllerActions(c, store, viewModelScope, com.knhdixh.attendance80.focus.WorkOutboxScheduler(container.context))
    }
    init { container.focusController?.let { c -> viewModelScope.launch { c.load() } } }
    val focusHistory = container.dataRepository.focusHistory
    suspend fun ensureFocusHistory(owner: String, since: Long) = container.dataRepository.ensureFocusHistory(owner, since)
    suspend fun saveFocusSettings(owner: String, body: JsonObject) = container.dataRepository.saveFocusSettings(owner, body)
    suspend fun saveFocusList(owner: String, body: JsonObject) = container.dataRepository.saveFocusSettingsCanonical(owner, body)
    suspend fun deleteFocus(owner: String, id: Long) = container.dataRepository.deleteFocus(owner, id)
    /** The calendar's writes: optimistic, put back with the server's message if it says no. */
    fun calendarActions(owner: String) = object : com.knhdixh.attendance80.ui.calendar.CalendarActions {
        private val repo get() = container.dataRepository
        override fun message(text: String) = repo.reportMessage(owner, text)
        override suspend fun setPlan(h: HomeworkDto, plannedAt: Long?, minutes: Int?): Boolean {
            var saved: HomeworkDto? = null
            val ok = repo.mutate({ d -> d.copy(homework = d.homework.map { if (it.id == h.id) it.copy(planned_at = plannedAt, planned_minutes = if (plannedAt == null) null else minutes) else it }) },
                { container.api.updateHomework(h.id, com.knhdixh.attendance80.ui.calendar.planBody(plannedAt, minutes)).also { saved = it } },
                { d, row -> d.copy(homework = d.homework.map { if (it.id == row.id) row else it }) }, expectedOwner = owner)
            saved?.sync_error?.takeIf { ok }?.let { message("Saved, but Google Calendar sync failed: $it") }
            return ok
        }
        override suspend fun keep(items: List<com.knhdixh.attendance80.ui.calendar.Proposal>): Boolean {
            val byId = items.associateBy { it.id }
            return repo.mutate({ d -> d.copy(homework = d.homework.map { h -> byId[h.id]?.let { h.copy(planned_at = it.start, planned_minutes = it.minutes) } ?: h }) },
                { container.api.planHomework(com.knhdixh.attendance80.ui.calendar.keepBody(items)) },
                { d, r -> val rows = r.homework.associateBy { it.id }; d.copy(homework = d.homework.map { rows[it.id] ?: it }) }, expectedOwner = owner)
        }
        override suspend fun create(body: JsonObject) = saveHomework(owner, null, body)
    }
    /** Habits: check-ins and edits, optimistic where the web is, put back with the server's message. */
    fun habitActions(owner: String) = object : com.knhdixh.attendance80.ui.habits.HabitActions {
        private val repo get() = container.dataRepository
        private val api get() = container.api
        override fun message(text: String) = repo.reportMessage(owner, text)
        private suspend fun q(m: QueuedMutation) = queue(owner, m)
        override suspend fun setCount(id: Long, day: String, count: Int) = q(Mutations.logHabit(id, day, count)) != null
        override suspend fun create(body: JsonObject): com.knhdixh.attendance80.net.HabitDto? {
            val temp = q(Mutations.createHabit(body))?.temp ?: return null
            return repo.snapshot.value?.habits?.list?.find { it.id == temp }?.also { message("Added “${it.name}” · ${Habits.scheduleLabel(it.schedule)}.") }
        }
        override suspend fun update(id: Long, body: JsonObject): com.knhdixh.attendance80.net.HabitDto? {
            q(Mutations.updateHabit(id, body)) ?: return null
            return repo.snapshot.value?.habits?.list?.find { it.id == id }
        }
        override suspend fun delete(id: Long) = q(Mutations.deleteHabit(id)) != null
        override suspend fun order(ids: List<Long>) = q(Mutations.orderHabits(ids)) != null
    }
    /** Countdowns: create, fine-tune (optimistic, as the web), pin and delete. */
    fun countdownActions(owner: String) = object : com.knhdixh.attendance80.ui.countdowns.CountdownActions {
        private val repo get() = container.dataRepository
        override fun message(text: String) = repo.reportMessage(owner, text)
        override suspend fun create(body: JsonObject): com.knhdixh.attendance80.net.CountdownDto? {
            var row: com.knhdixh.attendance80.net.CountdownDto? = null
            val ok = repo.mutate({ it }, { container.api.createCountdown(body).also { row = it } }, { d, c -> d.copy(countdowns = d.countdowns + c) }, expectedOwner = owner)
            return row?.takeIf { ok }
        }
        override suspend fun update(id: Long, body: JsonObject) = repo.mutate(
            { d -> d.copy(countdowns = d.countdowns.map { c -> if (c.id != id) c else com.knhdixh.attendance80.net.ApiClient.json.decodeFromJsonElement(com.knhdixh.attendance80.net.CountdownDto.serializer(),
                JsonObject(com.knhdixh.attendance80.net.ApiClient.json.encodeToJsonElement(com.knhdixh.attendance80.net.CountdownDto.serializer(), c).jsonObject + body)) }) },
            { container.api.updateCountdown(id, body) }, { d, row -> d.copy(countdowns = d.countdowns.map { if (it.id == row.id) row else it }) }, expectedOwner = owner)
        override suspend fun delete(id: Long) = repo.mutate({ it }, { container.api.deleteCountdown(id) }, { d, _ -> d.copy(countdowns = d.countdowns.filterNot { it.id == id }) }, expectedOwner = owner)
    }
    fun settingsActions(owner: String): com.knhdixh.attendance80.ui.settings.SettingsActions =
        com.knhdixh.attendance80.ui.settings.AccountSettingsActions(container, owner)
    /** Back from connecting Google Calendar in the browser (`com.knhdixh.attendance80:/gcal`). */
    fun gcalReturned(result: String?, error: String?) {
        val owner = container.dataRepository.sessionSnapshot.value.first ?: return
        val actions = settingsActions(owner)
        viewModelScope.launch {
            if (result == "connected") { actions.message("Google Calendar connected. Syncing…"); actions.gcalResync(quiet = true) }
            else if (error != null) { actions.message(com.knhdixh.attendance80.ui.settings.gcalErrorMessage(error)); container.dataRepository.refresh() }
        }
    }
    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
    /** Saving a break marks many sessions, so reload afterwards as the web does. */
    suspend fun saveBreak(owner: String, from: String, to: String, label: String): Boolean {
        val repo = container.dataRepository
        var marked = 0
        val success = repo.mutate({ it }, { container.api.createBreak(com.knhdixh.attendance80.net.BreakCreateRequest(from, to, label.trim(), clock.zone().id)).also { marked = it.sessions } },
            { d, r -> d.copy(breaks = d.breaks.filterNot { it.id == r.`break`.id } + r.`break`) }, reload = true, expectedOwner = owner)
        if (success) repo.reportMessage(owner, "Break saved: ${plural(marked, "session")} marked as planned absences.")
        return success
    }
    suspend fun removeBreak(owner: String, id: Long): Boolean {
        val repo = container.dataRepository
        var restored = 0
        val success = repo.mutate({ it }, { container.api.deleteBreak(id).also { restored = it.restored } },
            { d, _ -> d.copy(breaks = d.breaks.filterNot { it.id == id }) }, reload = true, expectedOwner = owner)
        if (success) repo.reportMessage(owner, "Break removed, ${plural(restored, "session")} restored.")
        return success
    }
    val state = combine(container.dataRepository.sessionSnapshot, container.calendarScope.selected,
        container.dataRepository.loading, container.dataRepository.error) { owned, selected, loading, error ->
        ShellState(data = owned.second?.let { container.calendarScope.apply(it, selected) }, selected = selected, allEvents = owned.second?.events, loading = loading, error = error, session = owned.first, lastSynced = owned.third)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellState())

    val uiPrefs get() = container.uiPrefs
    private val recentsStore = com.knhdixh.attendance80.data.PaletteRecents(container.context)
    val recents = recentsStore.ids.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun recordRecent(id: String) { viewModelScope.launch { recentsStore.record(id) } }

    val clock get() = container.clock

    private val deleting = MutableStateFlow<PendingHomeworkDelete?>(null)
    val pendingHomeworkDelete = deleting.asStateFlow()
    private var deleteJob: Job? = null
    private var enqueueJob: Job? = null
    private var deleteSequence = 0L

    /** Queues a change (applied now, sent in order, kept over a restart) and tries to send it at once. */
    private suspend fun queue(owner: String, m: QueuedMutation): com.knhdixh.attendance80.data.DataRepository.Queued? =
        container.dataRepository.enqueue(m, expectedOwner = owner)?.also { flushSoon() }
    private fun flushSoon() { viewModelScope.launch { container.dataRepository.flushQueue() } }

    suspend fun saveHomework(owner: String, id: Long?, body: JsonObject): Boolean =
        queue(owner, if (id == null) Mutations.createTask(body) else Mutations.updateTask(id, body)) != null

    /** Ticks a task off (or back). A repeating task moves on to its next date; the date the screen saw goes with it, so a retry moves it once. */
    private suspend fun toggleHomework(owner: String, id: Long, done: Boolean) {
        val rows = container.dataRepository.snapshot.value?.homework.orEmpty()
        val row = rows.find { it.id == id }
        val repeats = row?.recurrence != null
        val m = if (done) Mutations.completeTask(id, row?.due_date.takeIf { repeats }, reload = repeats || rows.any { it.parent_id == id })
        else Mutations.uncompleteTask(id, reload = repeats)
        queue(owner, m)
    }

    fun deleteHomework(owner: String, item: HomeworkDto) {
        val repo = container.dataRepository
        if (repo.sessionSnapshot.value.first != owner) return
        if (deleting.value?.let { it.owner == owner && it.item.id == item.id } == true) return
        finishHomeworkDelete(undo = false)
        // In the outbox from the start, held for the Undo window: closing the app within it doesn't bring the item back.
        val m = Mutations.deleteTask(item.id, UNDO_MS)
        val rows = repo.snapshot.value?.let { LocalEffects.subtree(it, item.id) }?.takeIf { it.isNotEmpty() } ?: listOf(item)
        val pending = PendingHomeworkDelete(owner, item, ++deleteSequence, rows, m.clientId)
        deleting.value = pending
        deleteJob = viewModelScope.launch { delay(UNDO_MS); finishHomeworkDelete(undo = false, expected = pending) }
        enqueueJob = viewModelScope.launch { if (queue(owner, m) == null && deleting.value == pending) deleting.value = null }
    }

    fun finishHomeworkDelete(undo: Boolean, expected: PendingHomeworkDelete? = null) {
        val pending = deleting.value ?: return
        if (expected != null && expected != pending) return
        deleting.value = null
        deleteJob?.cancel()
        deleteJob = null
        val repo = container.dataRepository
        val queued = enqueueJob   // finishing right after deleting must find the change in the outbox
        viewModelScope.launch {
            queued?.join()
            if (undo) repo.cancelQueued(pending.clientId) { d ->
                d.copy(homework = (d.homework + pending.rows.filter { r -> d.homework.none { it.id == r.id } }).sortedBy { it.due_at ?: Long.MAX_VALUE })
            } else if (repo.release(pending.clientId, pending.owner)) repo.flushQueue()
        }
    }

    companion object { const val UNDO_MS = 5000L }

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
            is PaletteAction.SyncCalendar -> settingsActions(submittedOwner).syncCalendar(action.id)
            is PaletteAction.ToggleHomework -> toggleHomework(submittedOwner, action.id, action.done)
            is PaletteAction.CheckHabit -> queue(submittedOwner, Mutations.logHabit(action.id, action.day, action.count))
            is PaletteAction.AddHabit -> {
                val parsed = Habits.parseHabit(action.input)
                val schedule = parsed.schedule ?: return@launch
                val body = buildJsonObject {
                    put("name", parsed.name); put("target", parsed.target); put("color", "blue")
                    parsed.icon?.let { put("icon", it) }; parsed.unit?.let { put("unit", it) }
                    put("schedule", buildJsonObject { put("type", schedule.type); schedule.n?.let { put("n", it) }; schedule.days?.let { put("days", JsonArray(it.map(::JsonPrimitive))) } })
                }
                queue(submittedOwner, Mutations.createHabit(body))
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
