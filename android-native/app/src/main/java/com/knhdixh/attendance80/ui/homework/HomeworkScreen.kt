package com.knhdixh.attendance80.ui.homework

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knhdixh.attendance80.logic.Dates
import com.knhdixh.attendance80.logic.courseCalendars
import com.knhdixh.attendance80.logic.groupLabel
import com.knhdixh.attendance80.logic.minutesByHomework
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.floor
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class HomeworkDraft(val title: String, val notes: String, val course: Long?, val date: String,
    val time: String, val reminder: Int?, val effort: Int?, val touched: Boolean = false)

private val reminders = listOf(null to "No reminder", 0 to "At the deadline", 30 to "30 min before", 60 to "1 hour before", 180 to "3 hours before", 1440 to "1 day before", 2880 to "2 days before", 10080 to "1 week before")
private val efforts = listOf(null to "Time: default", 15 to "15 min", 30 to "30 min", 60 to "1 h", 90 to "1.5 h", 120 to "2 h", 180 to "3 h", 300 to "5 h", 480 to "8 h", 720 to "12 h")
private fun webRound(value: Double) = floor(value + .5).toLong()
internal fun focusHours(minutes: Double): String = if (minutes < 60) "${webRound(minutes)} min" else "${(webRound(minutes / 6) / 10.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }} h"
internal fun homeworkDateDisplay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
internal fun homeworkTimeDisplay(time: LocalTime, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
internal fun homeworkDayDisplay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale))
private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"; today.plusDays(1) -> "Tomorrow"; today.minusDays(1) -> "Yesterday"
    else -> homeworkDayDisplay(date)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeworkRow(hw: HomeworkDto, data: AppData, allEvents: List<EventDto>, now: Long, zone: ZoneId,
    onNavigate: (String) -> Unit, onAction: (PaletteAction) -> Unit, onDelete: (HomeworkDto) -> Unit,
    onPending: (PaletteAction, String) -> Unit, extra: String? = null) {
    var confirm by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val density = LocalDensity.current
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = hw.due_date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: hw.due_at?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val due = day?.let { dayLabel(it, today) + if (hw.due_time != null && hw.due_at != null) " ${Dates.formatTime(hw.due_at, zone)}" else "" }
    val focused = minutesByHomework(data.focus.sessions)[hw.id] ?: 0.0
    val course = data.courses.find { it.id == hw.course_id }?.let { c ->
        listOf(c.name, groupLabel(courseCalendars(allEvents)[c.id], data.calendars)).filter { it.isNotBlank() && it != "All groups" }.joinToString(" · ")
    }
    val sync = if (data.integrations.gcal == null) null else when {
        hw.sync_error != null -> "not synced: ${hw.sync_error}"
        hw.gcal_event_id != null -> "synced"
        else -> "syncing"
    }
    val recording = if (hw.kind == "catchup") Regex("(?m)^Recording: (https?://\\S+)").find(hw.notes)?.groupValues?.get(1) else null
    Column(Modifier.fillMaxWidth().testTag("homework-row-${hw.id}")) {
        Row(Modifier.fillMaxWidth()) {
            Checkbox(hw.done, { onAction(PaletteAction.ToggleHomework(hw.id, it)) }, Modifier.semantics { contentDescription = "Done: ${hw.title}" })
            Column(Modifier.weight(1f).clickable { onNavigate("homework/${hw.id}") }.padding(vertical = 8.dp)) {
                Text(hw.title, style = MaterialTheme.typography.titleSmall)
                if (hw.notes.isNotBlank()) Text(hw.notes, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    due?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (hw.kind == "catchup") Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("catch-up", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    val details = listOfNotNull(extra, focused.takeIf { it >= 1 }?.let { "${focusHours(it)} focused" },
                        hw.remind_minutes?.takeIf { !hw.done }?.let { value -> reminders.find { it.first == value }?.second?.let { "reminder $it" } }, sync, course).joinToString(" · ")
                    if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (recording != null) TextButton(onClick = { uri.openUri(recording) }) { Text("recording ↗") }
            if (hw.planned_at != null && !hw.done) TextButton(onClick = { onPending(PaletteAction.OpenForm("calendar-show", input = hw.planned_at.toString()), "calendar") }) {
                val plannedDay = Instant.ofEpochMilli(hw.planned_at).atZone(zone).toLocalDate()
                val label = when (plannedDay) { today -> "today"; today.plusDays(1) -> "tomorrow"; else -> dayLabel(plannedDay, today) }
                Text("planned $label ${Dates.formatTime(hw.planned_at, zone)}")
            }
            if (!hw.done) TextButton(onClick = { onPending(PaletteAction.Focus("target", homeworkId = hw.id), "focus") }) { Text("Focus on this") }
            TextButton(onClick = { onNavigate("homework/${hw.id}") }) { Text("Edit") }
            TextButton(onClick = { confirm = true }) { Text("Delete") }
        }
        HorizontalDivider()
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, text = {
        CompositionLocalProvider(LocalDensity provides density) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { Text("Delete \"${hw.title}\"?") }
        }
    }, confirmButton = { CompositionLocalProvider(LocalDensity provides density) {
        TextButton(onClick = { confirm = false; onDelete(hw) }) { Text("Delete") }
    } }, dismissButton = { CompositionLocalProvider(LocalDensity provides density) {
        TextButton(onClick = { confirm = false }) { Text("Cancel") }
    } })
}

@Composable
fun HomeworkScreen(data: AppData?, allEvents: List<EventDto>?, now: Long, zone: ZoneId,
    onNavigate: (String) -> Unit, onAction: (PaletteAction) -> Unit, onDelete: (HomeworkDto) -> Unit,
    onPending: (PaletteAction, String) -> Unit, model: HomeworkViewModel = viewModel()) {
    val listState = rememberLazyListState()
    var doneOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(data, now, zone) { model.update(data, now, zone) }
    val state by model.state.collectAsState()
    val s = state?.takeIf { it.data == data && it.now == now && it.zone == zone }
    if (s == null) { CircularProgressIndicator(Modifier.padding(16.dp)); return }
    LazyColumn(Modifier.fillMaxSize().testTag("homework-list"), state = listState, contentPadding = PaddingValues(16.dp)) {
        item { TextButton(onClick = { onNavigate("homework/new") }) { Text("Add homework") } }
        if (s.data.homework.isEmpty()) item { Text("Nothing due. Press Q to add homework.") }
        val names = listOf(HomeworkBucket.Overdue to "Overdue", HomeworkBucket.Today to "Today", HomeworkBucket.Tomorrow to "Tomorrow", HomeworkBucket.Week to "Next 7 days", HomeworkBucket.Later to "Later")
        names.forEach { (bucket, label) ->
            val list = s.buckets.getValue(bucket)
            if (list.isNotEmpty()) {
                item("heading:$bucket") { Text("$label ${list.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                items(list, key = { "homework:${it.id}" }) { HomeworkRow(it, s.data, allEvents ?: s.data.events, now, zone, onNavigate, onAction, onDelete, onPending) }
            }
        }
        val done = s.buckets.getValue(HomeworkBucket.Done)
        if (done.isNotEmpty()) {
            item("done-heading") { TextButton(onClick = { doneOpen = !doneOpen }) { Text("Done ${done.size} ${if (doneOpen) "▴" else "▾"}") } }
            if (doneOpen) items(done, key = { "done:${it.id}" }) { HomeworkRow(it, s.data, allEvents ?: s.data.events, now, zone, onNavigate, onAction, onDelete, onPending) }
        }
    }
}

@Composable
private fun <T> Choice(label: String, value: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${options.find { it.first == value }?.second.orEmpty()}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEach { (v, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(v) }) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeworkForm(data: AppData?, id: Long?, now: Long, zone: ZoneId, initialInput: String,
    onBack: () -> Unit, onSave: suspend (Long?, JsonObject) -> Boolean, lastCourse: Long?, onLastCourse: (Long?) -> Unit,
    storedDraft: String? = null, onDraft: (String) -> Unit = {}) {
    if (data == null) { CircularProgressIndicator(Modifier.padding(16.dp)); return }
    val original = id?.let { wanted -> data.homework.find { it.id == wanted } }
    if (id != null && original == null) { Column(Modifier.padding(16.dp)) { Text("Homework not found."); TextButton(onClick = onBack) { Text("Back") } }; return }
    val draft = remember(id) { storedDraft?.let { runCatching { ApiClient.json.decodeFromString<HomeworkDraft>(it) }.getOrNull() } }
    var title by remember(id) { mutableStateOf(draft?.title ?: original?.title ?: initialInput) }
    var draftTouched by remember(id) { mutableStateOf(draft?.touched ?: (original != null || initialInput.isNotEmpty())) }
    LaunchedEffect(initialInput) {
        if (id == null && !draftTouched && title.isEmpty() && initialInput.isNotEmpty()) { title = initialInput; draftTouched = true }
    }
    var notes by remember(id) { mutableStateOf(draft?.notes ?: original?.notes.orEmpty()) }
    var course by remember(id) { mutableStateOf(if (draft != null) draft.course else original?.course_id ?: lastCourse.takeIf { id == null }) }
    var date by remember(id) { mutableStateOf(draft?.date ?: original?.due_date ?: Instant.ofEpochMilli(now + 86_400_000).atZone(zone).toLocalDate().toString()) }
    var time by remember(id) { mutableStateOf(draft?.time ?: original?.due_time.orEmpty()) }
    var reminder by remember(id) { mutableStateOf(if (draft != null) draft.reminder else if (original == null) 1440 else original.remind_minutes) }
    var effort by remember(id) { mutableStateOf(if (draft != null) draft.effort else original?.effort_minutes) }
    LaunchedEffect(title, notes, course, date, time, reminder, effort, draftTouched) {
        onDraft(ApiClient.json.encodeToString(HomeworkDraft(title, notes, course, date, time, reminder, effort, draftTouched)))
    }
    var saving by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val titleFocus = remember { FocusRequester() }
    val titleBringIntoView = remember { BringIntoViewRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusGeneration by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, focusGeneration) {
        if (id == null) {
            titleFocus.requestFocus()
            keyboard?.show()
            titleBringIntoView.bringIntoView()
        }
    }
    val context = LocalContext.current
    val pickerTheme = if (MaterialTheme.colorScheme.background.luminance() < .5f) com.knhdixh.attendance80.R.style.HomeworkPickerDark else com.knhdixh.attendance80.R.style.HomeworkPickerLight
    val courseChoices = listOf(null to "No course") + data.courses.filter { it.tracked || it.id == original?.course_id }.map { it.id as Long? to it.name }
    val sessions = homeworkSessions(data.events, course, now)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(title, { title = it.take(200); draftTouched = true }, Modifier.fillMaxWidth().focusRequester(titleFocus).bringIntoViewRequester(titleBringIntoView), label = { Text("What's due") }, singleLine = true)
        OutlinedTextField(notes, { notes = it.take(4000); draftTouched = true }, Modifier.fillMaxWidth(), label = { Text("Notes") })
        val civilDate = runCatching { LocalDate.parse(date) }.getOrElse { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }
        OutlinedButton(onClick = {
            DatePickerDialog(context, pickerTheme, { _, year, month, day -> date = LocalDate.of(year, month + 1, day).toString(); draftTouched = true },
                civilDate.year, civilDate.monthValue - 1, civilDate.dayOfMonth).show()
        }, modifier = Modifier.fillMaxWidth().testTag("homework-date")) { Text("Due date: ${homeworkDateDisplay(civilDate)}") }
        val selectedTime = runCatching { LocalTime.parse(time) }.getOrNull()
        OutlinedButton(onClick = {
            val initial = selectedTime ?: LocalTime.of(9, 0)
            TimePickerDialog(context, pickerTheme, { _, hour, minute -> time = LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)); draftTouched = true },
                initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
        }, modifier = Modifier.fillMaxWidth().testTag("homework-time")) { Text("Due time: ${selectedTime?.let { homeworkTimeDisplay(it) } ?: "No time"}") }
        if (selectedTime != null) TextButton(onClick = { time = ""; draftTouched = true }) { Text("Clear time") }
        Text("No time means end of day.")
        Choice("Course", course, courseChoices) { course = it; draftTouched = true }
        Choice("Due at a session", null as Long?, listOf(null to if (course == null) "Pick a course first" else if (sessions.isEmpty()) "No upcoming sessions" else "— or pick a session —") + sessions.map { it.start_at as Long? to "${Dates.formatDate(it.start_at, zone)} ${Dates.formatTime(it.start_at, zone)}" }) { ms ->
            if (ms != null) { val local = Instant.ofEpochMilli(ms).atZone(zone); date = local.toLocalDate().toString(); time = local.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")); draftTouched = true }
        }
        Choice("Reminder", reminder, reminders) { reminder = it; draftTouched = true }
        Choice("Time it needs", effort, if (efforts.any { it.first == effort }) efforts else efforts + (effort to "$effort min")) { effort = it; draftTouched = true }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Cancel") }
            Button(enabled = !saving, onClick = {
                val body = buildJsonObject {
                    put("title", title); put("notes", notes); put("course_id", course?.let(::JsonPrimitive) ?: JsonNull)
                    put("due_date", date); put("due_time", time.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull); put("tz", zone.id)
                    put("remind_minutes", reminder?.let(::JsonPrimitive) ?: JsonNull); put("effort_minutes", effort?.let(::JsonPrimitive) ?: JsonNull)
                }
                saving = true
                scope.launch {
                    val success = onSave(id, body)
                    withContext(Dispatchers.Main.immediate) {
                        saving = false
                        if (success) { onLastCourse(course); if (id != null) onBack() else { title = ""; notes = ""; date = Instant.ofEpochMilli(now + 86_400_000).atZone(zone).toLocalDate().toString(); time = ""; reminder = 1440; effort = null; draftTouched = false; focusGeneration++ } }
                    }
                }
            }) { Text(if (id == null) "Add homework" else "Save") }
        }
    }
}
