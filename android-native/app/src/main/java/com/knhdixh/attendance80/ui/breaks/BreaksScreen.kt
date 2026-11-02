package com.knhdixh.attendance80.ui.breaks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.theme.LocalExtraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The picked break, as `state.breaks` in `public/breaks-ui.js`. phase: which end the next tap sets. */
@Serializable
data class BreakPick(val from: String = "", val to: String = "", val label: String = "", val len: Int = 7,
                     val phase: String = "start", val keepEnd: Boolean = false, val month: String = "") {
    /** A tap on the calendar: the first day starts a one-day break, a last day before it starts over from there. */
    fun tap(d: String): BreakPick = when {
        phase == "start" -> { val keep = keepEnd && to.isNotEmpty() && d <= to; copy(from = d, to = if (keep) to else d, phase = if (keep) "start" else "end", keepEnd = false) }
        d < from -> copy(from = d, to = d)
        else -> copy(to = d, phase = "start")
    }
    fun use(from: String, to: String) = copy(from = from, to = to, phase = "start", keepEnd = false, month = from.take(7))
    fun clear() = copy(from = "", to = "", label = "", phase = "start", keepEnd = false)
}

val LENGTHS = listOf(1 to "1 day", 3 to "Long weekend", 7 to "1 week", 9 to "9 days", 14 to "2 weeks")
private val VERDICT = mapOf("free" to "No classes to miss", "ok" to "Safe", "risk" to "Possible, but leaves no spare skips", "fail" to "Not possible")
private val DAY_VERDICT = mapOf("free" to "no classes", "ok" to "safe to miss", "risk" to "tight: uses your last spare skip", "fail" to "can't miss")

data class BreaksBase(val data: AppData, val now: Long, val zone: ZoneId, val model: BreakModel, val days: Map<String, StartDayGridItem>)
data class BreaksState(val base: BreaksBase, val len: Int, val from: String, val to: String, val windows: BreakWindowsResult, val sim: BreakSimulation?)

class BreaksViewModel : ViewModel() {
    private val mutable = MutableStateFlow<BreaksState?>(null)
    val state = mutable.asStateFlow()
    private var base: BreaksBase? = null
    private var generation = 0
    fun update(data: AppData?, now: Long, zone: ZoneId, len: Int, from: String, to: String) {
        val version = ++generation
        if (data == null) { mutable.value = null; return }
        val current = mutable.value
        if (current != null && current.base.data == data && current.base.now == now && current.base.zone == zone && current.len == len && current.from == from && current.to == to) return
        viewModelScope.launch {
            // startDayGrid runs one simulation per day: keep it off the main thread, and only when the data changes.
            val result = withContext(Dispatchers.Default) {
                val b = base?.takeIf { it.data == data && it.now == now && it.zone == zone } ?: derive(data, now, zone)
                b to state(b, len, from, to)
            }
            if (generation == version) { base = result.first; mutable.value = result.second }
        }
    }
    companion object {
        fun derive(data: AppData, now: Long, zone: ZoneId): BreaksBase {
            val m = breakModel(data.courses, data.events, data.user.threshold, data.user.unit, now, zone.id)
            val days = startDayGrid(m, 1).flatten().filter { !it.past && !it.beyond }.associateBy { it.date }
            return BreaksBase(data, now, zone, m, days)
        }
        fun state(b: BreaksBase, len: Int, from: String, to: String) = BreaksState(b, len, from, to,
            findBreakWindows(b.model, len, limit = 4),
            if (from.isNotEmpty() && to >= from) simulateBreak(b.model, from, to) else null)
    }
}

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
private fun fmtN(x: Double): String = if (x == x.roundToInt().toDouble()) x.roundToInt().toString() else ((x * 10).roundToInt() / 10.0).toString()
private fun pct(x: Double) = "${fmtN((x * 1000).roundToInt() / 10.0)}%"
private fun dayLabel(d: String): String = LocalDate.parse(d).format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
fun rangeLabel(from: String, to: String) = if (from == to) dayLabel(from) else "${dayLabel(from)} – ${dayLabel(to)}"
private fun skipsLeft(s: AttendanceStats, unit: String): String =
    if (s.remaining < -1e-9) "over by ${fmtN(ceil(-s.remaining * 2) / 2)}" else "${fmtN(s.remainingDisplay)}${if (unit == "hours") " h" else ""} left"
private fun addMonths(month: String, n: Int) = LocalDate.parse("$month-01").plusMonths(n.toLong()).toString().take(7)

@Composable
private fun levelColor(level: String): Color {
    val x = LocalExtraColors.current
    return when (level) { "ok" -> x.green; "risk" -> x.orange; "fail" -> x.red; else -> MaterialTheme.colorScheme.outline }
}

/** Breaks: a month calendar is the date-range picker, with suggestions, the verdict and saved breaks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BreaksScreen(data: AppData?, now: Long, zone: ZoneId, pick: BreakPick, onPick: (BreakPick) -> Unit,
                 onSave: suspend (BreakPick) -> Boolean, onRemove: suspend (BreakDto) -> Unit,
                 model: BreaksViewModel = viewModel()) {
    LaunchedEffect(data, now, zone, pick.len, pick.from, pick.to) { model.update(data, now, zone, pick.len, pick.from, pick.to) }
    val st by model.state.collectAsState()
    val s = st?.takeIf { data != null && it.base.data == data && it.base.zone == zone }
    if (data == null || s == null) { Box(Modifier.fillMaxSize().padding(16.dp)) { CircularProgressIndicator() }; return }
    val m = s.base.model
    val saved = data.breaks
    val scope = rememberCoroutineScope()
    var confirmFail by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<BreakDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    val minMonth = m.today.take(7)
    val maxMonth = (if (m.lastDay < m.today) m.today else m.lastDay).take(7)
    val month = (pick.month.ifEmpty { (pick.from.ifEmpty { m.today }).take(7) }).coerceIn(minMonth, maxMonth)
    fun save() { scope.launch { busy = true; if (onSave(pick)) onPick(pick.clear()); busy = false } }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()

    LazyColumn(Modifier.fillMaxSize().testTag("breaks-list"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("calendar") { MonthCard(m, s.base.days, saved, pick, month, minMonth, maxMonth, onPick) }
        item("panel") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("start", "end").forEachIndexed { i, which ->
                        if (i == 1) Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val value = if (which == "start") pick.from else pick.from.takeIf { it.isNotEmpty() && !(pick.phase == "end" && pick.to == pick.from) }?.let { pick.to }.orEmpty()
                        val active = pick.phase == which
                        Surface(onClick = {
                            val keep = which == "start" && pick.from.isNotEmpty()
                            onPick(pick.copy(phase = which, keepEnd = keep, month = if (pick.from.isNotEmpty()) (if (which == "start") pick.from else pick.to).take(7) else pick.month))
                        }, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).testTag("break-pill-$which"),
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(if (which == "start") "First day off" else "Last day off", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value.takeIf { it.isNotEmpty() }?.let(::dayLabel) ?: "Pick a day", color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                Text(when { pick.from.isEmpty() -> "Tap your first day off on the calendar."; pick.phase == "end" -> "Now tap your last day off, or keep it to one day."; else -> "Tap a day to start a new break." },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                // The verdict (and Save) belong to the range it was worked out for; right after a tap it is still the previous pick's.
                val current = s.len == pick.len && s.from == pick.from && s.to == pick.to
                if (current) s.sim?.let { sim -> SimulationPanel(sim, pick, data.user.unit, data, zone, busy, onPick, onSubmit = { if (sim.level == "fail") confirmFail = true else save() }) }
                else if (pick.from.isNotEmpty() && pick.to >= pick.from) Text("Working it out…", Modifier.testTag("break-pending"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item("suggestions-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Suggestions", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LENGTHS.forEach { (d, l) -> FilterChip(selected = pick.len == d, onClick = { onPick(pick.copy(len = d)) }, label = { Text(l) }, modifier = Modifier.testTag("break-len-$d")) }
                }
            }
        }
        if (s.windows.windows.isEmpty()) item("suggestions-empty") {
            val lenWord = if (pick.len == 1) "day off" else "${pick.len}-day break"
            Column {
                Text("No safe $lenWord left in your calendar." + (s.windows.longest?.let { " The longest is ${rangeLabel(it.from, it.to)} (${plural(it.days, "day")})." } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.windows.longest?.let { l -> TextButton(onClick = { onPick(pick.use(l.from, l.to)) }) { Text("Show ${rangeLabel(l.from, l.to)}") } }
            }
        }
        items(s.windows.windows, key = { "window:${it.from}:${it.to}" }) { w ->
            val picked = w.from == pick.from && w.to == pick.to
            Column(Modifier.fillMaxWidth().background(if (picked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable { onPick(pick.use(w.from, w.to)) }.padding(horizontal = 4.dp, vertical = 10.dp).testTag("break-window-${w.from}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(levelColor(w.level), CircleShape)); Spacer(Modifier.width(8.dp))
                    Text(rangeLabel(w.from, w.to))
                }
                Text(listOfNotNull(if (w.sessions > 0) "skips ${plural(w.sessions, "session")}" else "no classes missed",
                    w.holidayDays.takeIf { it > 0 }?.let { plural(it, "holiday day") }, "no spare skips after".takeIf { w.level == "risk" }).joinToString(" · "),
                    Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (saved.isNotEmpty()) {
            item("saved-heading") { Row { Text("Saved breaks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text("${saved.size}", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(saved, key = { "break:${it.id}" }) { b ->
                Row(Modifier.fillMaxWidth().testTag("saved-break-${b.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onPick(pick.use(b.start_date, b.end_date)) }.padding(vertical = 10.dp)) {
                        Text(b.label.ifBlank { "Break" })
                        Text("${rangeLabel(b.start_date, b.end_date)} · ${plural(b.sessions, "session")} marked absent", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { removing = b }, enabled = !busy) { Icon(Icons.Outlined.Delete, contentDescription = "Remove ${b.label.ifBlank { "break" }}") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (confirmFail) AlertDialog(onDismissRequest = { confirmFail = false },
        text = { Text("This break would drop a course below the minimum or miss an exam/mandatory session. Save it anyway?") },
        confirmButton = { TextButton(onClick = { confirmFail = false; save() }) { Text("Save break") } },
        dismissButton = { TextButton(onClick = { confirmFail = false }) { Text("Cancel") } })
    removing?.let { b -> AlertDialog(onDismissRequest = { removing = null },
        text = { Text("Remove \"${b.label.ifBlank { "this break" }}\"? Its sessions go back to how they were marked before.") },
        confirmButton = { TextButton(onClick = { removing = null; scope.launch { busy = true; onRemove(b); busy = false } }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}

@Composable
private fun MonthCard(m: BreakModel, days: Map<String, StartDayGridItem>, saved: List<BreakDto>, pick: BreakPick, month: String,
                      minMonth: String, maxMonth: String, onPick: (BreakPick) -> Unit) {
    val first = LocalDate.parse("$month-01")
    val locale = Locale.getDefault()
    fun savedOn(d: String) = saved.find { d >= it.start_date && d <= it.end_date }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(first.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("breaks-month"))
            IconButton(enabled = month > minMonth, onClick = { onPick(pick.copy(month = addMonths(month, -1))) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous month") }
            TextButton(enabled = month != minMonth, onClick = { onPick(pick.copy(month = minMonth)) }) { Text("Today") }
            IconButton(enabled = month < maxMonth, onClick = { onPick(pick.copy(month = addMonths(month, 1))) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next month") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        val lead = first.dayOfWeek.value - 1
        val count = first.lengthOfMonth()
        val cells = List(lead) { null } + (1..count).map { first.withDayOfMonth(it).toString() }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (week + List(7 - week.size) { null }).forEach { d ->
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (d != null) DayCell(d, days[d], m, savedOn(d), pick, onPick)
                    }
                }
            }
        }
        val legend = listOf("ok" to "Safe to miss", "risk" to "Tight", "fail" to "Can't miss", "free" to "No classes")
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            legend.forEach { (level, text) -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(levelColor(level), CircleShape)); Spacer(Modifier.width(4.dp)); Text(text, style = MaterialTheme.typography.bodySmall) } }
            if (saved.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)); Spacer(Modifier.width(4.dp)); Text("Saved break", style = MaterialTheme.typography.bodySmall) }
        }
        if (m.noSkip.isNotEmpty()) Text("No spare absences left in ${m.noSkip.joinToString { it.name }}, so any day with one of their classes can't be missed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayCell(d: String, c: StartDayGridItem?, m: BreakModel, saved: BreakDto?, pick: BreakPick, onPick: (BreakPick) -> Unit) {
    val inBreak = pick.from.isNotEmpty() && d >= pick.from && d <= pick.to
    val today = d == m.today
    val weekend = LocalDate.parse(d).dayOfWeek.value >= 6
    val bg = if (inBreak) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val number = d.takeLast(2).trimStart('0')
    val shape = RoundedCornerShape(6.dp)
    val base = Modifier.fillMaxSize().background(bg, shape).then(if (today) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
    if (c == null) {
        val why = if (d < m.today) "Past" else "After the end of your calendar"
        Box(base.semantics { contentDescription = "$number: $why" }, contentAlignment = Alignment.Center) {
            Text(number, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val title = listOf("${dayLabel(d)}: ${DAY_VERDICT[c.level]}", if (c.sessions > 0) plural(c.sessions, "session") else "") + c.blockers +
        listOf(if (c.holiday) "School holiday" else "", saved?.let { "Saved break: ${it.label.ifBlank { "Break" }}" }.orEmpty())
    val description = title.filter { it.isNotEmpty() }.joinToString(", ")
    Column(base.clickable(role = Role.Button) { onPick(pick.tap(d)) }.semantics { contentDescription = description; selected = inBreak }.testTag("break-day-$d"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(number, fontWeight = if (today) FontWeight.Bold else null,
            color = if (weekend || c.holiday) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        Box(Modifier.padding(top = 2.dp).size(6.dp).background(levelColor(c.level), CircleShape)
            .then(if (saved != null) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier))
    }
}

@Composable
private fun SimulationPanel(sim: BreakSimulation, pick: BreakPick, unit: String, data: AppData, zone: ZoneId, busy: Boolean, onPick: (BreakPick) -> Unit, onSubmit: () -> Unit) {
    val level = if (sim.sessions.isNotEmpty() || sim.conflicts.isNotEmpty()) sim.level else "free"
    val facts = listOfNotNull(plural(sim.days, "day"), if (sim.sessions.isNotEmpty()) "skips ${plural(sim.sessions.size, "session")}" else "no classes missed",
        sim.holidayDays.takeIf { it > 0 }?.let { plural(it, "holiday day") })
    val groups = remember(data.events) { courseCalendars(data.events) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("break-result").semantics { stateDescription = "${pick.from} to ${pick.to}" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(VERDICT[level].orEmpty(), color = levelColor(level), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("break-verdict"))
            TextButton(onClick = { onPick(pick.clear()) }) { Text("Clear") }
        }
        Text(facts.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (sim.conflicts.isNotEmpty()) {
            Text("Can't miss:", fontWeight = FontWeight.Bold)
            val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())
            sim.conflicts.forEach { e -> Text("• ${e.summary}, ${fmt.format(Instant.ofEpochMilli(e.start_at).atZone(zone))}", style = MaterialTheme.typography.bodyMedium) }
        }
        sim.courses.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(c.course.name)
                    val label = groupLabel(groups[c.course.id], data.calendars)
                    if (label.isNotBlank() && label != "All groups") Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${skipsLeft(c.before, unit)} → ${skipsLeft(c.after, unit)}", style = MaterialTheme.typography.bodySmall, color = levelColor(c.level))
                }
                Text("−${c.skipped}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pct(c.after.projectedRate), fontWeight = FontWeight.Bold, color = levelColor(c.level))
            }
        }
        if (sim.sessions.isNotEmpty()) {
            OutlinedTextField(pick.label, { onPick(pick.copy(label = it.take(100))) }, placeholder = { Text("Name, e.g. Trip home") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("break-label").semantics { contentDescription = "Name" })
            Button(onClick = onSubmit, enabled = !busy, modifier = Modifier.testTag("break-save"),
                colors = if (level == "fail") ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) { Text("Save break") }
            Text("Marks ${plural(sim.sessions.size, "session")} as planned absences. You can remove it later.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
