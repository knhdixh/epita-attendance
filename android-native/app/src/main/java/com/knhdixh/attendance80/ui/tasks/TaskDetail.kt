package com.knhdixh.attendance80.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.logic.Recurrence
import com.knhdixh.attendance80.net.CommentDto
import com.knhdixh.attendance80.net.HomeworkDto
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val durations = listOf(null to "No duration", 15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 h", 90 to "1.5 h", 120 to "2 h", 240 to "4 h")
private val reminderChoices = listOf(10 to "10 min before", 30 to "30 min before", 60 to "1 hour before", 1440 to "1 day before")

private fun obj(vararg p: Pair<String, Any?>) = buildJsonObject {
    for ((k, v) in p) when (v) { null -> put(k, JsonNull); is Number -> put(k, v); is Boolean -> put(k, v); else -> put(k, v.toString()) }
}

/** The opened task: a sheet that opens tall (and can be pulled to full screen). Edits are saved as they are made, through the outbox. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskDetailSheet(id: Long, env: TaskEnv, actions: TaskActions, onFocus: (Long) -> Unit, onPlan: (HomeworkDto) -> Unit, onDelete: (HomeworkDto) -> Unit, onOpen: (Long) -> Unit, onDismiss: () -> Unit) {
    val h = env.data.homework.find { it.id == id }
    if (h == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val zone = env.zone
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp).testTag("task-detail")) {
            fun save(vararg p: Pair<String, Any?>) { scope.launch { actions.update(h.id, obj(*p)) } }
            // title and description: saved when the field loses focus
            var title by remember(h.id) { mutableStateOf(h.title) }
            Row(verticalAlignment = Alignment.Top) {
                PriorityRing(h) { done -> env.onToggle(h, done) }
                OutlinedTextField(title, { title = it }, Modifier.weight(1f).testTag("detail-title").onFocusChanged { if (!it.isFocused && title.isNotBlank() && title != h.title) save("title" to title.trim()) },
                    textStyle = MaterialTheme.typography.titleMedium)
            }
            var notes by remember(h.id) { mutableStateOf(h.notes) }
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("detail-notes").onFocusChanged { if (!it.isFocused && notes != h.notes) save("notes" to notes) },
                label = { Text("Description") }, supportingText = { if (notes.isNotBlank()) Text(TaskModel.inlineMarkdown(notes.lineSequence().first())) })

            Section("Project")
            var menu by remember { mutableStateOf<String?>(null) }
            Box {
                val projectName = h.project_id?.let { p -> env.data.projects.find { it.id == p }?.name } ?: "Inbox"
                val sectionName = h.section_id?.let { s -> env.data.sections.find { it.id == s }?.name }
                OutlinedButton({ menu = "project" }, Modifier.testTag("detail-project")) { Text(projectName + (sectionName?.let { " / $it" } ?: "")) }
                DropdownMenu(menu == "project", { menu = null }) {
                    DropdownMenuItem({ Text("Inbox") }, { save("project_id" to null, "section_id" to null); menu = null })
                    TaskModel.projectTree(env.data).forEach { (p, depth) ->
                        DropdownMenuItem({ Text("  ".repeat(depth) + p.name) }, { save("project_id" to p.id, "section_id" to null); menu = null })
                        env.data.sections.filter { it.project_id == p.id }.sortedBy { it.position }.forEach { s ->
                            DropdownMenuItem({ Text("  ".repeat(depth + 1) + s.name) }, { save("project_id" to p.id, "section_id" to s.id); menu = null })
                        }
                    }
                }
            }

            Section("Date")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    val base = h.due_date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.parse(env.today)
                    android.app.DatePickerDialog(context, { _, y, m, d -> scope.launch { actions.update(h.id, dateBody(LocalDate.of(y, m + 1, d).toString(), zone)) } }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                }, Modifier.testTag("detail-date")) { Text(h.due_date?.let { TaskModel.dayLabel(it, env.today) + (h.due_time?.let { t -> " $t" } ?: "") } ?: "No date") }
                if (h.due_date != null) TextButton({ scope.launch { actions.update(h.id, dateBody(null, zone)) } }, Modifier.testTag("detail-date-clear")) { Text("Clear") }
                OutlinedButton({
                    val t = h.due_time?.split(':')
                    android.app.TimePickerDialog(context, { _, hh, mm -> scope.launch { actions.update(h.id, obj("due_time" to "%02d:%02d".format(hh, mm), "tz" to zone.id)) } }, t?.getOrNull(0)?.toIntOrNull() ?: 9, t?.getOrNull(1)?.toIntOrNull() ?: 0, true).show()
                }, enabled = h.due_date != null, modifier = Modifier.testTag("detail-time")) { Text(h.due_time ?: "Time") }
            }
            var repeat by remember(h.id, h.recurrence) { mutableStateOf(h.recurrence.orEmpty()) }
            var repeatError by remember(h.id) { mutableStateOf<String?>(null) }
            fun saveRepeat() {
                if (repeat.isBlank()) { if (h.recurrence != null) save("recurrence" to null); repeatError = null; return }
                val clean = Recurrence.cleanRecurrence(repeat, env.today, h.due_date)
                if (!clean.ok) { repeatError = clean.error; return }
                repeatError = null
                if (clean.text != h.recurrence) save("recurrence" to clean.text, *(if (h.due_date == null) arrayOf("due_date" to env.today, "tz" to zone.id) else emptyArray()))
            }
            OutlinedTextField(repeat, { repeat = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("detail-recurrence").onFocusChanged { if (!it.isFocused) saveRepeat() },
                label = { Text("Repeat, like every mon, wed") }, isError = repeatError != null, supportingText = { repeatError?.let { Text(it) } }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton({
                    val base = h.deadline_date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.parse(env.today)
                    android.app.DatePickerDialog(context, { _, y, m, d -> save("deadline_date" to LocalDate.of(y, m + 1, d).toString()) }, base.year, base.monthValue - 1, base.dayOfMonth).show()
                }, Modifier.testTag("detail-deadline")) { Text(h.deadline_date?.let { "Deadline ${TaskModel.dayLabel(it, env.today)}" } ?: "Deadline") }
                if (h.deadline_date != null) TextButton({ save("deadline_date" to null) }) { Text("Clear") }
                Box {
                    OutlinedButton({ menu = "duration" }, Modifier.testTag("detail-duration")) { Text(durations.find { it.first == h.effort_minutes }?.second ?: "${h.effort_minutes} min") }
                    DropdownMenu(menu == "duration", { menu = null }) { durations.forEach { (m, label) -> DropdownMenuItem({ Text(label) }, { save("effort_minutes" to m); menu = null }) } }
                }
            }

            Section("Priority")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (4 downTo 1).forEach { p ->
                    FilterChip(h.priority == p, { save("priority" to p) }, { Text(TaskModel.priorityLabel(p)) }, Modifier.testTag("detail-priority-${TaskModel.priorityLabel(p)}"))
                }
            }

            Section("Labels")
            if (env.data.labels.isEmpty()) Text("No labels yet. Add them in Browse › Filters & Labels.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                env.data.labels.forEach { l ->
                    FilterChip(l.id in h.labels, { scope.launch { actions.update(h.id, buildJsonObject { put("label_ids", JsonArray((if (l.id in h.labels) h.labels - l.id else h.labels + l.id).map(::JsonPrimitive))) }) } },
                        { Text("@${l.name}") }, Modifier.testTag("detail-label-${l.id}"))
                }
            }

            Section("Reminders")
            h.reminders.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.before_minutes?.let { m -> reminderChoices.find { it.first == m }?.second ?: "$m min before" } ?: r.at?.let { com.knhdixh.attendance80.logic.Dates.formatDate(it, zone) + " " + com.knhdixh.attendance80.logic.Dates.formatTime(it, zone) } ?: "", Modifier.weight(1f))
                    IconButton({ scope.launch { actions.deleteReminder(r.id) } }, Modifier.testTag("detail-reminder-delete-${r.id}")) { Icon(Icons.Outlined.Close, "Remove reminder") }
                }
            }
            if (h.due_date == null) Text("Give the task a date to add a reminder.", style = MaterialTheme.typography.bodySmall)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                reminderChoices.filter { c -> h.reminders.none { it.before_minutes == c.first } }.forEach { (m, label) ->
                    AssistChip({ scope.launch { actions.addReminder(h.id, obj("before_minutes" to m)) } }, { Text(label) }, Modifier.testTag("detail-add-reminder-$m"))
                }
            }

            Section("Subtasks")
            val subs = TaskModel.children(env.data, h.id)
            subs.forEachIndexed { i, sub ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { TaskRow(sub, env.copy(onOpen = onOpen), swipe = false) }
                    IconButton({ scope.launch { actions.move(TaskModel.moveWithin(sub, i - 1)) } }, enabled = i > 0, modifier = Modifier.testTag("subtask-up-${sub.id}")) { Icon(Icons.Outlined.ArrowUpward, "Move up") }
                    IconButton({ scope.launch { actions.move(TaskModel.moveWithin(sub, i + 1)) } }, enabled = i < subs.size - 1, modifier = Modifier.testTag("subtask-down-${sub.id}")) { Icon(Icons.Outlined.ArrowDownward, "Move down") }
                }
            }
            var sub by remember(h.id) { mutableStateOf("") }
            OutlinedTextField(sub, { sub = it }, Modifier.fillMaxWidth().testTag("detail-add-subtask"), placeholder = { Text("Add subtask") }, singleLine = true,
                trailingIcon = { IconButton({ val t = sub.trim(); if (t.isNotEmpty()) { sub = ""; scope.launch { actions.create(obj("title" to t, "parent_id" to h.id)) } } }, Modifier.testTag("detail-add-subtask-go")) { Icon(Icons.Outlined.Add, "Add subtask") } })

            Section("Comments")
            var comments by remember(h.id) { mutableStateOf<List<CommentDto>>(emptyList()) }
            LaunchedEffect(h.id) { comments = actions.comments(h.id) }
            comments.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(TaskModel.inlineMarkdown(c.body), Modifier.weight(1f).testTag("comment-${c.id}"))
                    IconButton({ comments = comments - c; scope.launch { actions.deleteComment(c.id, h.id) } }, Modifier.testTag("comment-delete-${c.id}")) { Icon(Icons.Outlined.Close, "Delete comment") }
                }
            }
            var comment by remember(h.id) { mutableStateOf("") }
            OutlinedTextField(comment, { comment = it }, Modifier.fillMaxWidth().testTag("detail-add-comment"), placeholder = { Text("Comment") },
                trailingIcon = { IconButton({
                    val t = comment.trim()
                    if (t.isNotEmpty()) { comment = ""; comments = comments + CommentDto(-(comments.size + 1).toLong(), h.id, t); scope.launch { actions.addComment(h.id, t) } }
                }, Modifier.testTag("detail-add-comment-go")) { Icon(Icons.Outlined.Send, "Add comment") } })

            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!h.done) Button({ onFocus(h.id) }, Modifier.testTag("detail-focus")) { Text("Focus on this") }
                OutlinedButton({ onPlan(h) }, Modifier.testTag("detail-plan")) { Text("Plan on the Calendar") }
                TextButton({ onDelete(h); onDismiss() }, Modifier.testTag("detail-delete")) { Text("Delete") }
            }
            Spacer(Modifier.height(8.dp))
            val created = h.created_at.takeIf { it > 0 }?.let { "Created ${com.knhdixh.attendance80.logic.Dates.formatDate(it, zone)}" }
            val completed = h.completed_at?.let { "Completed ${com.knhdixh.attendance80.logic.Dates.formatDate(it, zone)} ${com.knhdixh.attendance80.logic.Dates.formatTime(it, zone)}" }
            Text(listOfNotNull(created, completed).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("detail-dates"))
        }
    }
}

@Composable private fun Section(text: String) {
    Text(text, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
