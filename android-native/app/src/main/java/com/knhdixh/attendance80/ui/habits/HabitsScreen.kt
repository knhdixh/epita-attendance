package com.knhdixh.attendance80.ui.habits

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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.logic.Habits
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.theme.paletteColor
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

val COLORS = listOf("blue", "green", "teal", "purple", "pink", "red", "orange", "yellow", "gray")
private val MILESTONES = setOf(7, 14, 30, 50, 100, 200, 365)
private const val GRID_WEEKS = 26
private const val HEAT_WEEKS = 53
private val SCHEDULES = listOf("daily" to "Every day", "days" to "Some days", "weekly" to "Times a week", "monthly" to "Times a month", "interval" to "Every few days")

/** What the Habits pages ask the app to do. Each reports its own messages. */
interface HabitActions {
    suspend fun setCount(id: Long, day: String, count: Int): Boolean = false
    suspend fun create(body: JsonObject): HabitDto? = null
    suspend fun update(id: Long, body: JsonObject): HabitDto? = null
    suspend fun delete(id: Long): Boolean = false
    suspend fun order(ids: List<Long>): Boolean = false
    fun message(text: String) {}
}
object NoHabitActions : HabitActions

/** The page's own state, kept per account across navigation. editing: a habit id, or -1 for a new one from [preset]. */
@Serializable
data class HabitsUi(val grid: Boolean = false, val adding: Boolean = false, val draft: String = "", val editing: Long? = null,
                    val preset: String? = null, val showArchived: Boolean = false)

/** Check-ins per habit, as `counts()` on the web. */
fun countsOf(data: HabitsDto): Map<Long, Map<String, Int>> = data.logs.groupBy { it.habit_id }.mapValues { (_, l) -> l.associate { it.day to it.count } }
fun startOf(h: HabitDto, zone: ZoneId) = Habits.localKey(h.created_at, zone)
fun statsOf(h: HabitDto, counts: Map<String, Int>, today: String, zone: ZoneId) = Habits.habitStats(h, counts, today, startOf(h, zone))
private fun progress(s: Habits.Stats): Habits.Progress? = (s.progress as? JsonObject)?.let { runCatching { Json.decodeFromJsonElement(Habits.Progress.serializer(), it) }.getOrNull() }

/** One tap: one more check-in, or back to none once the day is complete; [back] takes one off. */
fun nextCount(n: Int, target: Int, back: Boolean = false) = if (back) maxOf(0, n - 1) else if (n >= maxOf(1, target)) 0 else n + 1
/** A past day in the strip or the grid: done or not, in one tap. */
fun toggledCount(n: Int, target: Int) = if (n >= maxOf(1, target)) 0 else maxOf(1, target)

private fun fmtKey(key: String, pattern: String) = LocalDate.parse(key).format(DateTimeFormatter.ofPattern(pattern))
private fun plural(n: Int, w: String) = "$n $w${if (n == 1) "" else "s"}"

/** The body for a habit, as `readForm` builds it. */
fun habitBody(name: String, icon: String, color: String, schedule: HabitScheduleDto, target: Int, unit: String, remindAt: String?) = buildJsonObject {
    put("name", name.trim()); put("icon", icon.trim()); put("color", color)
    put("schedule", buildJsonObject { put("type", schedule.type); schedule.days?.let { put("days", JsonArray(it.map(::JsonPrimitive))) }; schedule.n?.let { put("n", it) } })
    put("target", target); put("unit", unit.trim()); put("remind_at", remindAt?.let(::JsonPrimitive) ?: JsonNull)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitCheck(h: HabitDto, s: Habits.Stats, onCheck: (Boolean) -> Unit, size: Int = 34) {
    val color = paletteColor(h.color)
    val target = maxOf(1, h.target)
    val frac = minOf(1f, s.today.toFloat() / target)
    val label = if (target > 1) "${h.name}: ${s.today} of $target${h.unit?.let { " $it" }.orEmpty()} today" else "${h.name}: ${if (s.todayDone) "done" else "not done"} today"
    Box(Modifier.size(size.dp).clip(CircleShape)
        .combinedClickable(onClickLabel = if (s.todayDone && target == 1) "Mark not done" else "Add one", onLongClickLabel = "Take one off",
            onLongClick = { onCheck(true) }, onClick = { onCheck(false) })
        .semantics { contentDescription = label; stateDescription = if (s.todayDone) "Done" else "Not done"; selected = s.todayDone }
        .testTag("habit-check-${h.id}")
        .drawBehind {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            if (s.todayDone) drawCircle(color, radius = this.size.minDimension / 2 - 2.dp.toPx())
            else {
                drawCircle(color.copy(alpha = .35f), radius = this.size.minDimension / 2 - inset, style = Stroke(stroke))
                if (frac > 0) drawArc(color, -90f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            }
        }, contentAlignment = Alignment.Center) {
        if (s.todayDone) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size((size * .55).dp))
        else if (target > 1 && s.today > 0) Text("${s.today}", style = MaterialTheme.typography.labelMedium, color = color)
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Meta(h: HabitDto, s: Habits.Stats) {
    val bits = mutableListOf(Habits.scheduleLabel(h.schedule) + if (h.target > 1) " · ${h.target}${h.unit?.let { " $it" } ?: "×"} a day" else "")
    progress(s)?.let { p ->
        if (p.of != null) bits += "${p.done ?: 0}/${p.of} this ${if (h.schedule.type == "weekly") "week" else "month"}"
        if (p.since != null) bits += "last ${if (p.since == 1) "yesterday" else "${p.since} days ago"}"
    }
    if (!s.due) bits += "not due today"
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(bits.first(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.streak > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocalFireDepartment, null, Modifier.size(14.dp), tint = com.knhdixh.attendance80.ui.theme.LocalExtraColors.current.orange)
            Text(Habits.streakLabel(s.streak, s.unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        bits.drop(1).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The last seven days, tappable to fill in a day you forgot. */
@Composable
private fun WeekStrip(h: HabitDto, counts: Map<String, Int>, today: String, onToggle: (String) -> Unit) {
    val color = paletteColor(h.color)
    val target = maxOf(1, h.target)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag("habit-week-${h.id}")) {
        (0 until 7).forEach { i ->
            val d = Habits.addDays(today, i - 6)
            val n = counts[d] ?: 0
            val off = h.schedule.type == "days" && Habits.dow(d) !in h.schedule.days.orEmpty()
            val done = n >= target
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp))
                .background(if (done) color else if (n > 0) color.copy(alpha = .4f) else if (off) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant)
                .border(if (d == today) 1.5.dp else 0.dp, if (d == today) color else Color.Transparent, RoundedCornerShape(5.dp))
                .clickable { onToggle(d) }
                .semantics { contentDescription = "${fmtKey(d, "EEE d MMM")}, ${if (done) "done" else if (n > 0) "$n of $target" else if (off) "day off" else "not done"}" }
                .testTag("habit-day-${h.id}-$d"), contentAlignment = Alignment.Center) {
                Text(Habits.DOW[Habits.dow(d) - 1].take(1), style = MaterialTheme.typography.labelSmall, color = if (done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** HabitKit-style: one square per day, a column per week, the habit's colour where it was done. */
@Composable
private fun Cells(h: HabitDto, counts: Map<String, Int>, today: String, start: String, weeks: Int, onToggle: ((String) -> Unit)?, cell: Int = 11) {
    val color = paletteColor(h.color)
    val target = maxOf(1, h.target)
    val first = Habits.addDays(Habits.weekOf(today), -(weeks - 1) * 7)
    val scroll = rememberScrollState()
    LaunchedEffect(weeks) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.horizontalScroll(scroll).testTag("habit-cells-${h.id}"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(weeks) { w ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(7) { i ->
                    val d = Habits.addDays(first, w * 7 + i)
                    val n = counts[d] ?: 0
                    val lvl = if (n >= target) 4 else if (n > 0) maxOf(1, (n.toFloat() / target * 3).roundToInt()) else 0
                    val bg = when { d > today -> Color.Transparent; lvl == 0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (d < start) .4f else 1f); else -> color.copy(alpha = .25f + lvl * .1875f) }
                    Box(Modifier.size(cell.dp).clip(RoundedCornerShape(2.dp)).background(bg)
                        .then(if (d == today) Modifier.border(1.dp, color, RoundedCornerShape(2.dp)) else Modifier)
                        .then(if (onToggle != null && d <= today) Modifier.clickable { onToggle(d) }.semantics { contentDescription = "${fmtKey(d, "EEE d MMM yyyy")}: ${if (n >= target) "done" else if (n > 0) "$n of $target" else "not done"}" } else Modifier))
                }
            }
        }
    }
}

@Composable
fun HabitsScreen(data: AppData?, now: Long, zone: ZoneId, ui: HabitsUi, onUi: (HabitsUi) -> Unit, actions: HabitActions,
                 onOpen: (Long) -> Unit, pendingAdd: Boolean = false, onPendingConsumed: () -> Unit = {}) {
    if (data == null) { Box(Modifier.fillMaxSize().padding(16.dp)) { CircularProgressIndicator() }; return }
    val today = Habits.localKey(now, zone)
    val counts = remember(data.habits) { countsOf(data.habits) }
    val list = data.habits.list.filterNot { it.archived }.sortedBy { it.position }
    val archived = data.habits.list.filter { it.archived }
    val rows = list.map { it to statsOf(it, counts[it.id].orEmpty(), today, zone) }
    val due = rows.filter { it.second.due }
    val rest = rows.filterNot { it.second.due }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pendingAdd) { if (pendingAdd) { onUi(ui.copy(adding = true, editing = null)); onPendingConsumed() } }
    fun check(h: HabitDto, back: Boolean) {
        val c = counts[h.id].orEmpty()
        val was = statsOf(h, c, today, zone)
        val count = nextCount(c[today] ?: 0, h.target, back)
        val after = statsOf(h, c + (today to count), today, zone)
        if (after.todayDone && !was.todayDone && after.streak > was.streak && after.streak in MILESTONES)
            actions.message("${Habits.streakLabel(after.streak, after.unit)} in a row: ${h.name}. Keep going.")
        scope.launch { actions.setCount(h.id, today, count) }
    }
    fun toggle(h: HabitDto, day: String) {
        if (day > today) return
        scope.launch { actions.setCount(h.id, day, toggledCount(counts[h.id]?.get(day) ?: 0, h.target)) }
    }
    fun create(body: JsonObject) = scope.launch { actions.create(body)?.let { onUi(ui.copy(adding = false, editing = null, draft = "", preset = null)) } }
    @Composable fun item(h: HabitDto, s: Habits.Stats) {
        if (ui.editing == h.id) HabitForm(h, null, list, onCancel = { onUi(ui.copy(editing = null)) }, actions = actions, onSaved = { onUi(ui.copy(editing = null)) })
        else if (ui.grid) HabitCard(h, s, counts[h.id].orEmpty(), today, zone, { check(h, it) }, { onOpen(h.id) }) { toggle(h, it) }
        else HabitRow(h, s, counts[h.id].orEmpty(), today, { check(h, it) }, { onOpen(h.id) }, { toggle(h, it) }) { onUi(ui.copy(editing = h.id)) }
    }

    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("habits-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("toolbar") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!ui.grid, { onUi(ui.copy(grid = false)) }, { Text("List") }, modifier = Modifier.testTag("habits-view-list"))
                FilterChip(ui.grid, { onUi(ui.copy(grid = true)) }, { Text("Grid") }, modifier = Modifier.testTag("habits-view-grid"))
                rows.filter { it.second.streak > 0 }.maxByOrNull { it.second.streak }?.let { (h, s) ->
                    Text("Longest streak: ${h.name}, ${Habits.streakLabel(s.streak, s.unit)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.weight(1f))
                }
            }
        }
        if (list.isEmpty() && !ui.adding && ui.editing != -1L) item("empty") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Build habits that stick. Add one, tick it off each day, and watch the grid fill in.", fontWeight = FontWeight.Bold)
                Text("Try “Gym mon wed fri”, “Read 20 pages 3x a week” or “💧 Water 8 glasses a day”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (list.isNotEmpty()) item("today-heading") {
            Column {
                Row { Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(fmtKey(today, "EEEE d MMMM"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        items(due, key = { "due:${it.first.id}" }) { (h, s) -> item(h, s) }
        if (list.isNotEmpty() && due.isEmpty()) item("nothing-due") { Text("Nothing due today.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item("add") {
            when {
                ui.editing == -1L -> HabitForm(null, ui.preset?.let { runCatching { ApiClient.json.decodeFromString<HabitDto>(it) }.getOrNull() }, list,
                    onCancel = { onUi(ui.copy(editing = null, preset = null)) }, actions = actions, onSaved = { onUi(ui.copy(editing = null, preset = null, adding = false, draft = "")) })
                ui.adding -> QuickAdd(ui.draft, { onUi(ui.copy(draft = it)) }, onCancel = { onUi(ui.copy(adding = false, draft = "")) },
                    onMore = { p -> onUi(ui.copy(adding = false, editing = -1L, preset = ApiClient.json.encodeToString(HabitDto.serializer(),
                        HabitDto(0, p.name, p.icon, "blue", p.schedule ?: HabitScheduleDto("daily"), p.target, p.unit, null, 0, false, 0)))) },
                    onAdd = { p ->
                        if (p.name.isEmpty()) actions.message("Give the habit a name.")
                        else create(habitBody(p.name, p.icon.orEmpty(), COLORS[data.habits.list.size % (COLORS.size - 1)], p.schedule ?: HabitScheduleDto("daily"), p.target, p.unit.orEmpty(), null))
                    })
                else -> TextButton({ onUi(ui.copy(adding = true, editing = null)) }, Modifier.testTag("habits-add")) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Add habit") }
            }
        }
        if (rest.isNotEmpty()) {
            item("rest-heading") {
                Column {
                    Row { Text("Not due today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("${rest.size}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            items(rest, key = { "rest:${it.first.id}" }) { (h, s) -> item(h, s) }
        }
        if (archived.isNotEmpty()) {
            item("archived-heading") {
                TextButton({ onUi(ui.copy(showArchived = !ui.showArchived)) }, Modifier.testTag("habits-archived")) {
                    Text("Archived ${archived.size}", fontWeight = FontWeight.Bold); Icon(if (ui.showArchived) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            if (ui.showArchived) items(archived, key = { "arch:${it.id}" }) { h ->
                if (ui.editing == h.id) HabitForm(h, null, list, onCancel = { onUi(ui.copy(editing = null)) }, actions = actions, onSaved = { onUi(ui.copy(editing = null)) })
                else Row(Modifier.fillMaxWidth().clickable { onOpen(h.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(paletteColor(h.color), CircleShape)); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(h.icon?.takeIf { it.isNotBlank() }, h.name).joinToString(" "))
                        Text("${Habits.scheduleLabel(h.schedule)} · ${plural(statsOf(h, counts[h.id].orEmpty(), today, zone).total, "day")} done", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ scope.launch { actions.update(h.id, buildJsonObject { put("archived", false) })?.let { actions.message("Restored “${h.name}”.") } } }, Modifier.testTag("habit-restore-${h.id}")) { Text("Restore") }
                }
            }
        }
    }
}

@Composable
private fun HabitRow(h: HabitDto, s: Habits.Stats, counts: Map<String, Int>, today: String, onCheck: (Boolean) -> Unit, onOpen: () -> Unit, onToggle: (String) -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("habit-row-${h.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HabitCheck(h, s, onCheck)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(onClickLabel = "Stats and settings", onClick = onOpen)) {
                Text(listOfNotNull(h.icon?.takeIf { it.isNotBlank() }, h.name).joinToString(" "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (s.todayDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Meta(h, s)
            }
            IconButton(onEdit) { Icon(Icons.Outlined.Edit, "Edit ${h.name}") }
        }
        Box(Modifier.padding(start = 46.dp, top = 4.dp)) { WeekStrip(h, counts, today, onToggle) }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun HabitCard(h: HabitDto, s: Habits.Stats, counts: Map<String, Int>, today: String, zone: ZoneId, onCheck: (Boolean) -> Unit, onOpen: () -> Unit, onToggle: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).padding(12.dp).testTag("habit-card-${h.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClickLabel = "Stats and settings", onClick = onOpen)) {
                Text(listOfNotNull(h.icon?.takeIf { it.isNotBlank() }, h.name).joinToString(" "), fontWeight = FontWeight.Bold)
                Meta(h, s)
            }
            HabitCheck(h, s, onCheck)
        }
        Cells(h, counts, today, startOf(h, zone), GRID_WEEKS, onToggle)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAdd(draft: String, onDraft: (String) -> Unit, onCancel: () -> Unit, onMore: (Habits.Parsed) -> Unit, onAdd: (Habits.Parsed) -> Unit) {
    val p = remember(draft) { Habits.parseHabit(draft) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("habits-quick")) {
        OutlinedTextField(draft, { onDraft(it.take(120)) }, placeholder = { Text("e.g. Gym mon wed fri · Read 3x a week · 💧 Water 8 glasses a day") },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (draft.isNotBlank()) onAdd(p) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("habits-quick-input"))
        // What was understood, as chips.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("habits-quick-chips")) {
            p.icon?.let { AssistChip({}, { Text(it) }) }
            (p.chips.ifEmpty { listOf("Every day") }).forEach { AssistChip({}, { Text(it) }, enabled = p.chips.isNotEmpty()) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onMore(p) }, Modifier.testTag("habits-quick-more")) { Text("More options") }
            Spacer(Modifier.weight(1f))
            TextButton(onCancel) { Text("Cancel") }
            Button({ onAdd(p) }, enabled = draft.isNotBlank(), modifier = Modifier.testTag("habits-quick-add")) { Text("Add") }
        }
    }
}


/** Add or edit a habit (`form()` on the web). Reorder lives here too. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitForm(h: HabitDto?, preset: HabitDto?, active: List<HabitDto>, onCancel: () -> Unit, actions: HabitActions, onSaved: () -> Unit, onDeleted: () -> Unit = {}) {
    val v = h ?: preset
    val key = h?.id ?: -1L
    var name by rememberSaveable(key) { mutableStateOf(v?.name.orEmpty()) }
    var icon by rememberSaveable(key) { mutableStateOf(v?.icon.orEmpty()) }
    var color by rememberSaveable(key) { mutableStateOf(v?.color?.takeIf { it in COLORS } ?: "blue") }
    var type by rememberSaveable(key) { mutableStateOf(v?.schedule?.type ?: "daily") }
    var days by rememberSaveable(key) { mutableStateOf(v?.schedule?.days?.takeIf { v.schedule.type == "days" } ?: listOf(1, 2, 3, 4, 5)) }
    var n by rememberSaveable(key) { mutableStateOf((v?.schedule?.n ?: if (type == "interval") 2 else 3).toString()) }
    var target by rememberSaveable(key) { mutableStateOf((v?.target ?: 1).coerceAtLeast(1).toString()) }
    var unit by rememberSaveable(key) { mutableStateOf(v?.unit.orEmpty()) }
    var remindAt by rememberSaveable(key) { mutableStateOf(v?.remind_at) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pickerTheme = if (MaterialTheme.colorScheme.background.luminance() < .5f) com.knhdixh.attendance80.R.style.HomeworkPickerDark else com.knhdixh.attendance80.R.style.HomeworkPickerLight
    val accent = paletteColor(color)
    Column(Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = .5f), RoundedCornerShape(8.dp)).padding(12.dp).testTag("habit-form"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(icon, { icon = it.take(8) }, placeholder = { Text("🙂") }, singleLine = true, modifier = Modifier.width(72.dp).semantics { contentDescription = "Emoji (optional)" })
            OutlinedTextField(name, { name = it.take(60) }, placeholder = { Text("Habit name, e.g. Read") }, singleLine = true, modifier = Modifier.weight(1f).testTag("habit-form-name"))
        }
        Text("Repeat", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SCHEDULES.forEach { (k, l) -> FilterChip(type == k, { type = k }, { Text(l) }, modifier = Modifier.testTag("habit-form-type-$k")) } }
        if (type == "days") FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Habits.DOW.forEachIndexed { i, d -> FilterChip((i + 1) in days, { days = if ((i + 1) in days) days - (i + 1) else (days + (i + 1)).sorted() }, { Text(d) }, modifier = Modifier.testTag("habit-form-day-${i + 1}")) }
        }
        if (type in setOf("weekly", "monthly", "interval")) OutlinedTextField(n, { n = it.filter(Char::isDigit).take(3) }, label = { Text("How often") },
            suffix = { Text(when (type) { "weekly" -> "times a week"; "monthly" -> "times a month"; else -> "days apart" }) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("habit-form-n"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(target, { target = it.filter(Char::isDigit).take(2) }, label = { Text("Each day") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.width(110.dp).testTag("habit-form-target"))
            OutlinedTextField(unit, { unit = it.take(20) }, label = { Text("Unit") }, placeholder = { Text("times") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({
                val t = remindAt?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(20, 0)
                TimePickerDialog(context, pickerTheme, { _, hh, mm -> remindAt = "%02d:%02d".format(hh, mm) }, t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
            }, Modifier.testTag("habit-form-remind")) {
                Text("Remind me: ${remindAt?.let { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.parse(it)) } ?: "off"}")
            }
            if (remindAt != null) TextButton({ remindAt = null }) { Text("Clear") }
        }
        Text("on days it's due, if not done yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            COLORS.forEach { c -> Box(Modifier.size(26.dp).clip(CircleShape).background(paletteColor(c)).border(if (c == color) 2.5.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                .clickable { color = c }.semantics { contentDescription = c; selected = c == color }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button({
                val schedule = when (type) { "days" -> HabitScheduleDto("days", days = days); "daily" -> HabitScheduleDto("daily"); else -> HabitScheduleDto(type, n = n.toIntOrNull() ?: 1) }
                if (type == "days" && days.isEmpty()) { actions.message("Pick at least one day."); return@Button }
                val body = habitBody(name, icon, color, schedule, target.toIntOrNull() ?: 1, unit, remindAt)
                scope.launch { if ((if (h == null) actions.create(body) else actions.update(h.id, body)?.also { actions.message("Habit saved.") }) != null) onSaved() }
            }, enabled = name.isNotBlank(), modifier = Modifier.testTag("habit-form-save")) { Text(if (h == null) "Add habit" else "Save") }
            OutlinedButton(onCancel) { Text("Cancel") }
            if (h != null) {
                if (!h.archived) {
                    val i = active.indexOfFirst { it.id == h.id }
                    fun move(delta: Int) { val j = i + delta; if (i < 0 || j !in active.indices) return
                        val ids = active.map { it.id }.toMutableList().apply { add(j, removeAt(i)) }; scope.launch { actions.order(ids) } }
                    if (i > 0) TextButton({ move(-1) }, Modifier.testTag("habit-form-up")) { Text("Move up") }
                    if (i in 0 until active.size - 1) TextButton({ move(1) }, Modifier.testTag("habit-form-down")) { Text("Move down") }
                }
                TextButton({ scope.launch { actions.update(h.id, buildJsonObject { put("archived", !h.archived) })?.let {
                    actions.message(if (h.archived) "Restored “${h.name}”." else "Archived “${h.name}”. Its history is kept."); onSaved() } } }, Modifier.testTag("habit-form-archive")) { Text(if (h.archived) "Restore" else "Archive") }
                TextButton({ confirmDelete = true }, Modifier.testTag("habit-form-delete")) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDelete && h != null) AlertDialog(onDismissRequest = { confirmDelete = false }, text = { Text("Delete “${h.name}” and all its check-ins? Archiving keeps the history instead.") },
        confirmButton = { TextButton({ confirmDelete = false; scope.launch { if (actions.delete(h.id)) { actions.message("Deleted “${h.name}”."); onDeleted() } } }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}

/** A habit's page: streaks, the 30-day rate, a year heatmap, per month and per weekday. */
@Composable
fun HabitDetail(data: AppData?, id: Long, now: Long, zone: ZoneId, actions: HabitActions, onBack: () -> Unit) {
    if (data == null) { Box(Modifier.fillMaxSize().padding(16.dp)) { CircularProgressIndicator() }; return }
    val h = data.habits.list.find { it.id == id }
    if (h == null) { Column(Modifier.padding(16.dp)) { Text("This habit is gone."); TextButton(onBack) { Text("All habits") } }; return }
    val today = Habits.localKey(now, zone)
    val counts = remember(data.habits) { countsOf(data.habits)[h.id].orEmpty() }
    val s = statsOf(h, counts, today, zone)
    val target = maxOf(1, h.target)
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val color = paletteColor(h.color)
    val active = data.habits.list.filterNot { it.archived }.sortedBy { it.position }
    fun check(back: Boolean) {
        val count = nextCount(counts[today] ?: 0, h.target, back)
        val after = statsOf(h, counts + (today to count), today, zone)
        if (after.todayDone && !s.todayDone && after.streak > s.streak && after.streak in MILESTONES) actions.message("${Habits.streakLabel(after.streak, after.unit)} in a row: ${h.name}. Keep going.")
        scope.launch { actions.setCount(h.id, today, count) }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("habit-detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item("back") { TextButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("All habits") } }
        item("hero") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(listOfNotNull(h.icon?.takeIf { it.isNotBlank() }, h.name).joinToString(" "), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (target > 1) {
                    OutlinedButton({ check(true) }, enabled = s.today > 0, modifier = Modifier.semantics { contentDescription = "One less" }.testTag("habit-minus")) { Text("−") }
                    Text("${s.today}/$target${h.unit?.let { " $it" }.orEmpty()}", fontWeight = FontWeight.Bold)
                }
                HabitCheck(h, s, { check(it) }, 40)
            }
            Text(if (s.todayDone) "Done today" else if (s.due) "Due today" else "Not due today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (editing) item("form") { HabitForm(h, null, active, onCancel = { editing = false }, actions = actions, onSaved = { editing = false }, onDeleted = onBack) }
        item("stats") {
            Row(Modifier.fillMaxWidth()) {
                @Composable fun tile(n: String, l: String) = Column(Modifier.weight(1f)) { Text(n, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                tile("${s.streak} ${s.unit}${if (s.streak == 1) "" else "s"}", "current streak")
                tile("${s.best} ${s.unit}${if (s.best == 1) "" else "s"}", "best streak")
                tile(s.rate?.let { "${(it * 100).roundToInt()}%" } ?: "–", "last 30 days")
                tile("${s.total}", if (target > 1) "days done · ${s.checkins} ${h.unit ?: "check-ins"}" else "days done")
            }
        }
        item("heat") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text("Last 12 months", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("tap a day to change it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Cells(h, counts, today, s.start, HEAT_WEEKS, { d -> scope.launch { actions.setCount(h.id, d, toggledCount(counts[d] ?: 0, h.target)) } }, cell = 13)
                if (data.habits.since.isNotEmpty() && data.habits.since > startOf(h, zone)) Text("Stats cover the last 13 months.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item("months") {
            val months = Habits.monthTotals(h, counts, today)
            val top = maxOf(1, months.maxOf { it.days })
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Days done per month", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    months.forEach { m ->
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${fmtKey("${m.month}-01", "MMMM yyyy")}: ${plural(m.days, "day")}" },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            if (m.days > 0) Box(Modifier.fillMaxWidth().height((80f * m.days / top).coerceAtLeast(3f).dp).background(if (m.month == Habits.monthOf(today)) color else color.copy(alpha = .55f), RoundedCornerShape(2.dp)))
                            Text(fmtKey("${m.month}-01", "MMMMM"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item("weekdays") {
            val top = maxOf(1, s.weekdays.maxOrNull() ?: 0)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("By weekday", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                s.weekdays.forEachIndexed { i, n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Habits.DOW[i], Modifier.width(40.dp))
                        Box(Modifier.weight(1f).height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                            Box(Modifier.fillMaxWidth(n.toFloat() / top).fillMaxHeight().background(color, RoundedCornerShape(4.dp)))
                        }
                        Text(plural(n, "day"), Modifier.width(64.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (!editing) item("actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ editing = true }, Modifier.testTag("habit-edit")) { Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Edit") }
                TextButton({ scope.launch { actions.update(h.id, buildJsonObject { put("archived", !h.archived) })?.let {
                    actions.message(if (h.archived) "Restored “${h.name}”." else "Archived “${h.name}”. Its history is kept.") } } }, Modifier.testTag("habit-archive")) { Text(if (h.archived) "Restore" else "Archive") }
                TextButton({ confirmDelete = true }, Modifier.testTag("habit-delete")) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, text = { Text("Delete “${h.name}” and all its check-ins? Archiving keeps the history instead.") },
        confirmButton = { TextButton({ confirmDelete = false; scope.launch { if (actions.delete(h.id)) { actions.message("Deleted “${h.name}”."); onBack() } } }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}
