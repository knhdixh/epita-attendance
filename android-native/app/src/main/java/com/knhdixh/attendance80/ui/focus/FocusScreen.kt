package com.knhdixh.attendance80.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.data.FocusHistoryStatus
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Serializable
private data class Draft(
    val focus: String, val short: String, val long: String, val longEvery: String, val goal: String,
    val autoBreaks: Boolean, val autoFocus: Boolean, val sound: Boolean, val notify: Boolean,
    val tasks: List<String>, val tags: List<String>, val taskInput: String = "", val tagInput: String = "",
) {
    fun valid() = focus.toIntOrNull() in 1..180 && short.toIntOrNull() in 1..60 && long.toIntOrNull() in 1..90 && longEvery.toIntOrNull() in 2..12 && goal.toIntOrNull() in 1..24
    fun body() = buildJsonObject {
        put("focus", focus.toInt()); put("short", short.toInt()); put("long", long.toInt()); put("longEvery", longEvery.toInt()); put("goal", goal.toInt())
        put("autoBreaks", autoBreaks); put("autoFocus", autoFocus); put("sound", sound); put("notify", notify)
        put("tasks", JsonArray(cleanTasks(tasks).map(::JsonPrimitive))); put("tags", JsonArray(cleanTags(tags, MAX_TAGS).map(::JsonPrimitive)))
    }
}
private fun FocusSettingsDto.draft() = Draft(focus.toString(), short.toString(), long.toString(), longEvery.toString(), goal.toString(), autoBreaks, autoFocus, sound, notify, tasks, tags)
private fun dur(minutes: Double): String { val m = minutes.roundToInt(); return if (m < 60) "$m min" else "${m / 60} h" + if (m % 60 == 0) "" else " %02d min".format(m % 60) }
private fun hrs(minutes: Double): String = if (minutes < 60) "${minutes.roundToInt()} min" else "${java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format((minutes / 6).roundToInt() / 10.0)} h"
private fun label(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(ms).atZone(zone))
private fun recentDay(ms: Long, now: Long, zone: ZoneId): String {
    val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val current = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) { current -> "Today"; current.minusDays(1) -> "Yesterday"; else -> DateTimeFormatter.ofPattern("EEE, d MMM").format(day) }
}
private fun time(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(zone))
private fun plural(n: Int) = "$n pomodoro${if (n == 1) "" else "s"}"
internal fun focusHeatLevel(minutes: Double, goalMinutes: Int): Int = when {
    minutes <= 0 -> 0
    minutes < goalMinutes / 4.0 -> 1
    minutes < goalMinutes / 2.0 -> 2
    minutes < goalMinutes -> 3
    else -> 4
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusScreen(data: AppData?, owner: String?, now: Long, zone: ZoneId, history: FocusHistoryStatus,
    listState: LazyListState, storedDraft: String?, onDraft: (String?) -> Unit,
    onHistory: suspend (Long) -> Unit, onSave: suspend (JsonObject) -> Boolean,
    onDelete: suspend (Long) -> Boolean, message: suspend (String) -> Unit,
    pending: PaletteAction.Focus?, onPending: (PaletteAction.Focus) -> Unit,
    onSaveList: suspend (JsonObject) -> FocusSettingsDto? = { null },
    timerState: com.knhdixh.attendance80.ui.shell.FocusTimerState? = null, timerActions: TimerActions? = null, onConsumed: () -> Unit = {}, timerNow: (() -> Long)? = null) {
    var range by rememberSaveable(owner) { mutableIntStateOf(7) }
    var deleting by rememberSaveable(owner) { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestDraft by rememberUpdatedState(storedDraft)
    val latestOwner by rememberUpdatedState(owner)
    val first = addDays(weekStart(now, zone), -52 * 7, zone)
    // A palette command ("Pause timer", "Focus on: …") runs once, then clears itself.
    LaunchedEffect(pending, timerActions != null, timerState != null) {
        val actions = timerActions; val state = timerState
        if (pending == null || actions == null || state == null) return@LaunchedEffect
        val t = state.timer
        when (pending.command) {
            // The controller decides from its own state (the UI's copy can still be the default before load()).
            "start" -> actions.start()
            "pause" -> actions.pause()
            "resume" -> actions.resume()
            "skip" -> if (t.running() || t.next != "focus") actions.skip()
            "stop" -> actions.stop()
            "target" -> {
                val keep = t.target?.tags.orEmpty()
                val hw = pending.homeworkId?.let { id -> data?.homework?.firstOrNull { it.id == id && !it.done } }
                if (hw != null) actions.focusOn(FocusTarget(homework_id = hw.id, course_id = hw.course_id, label = hw.title, tags = keep))
                else if (pending.task != null) actions.focusOn(FocusTarget(label = pending.task, tags = keep))
            }
        }
        if (pending.command != "new-task") onConsumed()
    }
    LaunchedEffect(owner, first, data?.focus?.since, history.refreshVersion) { if (owner != null && data != null && data.focus.since > first) onHistory(first) }
    if (data == null) { Box(Modifier.fillMaxSize().padding(16.dp)) { CircularProgressIndicator() }; return }
    val settings = data.focus.settings
    val draft = storedDraft?.let { runCatching { ApiClient.json.decodeFromString<Draft>(it) }.getOrNull() } ?: settings.draft()
    fun edit(d: Draft) = onDraft(ApiClient.json.encodeToString(d))
    fun saveList(next: List<String>, taskList: Boolean) {
        val submittedOwner = owner
        val before = draft
        val key = if (taskList) "tasks" else "tags"
        val body = buildJsonObject { put(key, JsonArray(next.map(::JsonPrimitive))) }
        scope.launch {
            saving = true
            val saved = onSaveList(body)
            saving = false
            if (saved != null && latestOwner == submittedOwner) {
                val current = latestDraft?.let { runCatching { ApiClient.json.decodeFromString<Draft>(it) }.getOrNull() } ?: before
                edit(if (taskList) current.copy(tasks = saved.tasks, taskInput = if (current.taskInput == before.taskInput) "" else current.taskInput)
                    else current.copy(tags = saved.tags, tagInput = if (current.tagInput == before.tagInput) "" else current.tagInput))
            }
        }
    }
    val sessions = data.focus.sessions
    val st = remember(sessions, now, range, zone) { focusStats(sessions, now, range, zone) }
    val totals = remember(sessions, zone) { dailyTotals(sessions, zone) }
    val today = dayStart(now, zone)
    val hasYear = data.focus.since <= first
    val goalMin = settings.goal * settings.focus
    val top = maxOf(goalMin.toDouble(), st.week.maxOfOrNull { it.minutes } ?: 0.0, 1.0)
    val courseNames = data.courses.associate { it.id to it.name }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().imePadding().testTag("focus-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (timerState != null && timerActions != null) item("timer") {
            TimerPanel(timerState.timer, timerState.settings, data, timerState.pendingTasks, timerNow ?: { now }, timerActions, startWithNewTask = pending?.command == "new-task")
        } else if (pending != null) item("boundary") { Text("Focus request saved for the timer.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item("today") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                Text("${dur(st.today.minutes)} focused today · ${st.today.count}/${settings.goal} pomodoros")
                Text("${st.streak} ${if (st.streak == 1) "day" else "days"} streak · ${hrs(st.thisWeek)} this week")
                if (st.thisWeek != 0.0 || st.lastWeek != 0.0) Text("${if (st.thisWeek >= st.lastWeek) "+" else "−"}${hrs(kotlin.math.abs(st.thisWeek - st.lastWeek))} vs last week")
            }
        }
        item("week") {
            Column {
                Text("This week", style = MaterialTheme.typography.titleMedium)
                Text("goal ${hrs(goalMin.toDouble())}")
                Row(Modifier.fillMaxWidth().height(135.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    st.week.forEach { d ->
                        val weekday = DateTimeFormatter.ofPattern("EEE").format(Instant.ofEpochMilli(d.ms).atZone(zone))
                        val description = "$weekday ${label(d.ms, zone)}: ${if (d.minutes > 0) "${dur(d.minutes)} · ${plural(d.count)}" else "no focus"}"
                        Column(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = description }, verticalArrangement = Arrangement.Bottom) {
                            Box(Modifier.height((100 * d.minutes / top).toFloat().coerceAtLeast(if (d.minutes > 0) 2f else 0f).dp).width(22.dp).background(if (d.ms == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = .55f)))
                            Text(weekday, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item("range") {
            Column {
                Text("Where it went", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState())) { listOf(7 to "Week", 30 to "30 days", 365 to "Year").forEach { (n, title) -> FilterChip(selected = range == n, onClick = { range = n }, label = { Text(title) }, modifier = Modifier.padding(end = 6.dp).testTag("focus-range-$n")) } }
                val r = st.range
                if (range == 365 && !hasYear) {
                    Text(if (history.owner == owner && history.failed) "Couldn’t load the year." else "Loading the year…")
                } else {
                Text(if (r.minutes == 0.0) "No focus in this period yet." else "${hrs(r.minutes)} over ${r.activeDays} ${if (r.activeDays == 1) "day" else "days"}, about ${dur(r.perActiveDay)} on a day you focus${r.peakHour?.let { ", mostly around " + time(Instant.ofEpochMilli(now).atZone(zone).withHour(it).withMinute(0).toInstant().toEpochMilli(), zone) } ?: ""}.")
                if (r.courses.isNotEmpty()) { Text("By course", style = MaterialTheme.typography.titleSmall); r.courses.take(8).forEach { Text("${courseNames[it.course_id] ?: "No course"} · ${dur(it.minutes)}"); LinearProgressIndicator(progress = { (it.minutes / r.courses.first().minutes.coerceAtLeast(1.0)).toFloat() }, modifier = Modifier.fillMaxWidth()) } }
                if (r.tags.isNotEmpty()) { Text("By tag", style = MaterialTheme.typography.titleSmall); r.tags.take(8).forEach { Text("#${it.tag} · ${dur(it.minutes)}"); LinearProgressIndicator(progress = { (it.minutes / r.tags.first().minutes.coerceAtLeast(1.0)).toFloat() }, modifier = Modifier.fillMaxWidth()) } }
                if (r.homework.isNotEmpty()) { Text("By task", style = MaterialTheme.typography.titleSmall); r.homework.take(6).forEach { h ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${h.label ?: "Untitled"}${if (h.count > 0) " · ${plural(h.count)}" else ""} · ${dur(h.minutes)}", Modifier.weight(1f))
                        val open = h.homework_id != null && data.homework.any { it.id == h.homework_id && !it.done }
                        val task = h.homework_id == null && h.label in settings.tasks
                        if (open || task) TextButton(onClick = { onPending(PaletteAction.Focus("target", homeworkId = h.homework_id, task = if (task) h.label else null)) }) { Text("Focus on it again") }
                    }
                    LinearProgressIndicator(progress = { (h.minutes / r.homework.first().minutes.coerceAtLeast(1.0)).toFloat() }, modifier = Modifier.fillMaxWidth())
                } }
                }
            }
        }
        item("year") {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Last 12 months", style = MaterialTheme.typography.titleMedium)
                    st.best?.let { Text("best day ${dur(it.minutes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (!hasYear) Text(if (history.owner == owner && history.failed) "Couldn’t load the year." else "Loading the year…")
                else {
                    val heatScroll = rememberScrollState()
                    LaunchedEffect(first) { val end = snapshotFlow { heatScroll.maxValue }.first { it > 0 && it != Int.MAX_VALUE }; heatScroll.scrollTo(end) }
                    Column(Modifier.horizontalScroll(heatScroll).testTag("focus-heatmap")) {
                      Box(Modifier.width((53 * 18 + 52).dp).height(28.dp)) {
                        repeat(53) { w ->
                            val sunday = Instant.ofEpochMilli(addDays(first, w * 7 + 6, zone)).atZone(zone)
                            val prior = if (w == 0) null else Instant.ofEpochMilli(addDays(first, (w - 1) * 7 + 6, zone)).atZone(zone).month
                            if (sunday.month != prior) Text(DateTimeFormatter.ofPattern("MMM").format(sunday), Modifier.offset(x = (w * 18).dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                      }
                      Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(53) { w -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { repeat(7) { d ->
                            val ms = addDays(first, w * 7 + d, zone); val total = totals[dayKey(ms, zone)] ?: DayTotal()
                            val level = focusHeatLevel(total.minutes, goalMin)
                            val color = if (ms > today) MaterialTheme.colorScheme.surface else if (level == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary.copy(alpha = .22f + level * .19f)
                            val desc = "${label(ms, zone)}: ${if (total.minutes > 0) "${dur(total.minutes)} · ${plural(total.count)}" else "no focus"}"
                            Box(Modifier.size(16.dp).background(color, RoundedCornerShape(2.dp)).then(if (ms <= today) Modifier.semantics { contentDescription = desc } else Modifier))
                        } } }
                        Spacer(Modifier.width(52.dp))
                      }
                    }
                    Text("Less     More · darkest is your daily goal (${hrs(goalMin.toDouble())})", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item("recent-heading") { Text("Recent sessions", style = MaterialTheme.typography.titleMedium) }
        val recent = sessions.sortedBy { it.started_at }.takeLast(8).reversed()
        if (recent.isEmpty()) item("recent-empty") { Text("Your focus sessions will show here.") }
        items(recent.size, key = { "recent-${recent[it].client_id}" }) { index -> val s = recent[index]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text((s.label ?: courseNames[s.course_id] ?: "Focus") + (if (s.label != null && courseNames[s.course_id] != null) " · ${courseNames[s.course_id]}" else ""))
                    Text("${recentDay(s.started_at, now, zone)} · ${time(s.started_at, zone)}–${time(s.ended_at, zone)} · ${dur(s.focus_seconds / 60.0)}${if (!s.completed) " · stopped early" else ""}${if (s.id == null || s.id == 0L) " · saving…" else ""}")
                    if (s.tags.isNotEmpty()) Text(s.tags.joinToString(" ") { "#$it" })
                }
                if (s.id != null && s.id != 0L) TextButton(onClick = { deleting = s.id }) { Text("Delete") }
            }
        }
        item("settings") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("focus-settings")) {
                Text("Timer settings", style = MaterialTheme.typography.titleMedium)
                @Composable fun number(label: String, value: String, hint: String, change: (String) -> Unit) { OutlinedTextField(value, change, label = { Text(label) }, supportingText = { Text(hint) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth()) }
                number("Focus", draft.focus, "1–180 min") { edit(draft.copy(focus = it)) }
                number("Short break", draft.short, "1–60 min") { edit(draft.copy(short = it)) }
                number("Long break", draft.long, "1–90 min") { edit(draft.copy(long = it)) }
                number("Long break after", draft.longEvery, "2–12 focus sessions") { edit(draft.copy(longEvery = it)) }
                number("Daily goal", draft.goal, "1–24 pomodoros") { edit(draft.copy(goal = it)) }
                // The web's tag chips with an × button.
                @Composable fun removable(text: String, description: String, remove: () -> Unit) {
                    InputChip(selected = false, enabled = !saving, onClick = remove, label = { Text(text) },
                        trailingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Close, null, Modifier.size(16.dp)) },
                        modifier = Modifier.semantics { contentDescription = description })
                }
                @Composable fun check(label: String, value: Boolean, change: (Boolean) -> Unit) { Row { Checkbox(value, change); Text(label, Modifier.padding(top = 12.dp)) } }
                check("Start breaks automatically", draft.autoBreaks) { edit(draft.copy(autoBreaks = it)) }
                check("Start the next focus after a break automatically", draft.autoFocus) { edit(draft.copy(autoFocus = it)) }
                check("Play a sound when a phase ends", draft.sound) { edit(draft.copy(sound = it)) }
                check("Notify me when a phase ends", draft.notify) { edit(draft.copy(notify = it)) }
                Text("Your tasks", style = MaterialTheme.typography.titleSmall)
                if (draft.tasks.isEmpty()) Text("No tasks yet.")
                if (draft.tasks.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { draft.tasks.forEach { task -> removable(task, "Remove $task") { saveList(draft.tasks - task, true) } } }
                OutlinedTextField(draft.taskInput, { edit(draft.copy(taskInput = it)) }, label = { Text("New task") }, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = !saving && cleanTask(draft.taskInput).isNotEmpty() && (draft.tasks.size < MAX_TASKS || draft.tasks.any { it.equals(cleanTask(draft.taskInput), ignoreCase = true) }), onClick = { saveList(cleanTasks(listOf(draft.taskInput) + draft.tasks), true) }) { Text("Add task") }
                Text("Tags", style = MaterialTheme.typography.titleSmall)
                if (draft.tags.isEmpty()) Text("No tags yet.")
                if (draft.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { draft.tags.forEach { tag -> removable("#$tag", "Remove #$tag") { saveList(draft.tags - tag, false) } } }
                OutlinedTextField(draft.tagInput, { edit(draft.copy(tagInput = it)) }, label = { Text("New tag") }, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = !saving && cleanTag(draft.tagInput).isNotEmpty() && (draft.tags.size < MAX_TAGS || cleanTag(draft.tagInput) in draft.tags), onClick = { saveList(cleanTags(draft.tags + draft.tagInput, MAX_TAGS), false) }) { Text("Add tag") }
                Button(enabled = draft.valid() && !saving, onClick = { val submitted = storedDraft; val submittedOwner = owner; scope.launch { saving = true; val success = onSave(draft.body()); saving = false; if (success && latestOwner == submittedOwner) { if (latestDraft == submitted) onDraft(null); message("Timer settings saved.") } } }) { Text("Save") }
                Text("Changes apply from the next phase. Settings follow your account on every device.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this focus session from your stats?") }, confirmButton = { TextButton(onClick = { deleting = null; scope.launch { onDelete(id) } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}
