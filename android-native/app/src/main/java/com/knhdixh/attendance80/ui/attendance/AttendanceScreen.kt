package com.knhdixh.attendance80.ui.attendance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.theme.*
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.round

private fun number(n: Double): String { val x = round(n * 10) / 10; return if (x == x.toLong().toDouble()) x.toLong().toString() else x.toString() }
private fun pct(n: Double) = "${number(n * 100)}%"
private fun skips(s: AttendanceStats, unit: String): String {
    val suffix = if (unit == "hours") " h" else ""
    return if (s.remaining < -1e-9) "over by ${number(ceil(-s.remaining * 2) / 2)}$suffix" else if (s.remainingDisplay > 0) "${number(s.remainingDisplay)}$suffix left" else "none left"
}

@Composable
fun AttendanceScreen(data: AppData?, now: Long, zone: ZoneId, onNavigate: (String) -> Unit, onAction: (PaletteAction) -> Unit, model: AttendanceViewModel = viewModel(), allEvents: List<com.knhdixh.attendance80.net.EventDto>? = null) {
    LaunchedEffect(data, now / 60000, zone) { model.update(data, now, zone) }
    val state by model.state.collectAsState()
    val s = state?.takeIf { it.data == data }
    if (s == null) { CircularProgressIndicator(Modifier.padding(16.dp)); return }
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    fun tone(level: String): Color = when (level) { "fail" -> if (dark) RedDark else RedLight; "risk" -> if (dark) OrangeDark else OrangeLight; else -> if (dark) GreenDark else GreenLight }
    val unit = s.data.user.unit
    val groups = courseCalendars(allEvents ?: s.data.events)
    val closest = s.courses.firstOrNull()
    val line = "${when (s.overall.level) { "fail" -> "Below"; "risk" -> "Close to"; else -> "On track for" }} ${pct(s.data.user.threshold)}." +
        (closest?.takeIf { it.stats.level != "ok" }?.let { " ${it.course.name}: ${skips(it.stats, unit)}." } ?: "")
    LazyColumn(Modifier.fillMaxSize().testTag("attendance-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.families.isNotEmpty()) item("groups") { Column { Text("Pick your groups for ${s.families.joinToString(", ")}."); TextButton(onClick = { onNavigate("courses") }) { Text("Choose groups") } } }
        item("overview") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stacked = maxWidth < 600.dp && LocalDensity.current.fontScale > 1.3f
                    if (stacked) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OverviewStat(pct(s.overall.projectedRate), "projected")
                            OverviewStat("${number(s.overall.absent)}/${number(s.overall.total)}", "absences")
                            OverviewStat("${s.unmarked.size}", "to mark")
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OverviewStat(pct(s.overall.projectedRate), "projected", Modifier.weight(1f))
                            OverviewStat("${number(s.overall.absent)}/${number(s.overall.total)}", "absences", Modifier.weight(1f))
                            OverviewStat("${s.unmarked.size}", "to mark", Modifier.weight(1f))
                        }
                    }
                }
                Text(line, color = tone(s.overall.level))
                if (s.unmarked.isNotEmpty()) Button(onClick = { onAction(PaletteAction.BulkAttendance(s.unmarked.map { it.id })) }) { Text("Mark ${s.unmarked.size} as attended") }
            }
        }
        if (s.sessions.isNotEmpty()) {
            item("sessions-heading") { SectionHeading(if (s.today) "Today" else "Next", "upcoming →") { onNavigate("upcoming") } }
            items(s.sessions, key = { "session:${it.id}" }) { e ->
                com.knhdixh.attendance80.ui.sessions.SessionRow(e, s.data, now, zone, onNavigate, onAction)
            }
        }
        if (s.homework.isNotEmpty()) {
            item("homework-heading") { SectionHeading("Homework", "all ${s.openHomework} →") { onNavigate("homework") } }
            items(s.homework, key = { "homework:${it.id}" }) { h ->
                Row(Modifier.fillMaxWidth()) {
                    Checkbox(false, { onAction(PaletteAction.ToggleHomework(h.id, true)) }, Modifier.semantics { contentDescription = "Complete ${h.title}" })
                    Column(Modifier.weight(1f).clickable { onNavigate("homework/${h.id}") }.padding(8.dp)) { Text(h.title); h.due_at?.let { Text(Dates.formatDate(it, zone), style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
        item("courses-heading") { Text("Courses", style = MaterialTheme.typography.titleMedium) }
        if (s.courses.isEmpty()) item("empty") { Text("No tracked courses.") }
        items(s.courses, key = { "course:${it.course.id}" }) { (course, stats) ->
            Column(Modifier.fillMaxWidth().clickable { onNavigate("course/${course.id}") }.padding(vertical = 8.dp).testTag("attendance-course-${course.id}"), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(course.name, style = MaterialTheme.typography.titleMedium)
                val label = groupLabel(groups[course.id], s.data.calendars)
                val chips = listOf(label, if (course.tag != "normal") TAGS[course.tag]?.label?.lowercase().orEmpty() else "").filter { it.isNotBlank() }
                if (chips.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    chips.forEach { chip -> Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(chip, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall)
                    } }
                }
                Text("${skips(stats, unit)}  ›", color = tone(stats.level), style = MaterialTheme.typography.titleLarge)
                Box(Modifier.fillMaxWidth().height(6.dp)) {
                    LinearProgressIndicator(progress = { if (stats.total == 0.0) 1f else ((stats.total - stats.absent) / stats.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), color = tone(stats.level))
                    val marker = MaterialTheme.colorScheme.onSurface
                    Canvas(Modifier.fillMaxSize().testTag("threshold-${course.id}")) {
                        val x = size.width * s.data.user.threshold.toFloat().coerceIn(0f, 1f)
                        drawLine(marker, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    }
                }
                Text("${pct(stats.projectedRate)} projected · ${number(stats.absent)} missed${if (stats.plannedAbsent > 0) ", ${number(stats.plannedAbsent)} planned" else ""} · ${stats.upcoming} upcoming", style = MaterialTheme.typography.bodySmall)
                Text("${number(stats.absent)} / ${number(stats.allowedAbsence)} ${if (unit == "hours") "h" else "absences allowed"}", style = MaterialTheme.typography.bodySmall)
                if (stats.unmarkedPast > 0) Text("${stats.unmarkedPast} unmarked", style = MaterialTheme.typography.bodySmall)
                if (stats.missedMustAttend.isNotEmpty()) Text("Missed ${stats.missedMustAttend.size} exam or mandatory", color = tone("fail"), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }
}

@Composable private fun SectionHeading(title: String, link: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(onClick = click) { Text(link) } }
}

@Composable private fun OverviewStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) { Text(value, style = MaterialTheme.typography.headlineMedium); Text(label) }
}
