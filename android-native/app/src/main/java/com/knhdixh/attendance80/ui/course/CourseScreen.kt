package com.knhdixh.attendance80.ui.course

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.luminance
import com.knhdixh.attendance80.ui.theme.*
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.round

data class CourseState(val data: AppData, val id: Long?, val now: Long, val zone: ZoneId,
    val course: CourseDto?, val events: List<EventDto>, val stats: AttendanceStats?, val family: GroupFamily?)

class CourseViewModel : ViewModel() {
    private val mutable = MutableStateFlow<CourseState?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0
    fun update(data: AppData?, id: Long?, now: Long, zone: ZoneId) {
        val minute = now / 60000 * 60000
        if (mutable.value?.let { it.data == data && it.id == id && it.now == minute && it.zone == zone } == true) return
        val version = ++generation
        if (mutable.value?.data != data || mutable.value?.id != id) mutable.value = null
        if (data == null) { mutable.value = null; return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { derive(data, id, minute, zone) }
            if (version == generation) mutable.value = result
        }
    }
    companion object {
        fun derive(data: AppData, id: Long?, now: Long, zone: ZoneId): CourseState {
            val course = data.courses.find { it.id == id }
            val events = data.events.filter { it.course_id == id }.sortedBy { it.start_at }
            val stats = course?.let { computeStats(events, data.courses.associateBy { c -> c.id }, data.user.threshold, data.user.unit, now) }
            return CourseState(data, id, now, zone, course, events, stats, groupFamilies(data.courses).find { f -> f.courses.any { it.id == id } })
        }
    }
}

private fun num(n: Double): String { val x = round(n * 10) / 10; return if (x == x.toLong().toDouble()) x.toLong().toString() else x.toString() }

@Composable
fun CourseScreen(data: AppData?, allEvents: List<EventDto>?, id: Long?, now: Long, zone: ZoneId,
    onNavigate: (String) -> Unit, onBack: () -> Unit, onAction: (PaletteAction) -> Unit,
    model: CourseViewModel = viewModel()) {
    LaunchedEffect(data, id, now / 60000, zone) { model.update(data, id, now, zone) }
    val derived by model.state.collectAsState()
    val state = derived?.takeIf { it.data == data && it.id == id }
    if (data == null) { Text("Loading…", Modifier.padding(16.dp)); return }
    if (state == null) { CircularProgressIndicator(Modifier.padding(16.dp)); return }
    val course = state.course
    if (course == null) { Column(Modifier.padding(16.dp)) { Text("Course not found."); TextButton(onClick = onBack) { Text("Back") } }; return }
    val stats = requireNotNull(state.stats)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val tone = when (stats.level) { "fail" -> if (dark) RedDark else RedLight; "risk" -> if (dark) OrangeDark else OrangeLight; else -> if (dark) GreenDark else GreenLight }
    val context = LocalContext.current
    val density = LocalDensity.current
    var edit by rememberSaveable(id) { mutableStateOf(false) }
    var url by rememberSaveable(id) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().testTag("course-detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("course") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(course.name, style = MaterialTheme.typography.headlineSmall)
                val label = groupLabel(courseCalendars(allEvents ?: data.events)[course.id], data.calendars)
                if (label.isNotBlank()) Text(label)
                if (!course.tracked) Text("Not tracked")
                if (!course.attendance) Text("Attendance not counted")
                Text("${num(data.user.threshold * 100)}% minimum · ${num(stats.projectedRate * 100)}% projected", color = tone)
                Text(if (stats.remaining < -1e-9) "over the absence limit" else "${num(stats.remainingDisplay)} ${if (data.user.unit == "hours") "hours" else "sessions"} left to skip", color = tone)
                Text("${num(stats.absent)} of max ${num(floor(stats.allowedAbsence * 10 + 1e-9) / 10)} absences used")
                if (stats.missedMustAttend.isNotEmpty()) Text("Missed ${stats.missedMustAttend.size} exam or mandatory", color = if (dark) RedDark else RedLight)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Track", Modifier.weight(1f)); Switch(course.tracked, { onAction(PaletteAction.CourseField(course.id, "tracked", it.toString())) }, Modifier.testTag("course-tracked"))
                }
                OutlinedButton(onClick = { menu = true }) { Text("Default tag: ${TAGS[course.tag]?.label ?: course.tag}") }
                state.family?.let { family ->
                    val chosen = family.courses.filter { it.tracked }
                    val selection = when (chosen.size) { 0 -> "Not in this course"; 1 -> chosen.first().group; else -> "Several groups" }
                    Text(family.family)
                    OutlinedButton(onClick = { groupMenu = true }) { Text(selection) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    course.recording_url?.let { link -> TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }.onFailure { Toast.makeText(context, "Could not open recordings.", Toast.LENGTH_SHORT).show() } }) { Text("Open recordings ↗") } }
                    TextButton(onClick = { url = course.recording_url.orEmpty(); edit = true }) { Text(if (course.recording_url == null) "Add link" else "Edit link") }
                }
                Text("Sessions", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (state.events.isEmpty()) item("empty") { Text("No sessions match.") }
        items(state.events, key = { it.id }) { event ->
            Column(Modifier.fillMaxWidth().clickable { onNavigate("session/${event.id}") }.padding(vertical = 8.dp)) {
                Text(event.summary)
                Text("${Dates.formatDate(event.start_at, zone)} · ${Dates.formatTime(event.start_at, zone)}–${Dates.formatTime(event.end_at, zone)}${if (event.location.isNotBlank()) " · ${event.location}" else ""}", style = MaterialTheme.typography.bodySmall)
                event.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
            }
        }
    }
    if (menu) AlertDialog(onDismissRequest = { menu = false }, properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(), title = { Text("Default tag") },
        text = { Column { listOf("normal", "mandatory", "exam", "optional").forEach { tag -> TextButton(onClick = { menu = false; onAction(PaletteAction.CourseField(course.id, "tag", tag)) }) { Text(TAGS[tag]?.label ?: tag) } } } },
        confirmButton = { TextButton(onClick = { menu = false }) { Text("Cancel") } })
    if (groupMenu) state.family?.let { family -> AlertDialog(onDismissRequest = { groupMenu = false }, properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(), title = { Text(family.family) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { TextButton(onClick = { groupMenu = false; onAction(PaletteAction.CourseGroups(family.courses.map { it.id }, null)) }) { Text("Not in this course") }
            family.courses.forEach { c -> TextButton(onClick = { groupMenu = false; onAction(PaletteAction.CourseGroups(family.courses.map { it.id }, c.id)) }) { Text(c.group) } } } },
        confirmButton = { TextButton(onClick = { groupMenu = false }) { Text("Cancel") } }) }
    if (edit) AlertDialog(onDismissRequest = { edit = false }, properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth(), title = { CompositionLocalProvider(LocalDensity provides density) { Text("Lecture recordings") } }, text = {
        CompositionLocalProvider(LocalDensity provides density) { Column(Modifier.verticalScroll(rememberScrollState())) { Text("Link to the lecture recordings of ${course.name}. New catch-ups link to it. Leave empty to remove.")
            Text("Recording URL")
            OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth().testTag("recording-url"), singleLine = true) } }
    }, confirmButton = { CompositionLocalProvider(LocalDensity provides density) { TextButton(onClick = { onAction(PaletteAction.CourseField(course.id, "recording_url", url.trim())); edit = false }) { Text("Save") } } },
        dismissButton = { CompositionLocalProvider(LocalDensity provides density) { TextButton(onClick = { edit = false }) { Text("Cancel") } } })
}
