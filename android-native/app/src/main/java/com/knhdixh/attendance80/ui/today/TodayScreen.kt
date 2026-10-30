package com.knhdixh.attendance80.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.knhdixh.attendance80.ui.theme.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knhdixh.attendance80.logic.Dates
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.ui.search.PaletteAction
import java.time.ZoneId
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun TodayScreen(data: AppData?, now: Long, zone: ZoneId, onNavigate: (String) -> Unit, onAction: (PaletteAction) -> Unit, model: TodayViewModel = viewModel()) {
    LaunchedEffect(data, now / 60000, zone) { model.update(data, now, zone) }
    val state by model.state.collectAsState()
    val s = state?.takeIf { it.data == data }
    if (data == null || s == null) { CircularProgressIndicator(Modifier.padding(16.dp)); return }
    val plan = s.plan
    val events = s.data.events.associateBy { it.id }
    val courses = s.data.courses.associateBy { it.id }
    fun time(t: Long) = Dates.formatTime(t, zone)
    fun number(n: Double) = if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
    fun hours(h: Double) = if (h < 1) "${(h * 60).roundToInt()} min" else "${number((h * 2).roundToInt() / 2.0)} h"
    fun due(h: com.knhdixh.attendance80.logic.PlanTodoItem): String {
        val date = h.due_date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: Instant.ofEpochMilli(h.due_at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(plan.now).atZone(zone).toLocalDate()
        val label = when(date) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; today.minusDays(1) -> "Yesterday"; else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date) }
        return label + if (h.due_time != null) " ${time(h.due_at)}" else ""
    }
    fun minutes(m: Int) = if (m < 60) "$m min" else "${number((m / 30.0).roundToInt() / 2.0)} h"
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    fun statusColor(status: String?): Color = when(status) {
        "present" -> if (dark) GreenDark else GreenLight
        "absent" -> if (dark) RedDark else RedLight
        "excused" -> if (dark) PurpleDark else PurpleLight
        else -> Color.Unspecified
    }
    val current = plan.current.firstOrNull()?.let(events::get)
    val next = plan.next?.let(events::get)?.takeIf { it.id in plan.todaySessions }
    val firstGap = plan.gaps.firstOrNull { it.fits.isNotEmpty() }
    val headline = buildList {
        current?.let { add("In ${courses[it.course_id]?.name ?: it.summary} until ${time(it.end_at)}.") }
        next?.let { add("Next: ${courses[it.course_id]?.name ?: it.summary} at ${time(it.start_at)}${if (it.location.isNotBlank()) ", ${it.location}" else ""}.") }
        if (next == null && current == null) add("No more classes today.")
        if (firstGap != null) add("${if (firstGap.start <= plan.now + 60000) "Now" else "Free from ${time(firstGap.start)}"}: ${firstGap.fits.first().title}.")
        else plan.todo.firstOrNull()?.takeIf { it.urgency == "overdue" }?.let { add("${it.title} is overdue.") }
    }.joinToString(" ")
    LazyColumn(Modifier.fillMaxSize().testTag("today-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("headline") { Text(headline) }
        items(s.countdowns, key = { "countdown:${it.c.id}" }) { c ->
            TodayRow("${if (c.v.state == "today") "Today" else c.v.parts} ${c.c.title}", when(c.v.state) { "future" -> "to go"; "up" -> "since"; else -> "" }) { onNavigate("countdowns/${c.c.id}") }
        }
        if (plan.week.clashes.isNotEmpty()) item("clashes") {
            val names = plan.week.clashes.take(3).mapNotNull { pair ->
                val a = events[pair[0]]; val b = events[pair[1]]
                if (a == null || b == null) null else "${courses[a.course_id]?.name ?: a.summary} and ${courses[b.course_id]?.name ?: b.summary} on ${Dates.formatDate(a.start_at, zone)} ${time(maxOf(a.start_at, b.start_at))}"
            }
            Text("${if (plan.week.clashes.size == 1) "Two sessions overlap" else "${plan.week.clashes.size} overlaps"} this week: ${names.joinToString("; ")}.", color = MaterialTheme.colorScheme.error)
        }
        item("todo-heading") { Heading("Do next", "all homework") { onNavigate("homework") } }
        if (plan.todo.isEmpty()) item("todo-empty") { Text("Nothing due in the next 7 days.") }
        items(plan.todo, key = { "todo:${it.id}" }) { h ->
            Row(Modifier.fillMaxWidth()) {
                Checkbox(checked = false, onCheckedChange = { onAction(PaletteAction.ToggleHomework(h.id, true)) }, modifier = Modifier.semantics { contentDescription = "Complete ${h.title}" })
                TodayRow(h.title, "${h.urgency} · ${due(h)} · start by ${Dates.formatDate(h.latestStart, zone)} ${time(h.latestStart)} · ${h.effort} min${h.course?.let { " · $it" } ?: ""}") { onNavigate("homework/${h.id}") }
            }
        }
        if (plan.later > 0) item("later") { Text("${plan.later} more due later.") }
        if (s.data.habits.list.any { !it.archived }) {
            item("habits-heading") { Heading("Habits${if (s.habits.isNotEmpty()) " ${s.habits.count { it.stats.todayDone }}/${s.habits.size}" else ""}", "all habits") { onNavigate("habits") } }
            if (s.habits.isEmpty()) item("habits-empty") { Text("No habits due today.") }
            items(s.habits, key = { "habit:${it.habit.id}" }) { h ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = h.stats.todayDone, onCheckedChange = { onAction(PaletteAction.CheckHabit(h.habit.id, s.day, if (h.stats.today >= h.habit.target) 0 else h.stats.today + 1)) }, modifier = Modifier.semantics { contentDescription = "Check ${h.habit.name}" })
                    TodayRow(h.habit.name, "${h.stats.today}/${h.habit.target}") { onNavigate("habits/${h.habit.id}") }
                }
            }
        }
        item("focus-heading") { Heading("Focus", "timer and stats") { onNavigate("focus") } }
        item("focus") { Text("${if (s.focus.today.minutes > 0) "${s.focus.today.minutes.roundToInt()} min focused today" else "No focus yet today"} · ${s.focus.today.count} of ${s.data.focus.settings.goal} pomodoros${if (s.focus.streak > 1) " · ${s.focus.streak}-day streak" else ""}.") }
        if (plan.gaps.isNotEmpty() || plan.planned.isNotEmpty()) {
            item("free-heading") { Heading("Free time today", "calendar") { onNavigate("calendar") } }
            val blocks = (plan.gaps.map { it.start to it } + plan.planned.map { it.start to it }).sortedBy { it.first }
            items(blocks, key = { (_, block) -> when (block) {
                is com.knhdixh.attendance80.logic.PlanGap -> "gap:${block.start}:${block.end}"
                is com.knhdixh.attendance80.logic.PlannedItem -> "planned:${block.id}:${block.start}"
                else -> error("Unknown Today block")
            } }) { (_, b) -> when (b) {
                is com.knhdixh.attendance80.logic.PlanGap -> Column { Text("${time(b.start)}–${time(b.end)} · ${minutes(b.minutes)} free"); b.fits.forEach { f -> TextButton(onClick = { onNavigate("homework/${f.id}") }) { Text("${if (f.partial) "start " else ""}${f.title} (${minutes(f.minutes)})") } } }
                is com.knhdixh.attendance80.logic.PlannedItem -> TodayRow(b.title, "${time(b.start)}–${time(b.end)} · planned, ${minutes(b.minutes)}") { onNavigate("homework/${b.id}") }
            } }
        }
        item("sessions-heading") { Heading("Today's sessions", "week") { onNavigate("upcoming") } }
        if (plan.todaySessions.isEmpty()) item("sessions-empty") { Text("No classes today.") }
        items(plan.todaySessions, key = { "session:$it" }) { id -> events[id]?.let { e -> TodayRow(e.summary, "${time(e.start_at)}–${time(e.end_at)}${if (e.location.isNotBlank()) " · ${e.location}" else ""}${e.status?.let { " · $it" } ?: ""}", statusColor(e.status)) { onNavigate("session/${e.id}") } } }
        plan.tomorrowFirst?.let(events::get)?.let { e -> item("tomorrow") { Text("Tomorrow starts at ${time(e.start_at)}: ${e.summary}${if (e.location.isNotBlank()) ", ${e.location}" else ""}.") } }
        item("week-heading") { Text("Next 7 days", style = MaterialTheme.typography.titleMedium) }
        item("week") { Text("${hours(plan.week.classHours)} of class, ${hours(plan.week.workHours)} of work due, about ${hours(plan.week.freeHours)} free between 8:00 and 22:00.${if (plan.week.load > 0.6) " That's tight: start the biggest item early." else ""}") }
    }
}

@Composable private fun Heading(title: String, link: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = click) { Text(link) }
    }
}
@Composable private fun TodayRow(title: String, detail: String, detailColor: Color = Color.Unspecified, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 8.dp)) {
        Text(title); if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = if (detailColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else detailColor)
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
