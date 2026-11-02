package com.knhdixh.attendance80.ui.sessions

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

data class SessionFilter(val course: Long? = null, val whenFilter: String = "upcoming", val query: String = "", val showHidden: Boolean = false)
data class SessionsState(val data: AppData, val now: Long, val zone: ZoneId, val filter: SessionFilter, val events: List<EventDto>, val summary: AttendanceStats?)
class SessionsViewModel : ViewModel() {
    private val mutable = MutableStateFlow<SessionsState?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0
    fun update(data: AppData?, now: Long, zone: ZoneId, filter: SessionFilter) {
        val minute = now / 60000 * 60000
        if (mutable.value?.let { it.data == data && it.now == minute && it.zone == zone && it.filter == filter } == true) { generation++; return }
        val version = ++generation
        if (data == null) { mutable.value = null; return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { derive(data, minute, zone, filter) }
            if (generation == version) mutable.value = result
        }
    }
    companion object {
        fun derive(data: AppData, now: Long, zone: ZoneId, filter: SessionFilter): SessionsState {
            val courses = data.courses.associateBy { it.id }
            var events = data.events.sortedBy { it.start_at }.filter { e ->
                if (filter.course != null) e.course_id == filter.course else filter.showHidden || courses[e.course_id]?.tracked == true
            }
            events = events.filter { e -> when (filter.whenFilter) {
                "upcoming" -> e.end_at > now
                "unmarked" -> e.start_at <= now && e.status == null
                "past" -> e.start_at <= now
                "skips" -> e.status == "absent"
                "mustattend" -> TAGS[effectiveTag(e, courses[e.course_id])]?.mustAttend == true
                else -> true
            } && "${e.summary} ${e.location} ${e.note}".contains(filter.query, ignoreCase = true) }
            if (filter.whenFilter == "past") events = events.reversed()
            val summary = filter.course?.let { id -> courses[id]?.let { computeStats(data.events.filter { e -> e.course_id == id }, courses, data.user.threshold, data.user.unit, now) } }
            return SessionsState(data, now, zone, filter, events, summary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SessionRow(event: EventDto, data: AppData, now: Long, zone: ZoneId, onNavigate: (String) -> Unit, onAction: (PaletteAction) -> Unit,
    selected: Boolean = false, openEnabled: Boolean = true, onSelect: (() -> Unit)? = null) {
    val course = data.courses.find { it.id == event.course_id }
    val tag = effectiveTag(event, course)
    val past = event.start_at <= now
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val tone = when (event.status) {
        "present" -> if (dark) GreenDark else GreenLight
        "absent" -> if (past) { if (dark) RedDark else RedLight } else { if (dark) OrangeDark else OrangeLight }
        "excused" -> if (dark) PurpleDark else PurpleLight
        else -> MaterialTheme.colorScheme.onSurface
    }
    val catchup = data.homework.find { it.kind == "catchup" && it.event_id == event.id }
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        val status = when (value) { SwipeToDismissBoxValue.StartToEnd -> "present"; SwipeToDismissBoxValue.EndToStart -> "absent"; else -> null }
        if (status != null && countsAttendance(course)) onAction(PaletteAction.SessionStatus(event.id, if (event.status == status) null else status))
        false
    })
    SwipeToDismissBox(state = swipe, enableDismissFromStartToEnd = countsAttendance(course), enableDismissFromEndToStart = countsAttendance(course), backgroundContent = {
        Text(if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) "Attended" else "Absent", Modifier.padding(16.dp).clearAndSetSemantics { })
    }) { Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).testTag("session-row-${event.id}")) {
        Column(Modifier.fillMaxWidth().then(if (openEnabled) Modifier.combinedClickable(onClick = { if (selected && onSelect != null) onSelect() else onNavigate("session/${event.id}") }, onLongClick = onSelect) else Modifier).padding(vertical = 8.dp)) {
            Text(event.summary, color = tone, style = MaterialTheme.typography.titleSmall)
            Text("${Dates.formatDate(event.start_at, zone)} · ${Dates.formatTime(event.start_at, zone)}–${Dates.formatTime(event.end_at, zone)}${if (event.location.isNotBlank()) " · ${event.location}" else ""}", style = MaterialTheme.typography.bodySmall)
            val memberships = event.cals.mapNotNull { id -> data.calendars.find { it.id == id }?.name }
            if (memberships.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                memberships.forEach { name -> Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(name, Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall) } }
            }
            if (event.note.isNotBlank()) Text(event.note, style = MaterialTheme.typography.bodySmall)
            Text(TAGS[tag]?.label ?: tag, style = MaterialTheme.typography.labelSmall)
            if (TAGS[tag]?.mustAttend == true) Text("Exam or mandatory", color = if (dark) RedDark else RedLight)
        }
        data.homework.filter { !it.done && it.course_id == event.course_id && it.due_at == event.start_at }.forEach { h ->
            TextButton(onClick = { onNavigate("homework/${h.id}") }) { Text(h.title) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onSelect != null) TextButton(onClick = onSelect) { Text(if (selected) "Deselect" else "Select") }
            if (countsAttendance(course)) listOf("present", "absent", "excused").forEach { status ->
                val label = when (status) { "present" -> if (past) "Attended" else "Will attend"; "absent" -> if (past) "Absent" else "Plan to skip"; else -> "Excused" }
                val selectedTone = when (status) { "present" -> if (dark) GreenDark else GreenLight; "absent" -> if (past) { if (dark) RedDark else RedLight } else { if (dark) OrangeDark else OrangeLight }; else -> if (dark) PurpleDark else PurpleLight }
                FilterChip(selected = event.status == status, onClick = { onAction(PaletteAction.SessionStatus(event.id, if (event.status == status) null else status)) }, label = { Text(label) }, colors = FilterChipDefaults.filterChipColors(selectedLabelColor = selectedTone, selectedContainerColor = selectedTone.copy(alpha = .16f)))
            }
            if (past) TextButton(onClick = { if (catchup != null) onNavigate("homework/${catchup.id}") else onAction(PaletteAction.SessionCatchup(event.id)) }) { Text(if (catchup != null) "Open catch-up" else "Add catch-up") }
        }
        HorizontalDivider()
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionsScreen(data: AppData?, now: Long, zone: ZoneId, filter: SessionFilter, onFilter: (SessionFilter) -> Unit, search: String?, consumeSearch: () -> Unit,
    onNavigate: (String) -> Unit, onAction: (PaletteAction) -> Unit, model: SessionsViewModel = viewModel()) {
    val course = filter.course
    val whenFilter = filter.whenFilter
    val query = filter.query
    val hidden = filter.showHidden
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(search) { if (search != null) { onFilter(filter.copy(query = search, whenFilter = "all")); consumeSearch() } }
    LaunchedEffect(data, now / 60000, zone, filter) { model.update(data, now, zone, filter) }
    val state by model.state.collectAsState()
    val listState = rememberLazyListState()
    val s = state
    LaunchedEffect(s?.events) { s?.let { selected = selected.intersect(it.events.map { event -> event.id }.toSet()) } }
    if (data == null || s == null) { CircularProgressIndicator(Modifier.padding(16.dp)); return }
    val allFamilies = groupFamilies(data.courses)
    val families = if (allFamilies.any { f -> f.courses.any { it.tracked } }) emptyList() else allFamilies
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    LazyColumn(Modifier.fillMaxSize().testTag("sessions-list"), listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (families.isNotEmpty()) item("groups") { Column { Text("Pick your groups for ${families.joinToString { it.family }}."); TextButton(onClick = { onNavigate("courses") }) { Text("Choose groups") } } }
        item("filters") { Column {
            OutlinedButton(onClick = { menu = true }) { Text(data.courses.find { it.id == course }?.name ?: if (hidden) "All courses" else "All tracked courses") }
            FlowRow { listOf("upcoming" to "Upcoming", "unmarked" to "To mark", "past" to "Past", "skips" to "Absences", "mustattend" to "Exams", "all" to "All").forEach { (key, label) -> FilterChip(selected = whenFilter == key, onClick = { onFilter(filter.copy(whenFilter = key)) }, label = { Text(label) }) } }
            OutlinedTextField(query, { onFilter(filter.copy(query = it)) }, label = { Text("Search") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row { Checkbox(hidden, { onFilter(filter.copy(showHidden = it)) }, Modifier.testTag("show-untracked").semantics { contentDescription = "Show untracked" }); Text("Show untracked", Modifier.padding(top = 12.dp)) }
            s.summary?.let { stats -> Text("${data.courses.find { it.id == course }?.name}: ${number(stats.projectedRate * 100)}% projected · ${if (stats.remaining < -1e-9) "over the absence limit" else "${unitLabel(stats.remainingDisplay, data.user.unit)} left to skip"} · ${number(stats.absent)} of max ${number(floor(stats.allowedAbsence * 10 + 1e-9) / 10)} absences used", color = when (stats.level) { "fail" -> if (dark) RedDark else RedLight; "risk" -> if (dark) OrangeDark else OrangeLight; else -> if (dark) GreenDark else GreenLight }) }
            if (whenFilter == "unmarked" && s.events.isNotEmpty()) TextButton(onClick = { onAction(PaletteAction.BulkAttendance(s.events.map { it.id })) }) { Text("Mark these ${s.events.size} as attended") }
            if (selected.isNotEmpty()) FlowRow { listOf("present" to "Present", "absent" to "Absent", "excused" to "Excused", null to "Clear").forEach { (status, label) -> TextButton(onClick = { onAction(PaletteAction.BulkAttendance(selected.toList(), status)); selected = emptySet() }) { Text(label) } } }
        } }
        if (s.events.isEmpty()) item("empty") { Text("No sessions match.") }
        val days = s.events.groupBy { Instant.ofEpochMilli(it.start_at).atZone(zone).toLocalDate() }
        days.forEach { (day, events) ->
            item("day:$day") { Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(day) + if (day == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) " · Today" else "", style = MaterialTheme.typography.titleSmall) }
            items(events, key = { "session:${it.id}" }) { e -> SessionRow(e, data, now, zone, onNavigate, onAction, e.id in selected) { selected = if (e.id in selected) selected - e.id else selected + e.id } }
        }
    }
    if (menu) {
        val density = LocalDensity.current
        AlertDialog(onDismissRequest = { menu = false }, properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
            title = { CompositionLocalProvider(LocalDensity provides density) { Text("Course") } },
            text = { CompositionLocalProvider(LocalDensity provides density) { Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { onFilter(filter.copy(course = null)); menu = false }) { Text("All courses") }
                data.courses.filter { it.tracked || hidden || it.id == course }.forEach { c -> TextButton(onClick = { onFilter(filter.copy(course = c.id)); menu = false }) { Text(c.name) } }
            } } }, confirmButton = { CompositionLocalProvider(LocalDensity provides density) { TextButton(onClick = { menu = false }) { Text("Cancel") } } })
    }
}
private fun number(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
internal fun unitLabel(value: Double, unit: String): String = "${number(value)} ${if (unit == "hours") "h" else if (value == 1.0) "session" else "sessions"}"

@Composable
fun SessionDetail(data: AppData?, id: Long?, now: Long, zone: ZoneId, onBack: () -> Unit, onNavigate: (String) -> Unit, onAction: (PaletteAction) -> Unit) {
    val event = data?.events?.find { it.id == id }
    if (data == null) { CircularProgressIndicator(); return }
    if (event == null) { Column(Modifier.padding(16.dp)) { Text("Session not found."); TextButton(onClick = onBack) { Text("Back") } }; return }
    var note by rememberSaveable(event.id) { mutableStateOf(event.note) }
    val density = LocalDensity.current
    AlertDialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false), modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        title = { CompositionLocalProvider(LocalDensity provides density) { Text("Session") } },
        text = { CompositionLocalProvider(LocalDensity provides density) { Column(Modifier.verticalScroll(rememberScrollState())) {
            SessionRow(event, data, now, zone, onNavigate, onAction, openEnabled = false)
            Text("Session tag")
            Text(if (event.tag == null) "Current: course default (${TAGS[effectiveTag(event, data.courses.find { it.id == event.course_id })]?.label ?: "Normal"})" else "Current: ${TAGS[event.tag]?.label ?: event.tag}", style = MaterialTheme.typography.bodySmall)
            listOf(null, "normal", "mandatory", "exam", "optional").forEach { tag -> TextButton(onClick = { onAction(PaletteAction.SessionField(event.id, "tag", tag)) }) { Text(if (tag == null) "Course default" else TAGS[tag]?.label ?: tag) } }
            OutlinedTextField(note, { note = it.take(2000) }, label = { Text("Note") })
            TextButton(onClick = { onAction(PaletteAction.SessionField(event.id, "note", note)) }) { Text("Save note") }
            data.homework.find { it.kind == "catchup" && it.event_id == event.id }?.let { TextButton(onClick = { onAction(PaletteAction.SessionCatchup(event.id, true)) }) { Text("Remove catch-up") } }
        } } }, confirmButton = { CompositionLocalProvider(LocalDensity provides density) { TextButton(onClick = onBack) { Text("Close") } } })
}
