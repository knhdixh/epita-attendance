package com.knhdixh.attendance80.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.HomeworkDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** What a task row needs to know and to call. */
data class TaskEnv(
    val data: AppData, val today: String, val zone: ZoneId,
    val onOpen: (Long) -> Unit = {},
    val onToggle: (HomeworkDto, Boolean) -> Unit = { _, _ -> },
    val onSchedule: (HomeworkDto) -> Unit = {},
)

@Composable
fun metaColor(kind: String): Color = when (kind) {
    "overdue" -> MaterialTheme.colorScheme.error
    "today" -> Color(0xFF058527)
    "recurrence", "date" -> MaterialTheme.colorScheme.onSurfaceVariant
    "label", "project", "course" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** The ring in the priority colour; filled with a tick when done. */
@Composable
fun PriorityRing(h: HomeworkDto, onToggle: (Boolean) -> Unit) {
    val color = TaskModel.priorityColor(h.priority)
    Box(Modifier.size(48.dp).clickable(onClickLabel = if (h.done) "Reopen" else "Complete") { onToggle(!h.done) }
        .semantics { contentDescription = "Done: ${h.title}${if (h.done) ", done" else ""}, ${TaskModel.priorityLabel(h.priority)}" }
        .testTag("task-check-${h.id}"), contentAlignment = Alignment.Center) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (h.done) color else color.copy(alpha = .12f))
            .border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
            if (h.done) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = Color.White)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskRow(h: HomeworkDto, env: TaskEnv, showProject: Boolean = false, indent: Int = 0, modifier: Modifier = Modifier, swipe: Boolean = true) {
    val content: @Composable () -> Unit = {
        Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = (indent * 24).dp).testTag("task-row-${h.id}"), verticalAlignment = Alignment.Top) {
            PriorityRing(h) { done -> env.onToggle(h, done) }
            Column(Modifier.weight(1f).clickable { env.onOpen(h.id) }.padding(top = 10.dp, bottom = 10.dp, end = 12.dp)) {
                Text(TaskModel.inlineMarkdown(h.title), style = MaterialTheme.typography.bodyLarge, textDecoration = if (h.done) TextDecoration.LineThrough else null,
                    modifier = Modifier.alpha(if (h.done) .6f else 1f))
                val meta = TaskModel.meta(h, env.data, env.today, showProject)
                if (meta.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    meta.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall, color = metaColor(it.kind)) }
                }
            }
        }
    }
    if (!swipe || h.done) { content(); return }
    key(h.id, h.due_date) {
        val state = rememberSwipeToDismissBoxState()
        // Once per swipe: the box settles on its end value, and that is what completes or schedules. A row that is still here
        // afterwards (a repeating task moved on, the change was refused) slides back.
        LaunchedEffect(state.currentValue) {
            when (state.currentValue) {
                SwipeToDismissBoxValue.StartToEnd -> { env.onToggle(h, true); kotlinx.coroutines.delay(600); state.reset() }
                SwipeToDismissBoxValue.EndToStart -> { env.onSchedule(h); state.reset() }
                else -> Unit
            }
        }
        SwipeToDismissBox(state, modifier = Modifier.testTag("task-swipe-${h.id}"), backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(Modifier.fillMaxSize().background(if (toRight) Color(0xFF058527) else Color(0xFF246FE0)).padding(horizontal = 20.dp),
                horizontalArrangement = if (toRight) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (toRight) Icons.Outlined.Check else Icons.Outlined.DateRange, null, tint = Color.White)
            }
        }) { content() }
    }
}

/** The body for a change of date. The task's time stays; "no date" clears both. */
fun dateBody(date: String?, zone: ZoneId): JsonObject = JsonObject(buildMap {
    put("due_date", date?.let(::JsonPrimitive) ?: JsonNull)
    if (date != null) put("tz", JsonPrimitive(zone.id))
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(h: HomeworkDto, today: String, onPick: (String?) -> Unit, onPickDate: () -> Unit, onDismiss: () -> Unit) {
    val t = LocalDate.parse(today)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp).testTag("schedule-sheet")) {
            Text("Schedule: ${h.title}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            listOf("Today" to t, "Tomorrow" to t.plusDays(1), "Next Monday" to t.with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)), "In a week" to t.plusDays(7))
                .forEach { (label, day) -> TextButton({ onPick(day.toString()) }, Modifier.fillMaxWidth().testTag("schedule-$label")) { Text(label, Modifier.fillMaxWidth()) } }
            TextButton(onPickDate, Modifier.fillMaxWidth().testTag("schedule-pick")) { Text("Pick a date…", Modifier.fillMaxWidth()) }
            if (h.due_date != null) TextButton({ onPick(null) }, Modifier.fillMaxWidth().testTag("schedule-none")) { Text("No date", Modifier.fillMaxWidth()) }
        }
    }
}
