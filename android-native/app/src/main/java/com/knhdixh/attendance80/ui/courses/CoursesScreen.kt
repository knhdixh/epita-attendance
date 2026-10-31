package com.knhdixh.attendance80.ui.courses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.course.CourseControls
import com.knhdixh.attendance80.ui.course.GroupSelector
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.theme.*
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

data class CoursesRow(val course: CourseDto, val stats: AttendanceStats, val count: Int, val hours: Double)
data class CoursesState(val data: AppData, val now: Long, val zone: ZoneId, val families: List<GroupFamily>, val courses: List<CoursesRow>)

class CoursesViewModel : ViewModel() {
    private val mutable = MutableStateFlow<CoursesState?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0
    fun update(data: AppData?, now: Long, zone: ZoneId) {
        val minute = now / 60000 * 60000
        if (mutable.value?.let { it.data == data && it.now == minute && it.zone == zone } == true) { generation++; return }
        val version = ++generation
        if (mutable.value?.data != data) mutable.value = null
        if (data == null) { mutable.value = null; return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { derive(data, minute, zone) }
            if (version == generation) mutable.value = result
        }
    }
    companion object {
        fun derive(data: AppData, now: Long, zone: ZoneId): CoursesState {
            val events = data.events.groupBy { it.course_id }
            val rows = statsByCourse(data.courses, data.events, data.user.threshold, data.user.unit, now).perCourse.map { row ->
                val sessions = events[row.course.id].orEmpty()
                CoursesRow(row.course, row.stats, sessions.size, sessions.sumOf { (it.end_at - it.start_at) / 3600000.0 })
            }
            return CoursesState(data, now, zone, groupFamilies(data.courses), rows)
        }
    }
}

private fun number(value: Double): String {
    val n = floor(value * 10 + 0.5) / 10
    return if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoursesScreen(data: AppData?, allEvents: List<EventDto>?, now: Long, zone: ZoneId,
    onNavigate: (String) -> Unit, onAction: (PaletteAction) -> Unit, model: CoursesViewModel = viewModel()) {
    LaunchedEffect(data, now / 60000, zone) { model.update(data, now, zone) }
    val derived by model.state.collectAsState()
    val state = derived?.takeIf { it.data == data && it.now == now / 60000 * 60000 && it.zone == zone }
    if (data == null) { Text("Loading…", Modifier.padding(16.dp)); return }
    if (state == null) { CircularProgressIndicator(Modifier.padding(16.dp)); return }
    val grouped = state.families.flatMap { it.courses }.map { it.id }.toSet()
    val memberships = courseCalendars(allEvents ?: data.events)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    LazyColumn(Modifier.fillMaxSize().testTag("courses-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.families.isNotEmpty()) {
            item("groups-heading") {
                Column { Text("Your groups", style = MaterialTheme.typography.titleMedium)
                    Text("Pick the group you belong to for each group-split course. Other groups' sessions are ignored.", style = MaterialTheme.typography.bodySmall) }
            }
            items(state.families, key = { "family:${it.family}" }) { family -> GroupSelector(family, onAction) }
        }
        item("courses-heading") { Column {
            Text("All courses", style = MaterialTheme.typography.titleMedium)
            Text("Untracked courses don't count toward attendance. The tag applies to every session of the course unless a session overrides it.\nExam/Mandatory: counted, and flagged if you skip them. Optional: never counted.", style = MaterialTheme.typography.bodySmall)
        } }
        if (state.courses.isEmpty()) item("empty") { Text("Add your Zeus link to keep it up to date automatically, or upload an .ics file. You can add more calendars later in Settings.") }
        items(state.courses, key = { "course:${it.course.id}" }) { item ->
            val c = item.course
            val label = groupLabel(memberships[c.id], data.calendars)
            val tone = when (item.stats.level) { "fail" -> if (dark) RedDark else RedLight; "risk" -> if (dark) OrangeDark else OrangeLight; else -> if (dark) GreenDark else GreenLight }
            Column(Modifier.fillMaxWidth().testTag("course-row-${c.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.name, Modifier.fillMaxWidth().clickable { onNavigate("course/${c.id}") }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                val labels = listOfNotNull(label.takeIf { it.isNotBlank() }, "group split".takeIf { c.id in grouped }, "no attendance".takeIf { !c.attendance })
                if (labels.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    labels.forEach { label -> Text(label, Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${item.count} sessions · ${number(item.hours)} hours ·", style = MaterialTheme.typography.bodySmall)
                    Text("Projected ${if (countsAttendance(c)) "${number(item.stats.projectedRate * 100)}%" else "—"}", style = MaterialTheme.typography.bodySmall, color = if (countsAttendance(c)) tone else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CourseControls(c, null, onAction)
                HorizontalDivider()
            }
        }
    }
}
