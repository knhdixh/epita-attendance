package com.knhdixh.attendance80.ui.countdowns

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.PushPin as FilledPushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.logic.Countdowns
import com.knhdixh.attendance80.logic.effectiveTag
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.habits.COLORS
import com.knhdixh.attendance80.ui.theme.paletteColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

val UNITS = listOf("auto" to "Automatic", "days" to "Days", "weeks" to "Weeks and days", "months" to "Months and days", "hours" to "Hours", "clock" to "Live clock", "workdays" to "Working days")
val REPEATS = listOf("none" to "Never", "weekly" to "Every week", "monthly" to "Every month", "yearly" to "Every year")
val AFTER = listOf("up" to "Keep counting up", "past" to "Move it to Past")
private const val DAY = 86_400_000L

/** What the Countdowns page asks the app to do. Each reports its own messages. */
interface CountdownActions {
    suspend fun create(body: JsonObject): CountdownDto? = null
    suspend fun update(id: Long, body: JsonObject): Boolean = false
    suspend fun delete(id: Long): Boolean = false
    fun message(text: String) {}
}
object NoCountdownActions : CountdownActions

/** The page's own state per account. editing: a countdown id, or -1 for a new one from [preset]. */
@Serializable
data class CountdownsUi(val adding: Boolean = false, val draft: String = "", val editing: Long? = null, val preset: String? = null,
                        val showPast: Boolean = false, val dismissed: List<Long> = emptyList())

/** The editor's values; also the preset a quick add or an exam hands it. */
@Serializable
data class Draft(val title: String = "", val at: Long? = null, val allDay: Boolean = true, val repeat: String = "none", val units: String = "auto",
                 val after: String = "up", val startAt: Long? = null, val color: String = "blue", val pinned: Boolean = false, val note: String = "", val eventId: Long? = null)

fun direction(v: Countdowns.View) = when (v.state) { "future" -> "to go"; "up" -> "since"; "past" -> "ago"; else -> "" }
/** "Tue 12 Dec, 9:00", with the year when it isn't this one. */
fun whenLabel(at: Long, allDay: Boolean, now: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(at).atZone(zone)
    val year = d.year != Instant.ofEpochMilli(now).atZone(zone).year
    return DateTimeFormatter.ofPattern(if (year) "EEE d MMM yyyy" else "EEE d MMM").format(d) + if (allDay) "" else ", ${DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(d)}"
}
/** The figure with its numbers in bold: "6 w 3 d". */
fun figure(v: Countdowns.View) = buildAnnotatedString {
    if (v.state == "today") { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Today") }; return@buildAnnotatedString }
    var last = 0
    Regex("\\d+(?::\\d+)*").findAll(v.parts).forEach { m -> append(v.parts.substring(last, m.range.first)); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.value) }; last = m.range.last + 1 }
    append(v.parts.substring(last))
}
fun draftBody(d: Draft) = buildJsonObject {
    put("title", d.title.trim()); put("at", d.at); put("all_day", d.allDay); put("repeat", d.repeat); put("units", d.units); put("after", d.after)
    put("start_at", d.startAt?.let(::JsonPrimitive) ?: JsonNull); put("color", d.color); put("pinned", d.pinned); put("note", d.note)
    d.eventId?.let { put("event_id", it) }
}
fun CountdownDto.draft() = Draft(title, at, all_day, repeat, units, after, start_at, color, pinned, note.orEmpty(), event_id)
private fun Draft.countdown() = Countdowns.Countdown(at ?: 0, allDay, repeat, units, after, startAt)

/** Exams in the timetable in the next six months, one per course and day, not yet counted down to or dismissed. */
fun suggestions(data: AppData, dismissed: Collection<Long>, now: Long, zone: ZoneId): List<Pair<EventDto, String>> {
    val linked = data.countdowns.mapNotNull { it.event_id }.toSet()
    val courses = data.courses.associateBy { it.id }
    val seen = mutableSetOf<String>()
    return data.events.filter { e -> e.start_at > now && e.start_at < now + 180 * DAY && e.id !in linked && e.id !in dismissed &&
            courses[e.course_id]?.tracked == true && effectiveTag(e, courses[e.course_id]) == "exam" }
        .sortedBy { it.start_at }
        .filter { e -> seen.add("${e.course_id}|${Instant.ofEpochMilli(e.start_at).atZone(zone).toLocalDate()}") }
        .take(4).map { it to "${courses[it.course_id]?.name ?: it.summary} exam" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CountdownsScreen(data: AppData?, now: Long, zone: ZoneId, ui: CountdownsUi, onUi: (CountdownsUi) -> Unit, actions: CountdownActions,
                     pending: Long? = null, pendingAdd: Boolean = false, onPendingConsumed: () -> Unit = {}) {
    if (data == null) { Box(Modifier.fillMaxSize().padding(16.dp)) { CircularProgressIndicator() }; return }
    // Live clocks tick every second; the rest follow the shell's minute.
    val live = data.countdowns.any { Countdowns.view(it, now, zone).live }
    val tick by produceState(now, live, now) { value = now; while (live) { delay(1000); value = System.currentTimeMillis().coerceAtLeast(now) } }
    val at = if (live) tick else now
    LaunchedEffect(pending, pendingAdd) {
        if (pending != null) { onUi(ui.copy(editing = pending, adding = false)); onPendingConsumed() }
        else if (pendingAdd) { onUi(ui.copy(adding = true, editing = null)); onPendingConsumed() }
    }
    val sorted = Countdowns.sortCountdowns(data.countdowns, at, zone)
    val coming = sorted.filter { it.v.state == "today" || it.v.state == "future" }
    val up = sorted.filter { it.v.state == "up" }
    val past = sorted.filter { it.v.state == "past" }
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<CountdownDto?>(null) }
    val sugg = suggestions(data, ui.dismissed, now, zone)
    fun create(d: Draft, quiet: Boolean = false) = scope.launch {
        actions.create(draftBody(d))?.let { row ->
            onUi(ui.copy(adding = false, editing = null, draft = "", preset = null))
            if (!quiet) Countdowns.view(row, System.currentTimeMillis(), zone).let { v -> actions.message("Added “${row.title}”: ${v.parts} ${direction(v)}.") }
        }
    }
    @Composable fun item(c: CountdownDto, v: Countdowns.View) {
        if (ui.editing == c.id) Editor(c.draft(), c.id, now, zone, onCancel = { onUi(ui.copy(editing = null)) }, onDelete = { deleting = c },
            onSave = { d -> scope.launch { if (actions.update(c.id, draftBody(d))) { onUi(ui.copy(editing = null)); actions.message("Countdown saved.") } } })
        else Row(c, v, data, at, zone, onEdit = { onUi(ui.copy(editing = c.id, adding = false)) }, onDelete = { deleting = c },
            onPin = { scope.launch { if (actions.update(c.id, buildJsonObject { put("pinned", !c.pinned) })) actions.message(if (c.pinned) "Unpinned." else "Pinned “${c.title}” to Today and the sidebar.") } })
    }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("countdowns-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (data.countdowns.isEmpty() && !ui.adding && ui.editing != -1L) item("empty") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Count down to what matters, or count up from when something began.", fontWeight = FontWeight.Bold)
                Text("Try “Algo exam 12 dec 9:00”, “Trip in 3 weeks”, “Mum’s birthday 3 march” or “No coffee since 1 sep”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (data.countdowns.isNotEmpty()) item("coming-heading") { Heading("Coming up") }
        items(coming, key = { "c:${it.c.id}" }) { item(it.c, it.v) }
        if (data.countdowns.isNotEmpty() && coming.isEmpty()) item("nothing") { Text("Nothing coming up.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item("add") {
            when {
                ui.editing == -1L -> Editor(ui.preset?.let { runCatching { ApiClient.json.decodeFromString<Draft>(it) }.getOrNull() } ?: Draft(), null, now, zone,
                    onCancel = { onUi(ui.copy(editing = null, preset = null)) }, onDelete = {}, onSave = { create(it) })
                ui.adding -> QuickAdd(ui.draft, now, zone, { onUi(ui.copy(draft = it)) }, onCancel = { onUi(ui.copy(adding = false, draft = "")) },
                    onMore = { p -> onUi(ui.copy(adding = false, editing = -1L, preset = ApiClient.json.encodeToString(Draft.serializer(),
                        p?.let { Draft(it.title, it.at, it.all_day != 0, it.repeat, after = it.after) } ?: Draft(ui.draft.trim())))) },
                    onAdd = { p ->
                        when {
                            p == null -> actions.message("Add a date, like “12 dec”, “friday 9am” or “in 3 weeks”.")
                            p.title.isEmpty() -> actions.message("Give it a name too.")
                            else -> create(Draft(p.title, p.at, p.all_day != 0, p.repeat, after = p.after, color = COLORS[data.countdowns.size % (COLORS.size - 1)]))
                        }
                    })
                else -> TextButton({ onUi(ui.copy(adding = true, editing = null)) }, Modifier.testTag("countdowns-add")) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Add countdown") }
            }
        }
        if (sugg.isNotEmpty()) item("suggest") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("From your timetable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sugg.forEach { (e, title) ->
                        InputChip(false, { create(Draft(title, e.start_at, false, color = "red", pinned = true, startAt = System.currentTimeMillis(), eventId = e.id)) },
                            { Text("+ $title · ${whenLabel(e.start_at, false, now, zone)}") },
                            trailingIcon = { Icon(Icons.Outlined.Close, "Don’t suggest $title", Modifier.size(16.dp).clickable { onUi(ui.copy(dismissed = (ui.dismissed + e.id).takeLast(200))) }) },
                            modifier = Modifier.testTag("countdown-exam-${e.id}"))
                    }
                }
            }
        }
        if (up.isNotEmpty()) { item("up-heading") { Heading("Counting up") }; items(up, key = { "u:${it.c.id}" }) { item(it.c, it.v) } }
        if (past.isNotEmpty()) {
            item("past-heading") {
                TextButton({ onUi(ui.copy(showPast = !ui.showPast)) }, Modifier.testTag("countdowns-past")) {
                    Text("Past ${past.size}", fontWeight = FontWeight.Bold); Icon(if (ui.showPast) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            if (ui.showPast) items(past, key = { "p:${it.c.id}" }) { item(it.c, it.v) }
        }
    }
    deleting?.let { c -> AlertDialog(onDismissRequest = { deleting = null }, text = { Text("Delete “${c.title}”?") },
        confirmButton = { TextButton({ deleting = null; scope.launch { if (actions.delete(c.id)) { onUi(ui.copy(editing = null)); actions.message("Deleted “${c.title}”.") } } }) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun Heading(text: String) = Column {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Row(c: CountdownDto, v: Countdowns.View, data: AppData, now: Long, zone: ZoneId, onEdit: () -> Unit, onDelete: () -> Unit, onPin: () -> Unit) {
    val color = paletteColor(c.color)
    val ev = c.event_id?.let { id -> data.events.find { it.id == id } }
    val meta = listOfNotNull(whenLabel(v.at, c.all_day, now, zone),
        REPEATS.toMap()[c.repeat]?.lowercase()?.takeIf { c.repeat != "none" },
        UNITS.toMap()[c.units]?.lowercase()?.takeIf { c.units != "auto" },
        ev?.location?.takeIf { it.isNotBlank() })
    Column(Modifier.fillMaxWidth().testTag("countdown-${c.id}")) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable(onClickLabel = "Edit", onClick = onEdit)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (c.pinned) Icon(Icons.Outlined.PushPin, "pinned", Modifier.size(14.dp).padding(start = 2.dp), tint = color)
                }
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!c.note.isNullOrBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (v.progress != null && v.state != "up" && v.state != "past") Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = .2f))) {
                    Box(Modifier.fillMaxWidth(v.progress.toFloat()).fillMaxHeight().background(color))
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(figure(v), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("countdown-fig-${c.id}"))
                if (direction(v).isNotEmpty()) Text(direction(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconToggleButton(c.pinned, { onPin() }, Modifier.testTag("countdown-pin-${c.id}")) {
                Icon(if (c.pinned) Icons.Filled.FilledPushPin else Icons.Outlined.PushPin, if (c.pinned) "Unpin" else "Pin", tint = if (c.pinned) color else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onDelete) { Icon(Icons.Outlined.Delete, "Delete ${c.title}") }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAdd(draft: String, now: Long, zone: ZoneId, onDraft: (String) -> Unit, onCancel: () -> Unit, onMore: (Countdowns.Parsed?) -> Unit, onAdd: (Countdowns.Parsed?) -> Unit) {
    val p = remember(draft, now) { Countdowns.parseCountdown(draft, now, zone) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("countdowns-quick")) {
        OutlinedTextField(draft, { onDraft(it.take(160)) }, placeholder = { Text("e.g. Algo exam 12 dec 9:00 · Mum’s birthday 3 march · No coffee since 1 sep") },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (draft.isNotBlank()) onAdd(p) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("countdowns-quick-input"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("countdowns-quick-chips")) {
            if (p == null) Text(if (draft.isNotBlank()) "Add a date: “12 dec”, “friday 9am”, “in 3 weeks”…" else "Type a name and a date",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                val v = Countdowns.view(Countdowns.Countdown(p.at, p.all_day != 0, p.repeat, "auto", p.after), now, zone)
                AssistChip({}, { Text(whenLabel(p.at, p.all_day != 0, now, zone)) }, leadingIcon = { Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp)) })
                if (p.repeat != "none") AssistChip({}, { Text(REPEATS.toMap().getValue(p.repeat).lowercase()) })
                AssistChip({}, { Text("${v.parts} ${direction(v)}".trim()) })
            }
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onMore(p) }, Modifier.testTag("countdowns-quick-more")) { Text("More options") }
            Spacer(Modifier.weight(1f))
            TextButton(onCancel) { Text("Cancel") }
            Button({ onAdd(p) }, enabled = draft.isNotBlank(), modifier = Modifier.testTag("countdowns-quick-add")) { Text("Add") }
        }
    }
}

/** Add or fine-tune a countdown (`editor()` on the web), with a live preview of the figure. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Editor(start: Draft, id: Long?, now: Long, zone: ZoneId, onCancel: () -> Unit, onDelete: () -> Unit, onSave: (Draft) -> Unit) {
    val initial = start.copy(at = start.at ?: Countdowns.dayStart(now + 7 * DAY, zone))
    var d by rememberSaveable(id ?: -1L, stateSaver = androidx.compose.runtime.saveable.Saver<Draft, String>({ ApiClient.json.encodeToString(Draft.serializer(), it) }, { ApiClient.json.decodeFromString(Draft.serializer(), it) })) { mutableStateOf(initial) }
    var progress by rememberSaveable(id ?: -1L) { mutableStateOf(initial.startAt != null) }
    val context = LocalContext.current
    val pickerTheme = if (MaterialTheme.colorScheme.background.luminance() < .5f) com.knhdixh.attendance80.R.style.HomeworkPickerDark else com.knhdixh.attendance80.R.style.HomeworkPickerLight
    val color = paletteColor(d.color)
    val at = d.at!!
    val local = Instant.ofEpochMilli(at).atZone(zone)
    fun withDate(date: LocalDate) = (if (d.allDay) date.atStartOfDay(zone) else date.atTime(local.toLocalTime()).atZone(zone)).toInstant().toEpochMilli()
    fun pickDate(ms: Long, set: (LocalDate) -> Unit) { val l = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate(); DatePickerDialog(context, pickerTheme, { _, y, m, dd -> set(LocalDate.of(y, m + 1, dd)) }, l.year, l.monthValue - 1, l.dayOfMonth).show() }
    Column(Modifier.fillMaxWidth().border(1.dp, color.copy(alpha = .5f), RoundedCornerShape(8.dp)).padding(12.dp).testTag("countdown-form"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(d.title, { d = d.copy(title = it.take(80)) }, placeholder = { Text("What are you counting to? e.g. Algorithms exam") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("countdown-form-title"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton({ pickDate(at) { d = d.copy(at = withDate(it)) } }, Modifier.testTag("countdown-form-date")) { Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(local)) }
            OutlinedButton({
                val t = if (d.allDay) LocalTime.of(9, 0) else local.toLocalTime()
                TimePickerDialog(context, pickerTheme, { _, h, m -> d = d.copy(allDay = false, at = local.toLocalDate().atTime(h, m).atZone(zone).toInstant().toEpochMilli()) },
                    t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
            }, Modifier.testTag("countdown-form-time")) { Text(if (d.allDay) "All day" else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local)) }
            if (!d.allDay) TextButton({ d = d.copy(allDay = true, at = local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()) }) { Text("All day") }
        }
        Text("Repeats", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { REPEATS.forEach { (k, l) -> FilterChip(d.repeat == k, { d = d.copy(repeat = k) }, { Text(l) }, modifier = Modifier.testTag("countdown-form-repeat-$k")) } }
        Text("Show as", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { UNITS.forEach { (k, l) -> FilterChip(d.units == k, { d = d.copy(units = k) }, { Text(l) }, modifier = Modifier.testTag("countdown-form-units-$k")) } }
        Text("Once it’s reached", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { AFTER.forEach { (k, l) -> FilterChip(d.after == k, { d = d.copy(after = k) }, { Text(l) }, modifier = Modifier.testTag("countdown-form-after-$k")) } }
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(progress, { progress = it; d = d.copy(startAt = if (it) d.startAt ?: Countdowns.dayStart(now, zone) else null) }, Modifier.testTag("countdown-form-progress"))
            Text("Progress bar from")
            Spacer(Modifier.width(8.dp))
            if (progress) OutlinedButton({ pickDate(d.startAt ?: now) { day -> d = d.copy(startAt = day.atStartOfDay(zone).toInstant().toEpochMilli()) } }) {
                Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(d.startAt ?: now).atZone(zone)))
            }
        }
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            COLORS.forEach { c -> Box(Modifier.size(26.dp).clip(CircleShape).background(paletteColor(c)).border(if (c == d.color) 2.5.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                .clickable { d = d.copy(color = c) }.semantics { contentDescription = c; selected = c == d.color }) }
        }
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { d = d.copy(pinned = !d.pinned) }) {
            Checkbox(d.pinned, { d = d.copy(pinned = it) }, Modifier.testTag("countdown-form-pinned")); Text("Pin to Today and the sidebar")
        }
        OutlinedTextField(d.note, { d = d.copy(note = it.take(500)) }, placeholder = { Text("Note (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        val v = Countdowns.view(d.countdown(), now, zone)
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("countdown-form-preview")) {
            Text(figure(v), style = MaterialTheme.typography.titleMedium); Spacer(Modifier.width(6.dp)); Text(direction(v), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ onSave(d) }, enabled = d.title.isNotBlank(), modifier = Modifier.testTag("countdown-form-save")) { Text(if (id != null) "Save" else "Add countdown") }
            OutlinedButton(onCancel) { Text("Cancel") }
            if (id != null) TextButton(onDelete, Modifier.testTag("countdown-form-delete")) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}
