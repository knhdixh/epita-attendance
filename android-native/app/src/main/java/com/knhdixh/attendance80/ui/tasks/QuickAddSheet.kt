package com.knhdixh.attendance80.ui.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.logic.QuickAdd
import com.knhdixh.attendance80.logic.QuickAddNames
import com.knhdixh.attendance80.logic.QuickAddParser
import com.knhdixh.attendance80.logic.QuickRange
import com.knhdixh.attendance80.net.AppData
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.time.ZoneId

/** Where the sheet was opened: a project page adds to that project, Today adds for today. */
data class QuickDefaults(val projectId: Long? = null, val sectionId: Long? = null, val date: String? = null)

/** What saving a quick-add line does: the task's body, and what has to exist first. Pure, so it can be tested. */
data class QuickPlan(val body: JsonObject, val newLabels: List<String>, val newProject: String?, val newSection: String?, val reminder: Int?)

object QuickAddPlan {
    val kindColors = mapOf("date" to Color(0xFF058527), "time" to Color(0xFF058527), "recurrence" to Color(0xFF058527), "priority" to Color(0xFFD1453B),
        "project" to Color(0xFF246FE0), "section" to Color(0xFF246FE0), "label" to Color(0xFF8A4FFF), "deadline" to Color(0xFFD1453B),
        "reminder" to Color(0xFFEB8909), "duration" to Color(0xFFEB8909))

    fun names(d: AppData) = QuickAddNames(d.projects.filter { !it.archived }.map { it.name }, d.sections.map { it.name }.distinct(), d.labels.map { it.name })

    /** The text with every part the parser read coloured and bold. */
    fun highlight(text: String, ranges: List<QuickRange>, colors: Map<String, Color> = kindColors): AnnotatedString = buildAnnotatedString {
        append(text)
        ranges.forEach { r ->
            if (r.start in 0 until r.end && r.end <= text.length)
                addStyle(SpanStyle(color = colors[r.kind] ?: Color.Unspecified, fontWeight = FontWeight.SemiBold), r.start, r.end)
        }
    }

    /** Drops what the parser read for [kind] and puts [token] at the end ("" just drops it): how the chips edit the text. */
    fun replace(text: String, ranges: List<QuickRange>, kind: String, token: String): String {
        var out = text
        ranges.filter { it.kind == kind }.sortedByDescending { it.start }.forEach { out = out.removeRange(it.start, it.end) }
        out = out.replace(Regex("\\s{2,}"), " ").trim()
        return if (token.isEmpty()) out else (if (out.isEmpty()) token else "$out $token")
    }

    fun plan(q: QuickAdd, d: AppData, zone: ZoneId, defaults: QuickDefaults = QuickDefaults()): QuickPlan {
        val project = q.project?.let { n -> d.projects.find { it.name.equals(n, true) && !it.archived } }
        val projectId = if (q.project != null) project?.id else defaults.projectId
        val section = q.section?.let { n -> d.sections.find { it.name.equals(n, true) && (projectId == null || it.project_id == projectId) } }
        val sectionId = if (q.section != null) section?.id else if (q.project == null) defaults.sectionId else null
        val known = q.labels.mapNotNull { n -> d.labels.find { it.name.equals(n, true) } }
        val date = q.due_date ?: defaults.date
        val body = buildJsonObject {
            put("title", q.title.ifBlank { "Untitled" })
            if (date != null) { put("due_date", date); q.due_time?.let { put("due_time", it) }; put("tz", zone.id) }
            q.recurrence?.let { put("recurrence", it) }
            if (q.priority > 1) put("priority", q.priority)
            q.deadline?.let { put("deadline_date", it) }
            q.duration?.let { put("effort_minutes", it) }
            projectId?.let { put("project_id", it) }
            sectionId?.let { put("section_id", it) }
            if (known.isNotEmpty()) put("label_ids", JsonArray(known.map { JsonPrimitive(it.id) }))
        }
        return QuickPlan(body, q.labels.filter { n -> known.none { it.name.equals(n, true) } }, q.project.takeIf { project == null },
            q.section.takeIf { section == null && q.project == null }, q.reminder.takeIf { date != null })
    }
}

/** Creates what the plan needs (new labels, project, section), then the task, then its reminder. @return the task's id. */
suspend fun saveQuickAdd(q: QuickAdd, d: AppData, zone: ZoneId, defaults: QuickDefaults, actions: TaskActions): Long? {
    val plan = QuickAddPlan.plan(q, d, zone, defaults)
    var body = plan.body
    val labelIds = (body["label_ids"] as? JsonArray).orEmpty().toMutableList()
    for (name in plan.newLabels) actions.createEntity("label", buildJsonObject { put("name", name) })?.let { labelIds += JsonPrimitive(it) }
    val project = plan.newProject?.let { name -> actions.createEntity("project", buildJsonObject { put("name", name) }) }
    val section = plan.newSection?.let { name ->
        (project ?: (body["project_id"] as? JsonPrimitive)?.longOrNull)?.let { p -> actions.createEntity("section", buildJsonObject { put("name", name); put("project_id", p) }) }
    }
    body = JsonObject(body + buildMap {
        if (labelIds.isNotEmpty()) put("label_ids", JsonArray(labelIds))
        project?.let { put("project_id", JsonPrimitive(it)) }
        section?.let { put("section_id", JsonPrimitive(it)) }
    })
    val id = actions.create(body) ?: return null
    plan.reminder?.let { actions.addReminder(id, buildJsonObject { put("before_minutes", it) }) }
    return id
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(data: AppData, today: String, zone: ZoneId, defaults: QuickDefaults, actions: TaskActions, initial: String = "", onDismiss: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val names = remember(data) { QuickAddPlan.names(data) }
    val q = remember(field.text, names, today) { QuickAddParser.parseQuickAdd(field.text, today, names) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var menu by remember { mutableStateOf<String?>(null) }
    // The sheet attaches its content a moment after this runs, so try again until the field exists.
    LaunchedEffect(Unit) { repeat(20) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(50) } }
    fun edit(kind: String, token: String) { val t = QuickAddPlan.replace(field.text, q.ranges, kind, token); field = TextFieldValue(t, TextRange(t.length)) }
    fun add() {
        if (q.title.isBlank()) return
        val line = q
        // The keyboard stays up: clear the line and carry on with the next task.
        field = TextFieldValue("")
        scope.launch { saveQuickAdd(line, data, zone, defaults, actions) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding().testTag("quick-add-sheet")) {
            OutlinedTextField(field, { field = it }, Modifier.fillMaxWidth().focusRequester(focus).testTag("quick-add-input"),
                placeholder = { Text("Buy milk tomorrow p1 #Home @errands") },
                visualTransformation = { text -> TransformedText(QuickAddPlan.highlight(text.text, q.ranges), OffsetMapping.Identity) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }), maxLines = 3)
            Spacer(Modifier.height(8.dp))
            LazyRow(Modifier.testTag("quick-chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chips = listOf(
                    "date" to (q.due_date?.let { TaskModel.dayLabel(it, today) + (q.due_time?.let { t -> " $t" } ?: "") } ?: defaults.date?.let { TaskModel.dayLabel(it, today) } ?: "Date"),
                    "priority" to (if (q.priority > 1) TaskModel.priorityLabel(q.priority) else "Priority"),
                    "label" to (q.labels.takeIf { it.isNotEmpty() }?.joinToString(" ") { "@$it" } ?: "Labels"),
                    "reminder" to (q.reminder?.let { "$it min before" } ?: "Reminder"),
                    "project" to (q.project ?: defaults.projectId?.let { id -> data.projects.find { it.id == id }?.name } ?: "Inbox"),
                )
                items(chips, key = { it.first }) { (kind, label) ->
                    Box {
                        val set = when (kind) { "date" -> q.due_date != null; "priority" -> q.priority > 1; "label" -> q.labels.isNotEmpty(); "reminder" -> q.reminder != null; else -> q.project != null }
                        FilterChip(set, { menu = kind }, { Text(label) }, Modifier.testTag("quick-chip-$kind"))
                        DropdownMenu(menu == kind, { menu = null }) {
                            @Composable fun item(text: String, kind: String, token: String) = DropdownMenuItem({ Text(text) }, { edit(kind, token); menu = null }, Modifier.testTag("quick-pick-$text"))
                            when (kind) {
                                "date" -> { item("Today", "date", "today"); item("Tomorrow", "date", "tomorrow"); item("Next week", "date", "next week"); item("No date", "date", "") }
                                "priority" -> { (4 downTo 1).forEach { p -> item(TaskModel.priorityLabel(p), "priority", if (p == 1) "" else "p${5 - p}") } }
                                "label" -> data.labels.forEach { l -> item(l.name, "label", (if (l.name.contains(' ')) "@${l.name}" else "@${l.name}")) }
                                "reminder" -> { item("10 min before", "reminder", "!10m"); item("30 min before", "reminder", "!30m"); item("1 hour before", "reminder", "!1h"); item("No reminder", "reminder", "") }
                                else -> { item("Inbox", "project", ""); data.projects.filter { !it.archived }.forEach { p -> item(p.name, "project", "#${p.name}") } }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button({ add() }, enabled = q.title.isNotBlank(), modifier = Modifier.testTag("quick-add-save")) { Text("Add task") }
            }
        }
    }
}
