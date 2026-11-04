package com.knhdixh.attendance80.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.logic.Dates
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.CompletionDto
import java.time.Instant
import java.time.ZoneId

/** One finished task or finished occurrence of a repeating one. */
data class CompletedItem(val key: String, val taskId: Long, val title: String, val at: Long, val repeating: Boolean, val project: String)
data class CompletedDay(val day: String, val items: List<CompletedItem>)

object CompletedModel {
    /** Done tasks (by `completed_at`) and the occurrences of repeating tasks (the completions table), newest day first. */
    fun byDay(d: AppData, completions: List<CompletionDto>, zone: ZoneId): List<CompletedDay> {
        val project = { id: Long? -> id?.let { p -> d.projects.find { it.id == p }?.name } ?: "Inbox" }
        val done = d.homework.filter { it.done && it.completed_at != null }.map { CompletedItem("t${it.id}", it.id, it.title, it.completed_at!!, false, project(it.project_id)) }
        val known = d.homework.associateBy { it.id }
        val occurrences = completions.mapNotNull { c -> known[c.homework_id]?.let { CompletedItem("c${c.id}", it.id, it.title, c.completed_at, true, project(it.project_id)) } }
        // A task that finished its last occurrence is both done and in the table: the done row says it already.
        val doneAt = done.map { it.taskId to it.at }.toSet()
        return (done + occurrences.filter { (it.taskId to it.at) !in doneAt }).sortedByDescending { it.at }
            .groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().toString() }.map { (day, items) -> CompletedDay(day, items) }
    }
}

@Composable
fun CompletedScreen(env: TaskEnv, actions: TaskActions, now: Long) {
    var completions by remember { mutableStateOf<List<CompletionDto>?>(null) }
    LaunchedEffect(Unit) { completions = actions.completions(now - 90L * 86_400_000L) }
    val days = remember(env.data, completions) { CompletedModel.byDay(env.data, completions.orEmpty(), env.zone) }
    LazyColumn(Modifier.fillMaxSize().testTag("completed-list")) {
        if (days.isEmpty()) item("none") { Text(if (completions == null) "Loading…" else "Nothing completed in the last 90 days.", Modifier.padding(16.dp).testTag("completed-empty"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        days.forEach { day ->
            item("d${day.day}") { Text(TaskModel.dayLabel(day.day, env.today) + " · ${day.items.size}", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).testTag("completed-day-${day.day}"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(day.items, key = { it.key }) { item ->
                Row(Modifier.fillMaxWidth().clickable { env.onOpen(item.taskId) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("completed-${item.key}"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(item.title, textDecoration = TextDecoration.LineThrough)
                        Text(Dates.formatTime(item.at, env.zone) + (if (item.repeating) " · repeating" else "") + " · #${item.project}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
