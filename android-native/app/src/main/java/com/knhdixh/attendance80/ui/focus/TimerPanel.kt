package com.knhdixh.attendance80.ui.focus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knhdixh.attendance80.focus.clock
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.AppData
import kotlinx.coroutines.delay

/** What the timer panel can ask for. The screen and the quick-focus sheet both implement it with the controller. */
interface TimerActions {
    fun toggle()
    fun skip()
    fun stop()
    fun adjust(min: Int)
    fun setLength(min: Int)
    fun setTarget(target: FocusTarget?)
    fun addTask(label: String)
}

private val PRESETS = mapOf(
    "focus" to listOf(15, 20, 25, 30, 45, 50, 60, 90),
    "short" to listOf(3, 5, 10, 15),
    "long" to listOf(10, 15, 20, 30),
)

internal fun targetKey(t: FocusTarget?): String = when {
    t == null -> ""
    t.homework_id != null -> "hw:${t.homework_id}"
    t.course_id != null && t.label == null -> "course:${t.course_id}"
    t.label != null -> "task:${t.label}"
    else -> ""
}

/** The targets the picker offers: nothing, your tasks, open homework, tracked courses (as the web's "Working on"). */
internal data class TargetOption(val key: String, val label: String, val target: FocusTarget?, val group: String)

internal fun targetOptions(data: AppData?, settings: FocusSettings, pendingTasks: List<String>, current: FocusTarget?): List<TargetOption> {
    val byId = data?.courses.orEmpty().associateBy { it.id }
    val tasks = (pendingTasks + settings.tasks).distinctBy { it.lowercase() }
    val keep = current?.tags.orEmpty()
    val out = mutableListOf(TargetOption("", "Nothing in particular", null, ""))
    // A task that is current but not in the list (just typed, not saved yet) stays selectable.
    if (current?.label != null && current.homework_id == null && current.course_id == null && tasks.none { it == current.label }) out += TargetOption(targetKey(current), current.label, current, "My tasks")
    tasks.forEach { out += TargetOption("task:$it", it, FocusTarget(label = it, tags = keep), "My tasks") }
    data?.homework.orEmpty().filterNot { it.done }.forEach { h ->
        val course = h.course_id?.let { byId[it]?.name }
        out += TargetOption("hw:${h.id}", h.title + (course?.let { " · $it" } ?: ""), FocusTarget(homework_id = h.id, course_id = h.course_id, label = h.title, tags = keep), "Homework and catch-ups")
    }
    data?.courses.orEmpty().filter { it.tracked }.sortedBy { it.name }.forEach { c ->
        out += TargetOption("course:${c.id}", c.name, FocusTarget(course_id = c.id, tags = keep), "Courses")
    }
    return out
}

/**
 * The timer: phase, a big clock, progress, ±time and presets, what you're working on, tags, and Start/Pause, Skip, Stop.
 * `now` ticks every second only while a phase is running.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerPanel(
    timer: FocusTimer, settings: FocusSettings, data: AppData?, pendingTasks: List<String>,
    now: () -> Long, actions: TimerActions, modifier: Modifier = Modifier,
) {
    var tick by remember { mutableLongStateOf(now()) }
    val animating = timer.running() && !timer.paused()
    LaunchedEffect(timer, animating) {
        tick = now()
        while (animating) { delay(1000 - tick % 1000); tick = now() }
    }
    val on = timer.running()
    val paused = timer.paused()
    val phase = if (on) timer.phase else timer.next
    val total = if (on) remaining(timer, tick) else nextLength(timer, settings) * MIN_MS
    val title = when {
        on -> (PHASES[phase] ?: phase) + if (paused) " · paused" else ""
        phase == "focus" -> "Ready to focus"
        else -> "Time for a ${if (phase == "long") "long" else "short"} break"
    }
    val current = nextLength(timer, settings)
    val doneInSet = minOf(timer.done, settings.longEvery)
    var picking by remember { mutableStateOf(false) }
    var newTask by remember { mutableStateOf<String?>(null) }
    val options = remember(data, settings, pendingTasks, timer.target) { targetOptions(data, settings, pendingTasks, timer.target) }
    val selected = options.firstOrNull { it.key == targetKey(timer.target) } ?: options.first()
    Column(modifier.testTag("timer-panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("timer-phase"))
        Text(clock(total), fontSize = 56.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace,
            modifier = Modifier.testTag("timer-clock").semantics { role = androidx.compose.ui.semantics.Role.Button; contentDescription = "${if (on) "Time left" else "Length"} ${clock(total)}" })
        LinearProgressIndicator(progress = { if (on) progress(timer, tick).toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("timer-adjust")) {
            @Composable fun chip(text: String, desc: String, pressed: Boolean = false, tag: String, go: () -> Unit) =
                FilterChip(selected = pressed, onClick = go, label = { Text(text) }, modifier = Modifier.testTag(tag).semantics { contentDescription = desc })
            chip("−5", "5 minutes less", tag = "adjust--5") { actions.adjust(-5) }
            chip("−1", "1 minute less", tag = "adjust--1") { actions.adjust(-1) }
            if (!on) (PRESETS[phase].orEmpty() + settings.minutes(phase)).distinct().sorted().forEach { m ->
                chip("$m min", "$m minutes", pressed = m == current, tag = "length-$m") { actions.setLength(m) }
            }
            chip("+1", "1 minute more", tag = "adjust-1") { actions.adjust(1) }
            chip("+5", "5 minutes more", tag = "adjust-5") { actions.adjust(5) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics { contentDescription = "$doneInSet of ${settings.longEvery} focus sessions before a long break" }) {
            repeat(settings.longEvery) { i ->
                Surface(shape = CircleShape, color = if (i < doneInSet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(8.dp)) {}
            }
        }
        if (newTask != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newTask!!, { newTask = it.take(TASK_LEN) }, label = { Text("New task") }, placeholder = { Text("New task, e.g. Thesis: literature review") },
                    singleLine = true, modifier = Modifier.weight(1f).testTag("timer-new-task"))
                Button(enabled = cleanTask(newTask).isNotEmpty(), onClick = { actions.addTask(cleanTask(newTask)); newTask = null }) { Text("Add") }
                TextButton(onClick = { newTask = null }) { Text("Cancel") }
            }
        } else {
            Box {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().testTag("timer-target")) {
                    Text("Working on: ${selected.label}", maxLines = 1)
                }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    var group: String? = null
                    options.forEach { o ->
                        if (o.group != group && o.group.isNotEmpty()) { group = o.group; Text(o.group, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                        DropdownMenuItem(text = { Text(o.label, maxLines = 1) }, onClick = { picking = false; actions.setTarget(o.target) }, modifier = Modifier.testTag("target-${o.key}"))
                    }
                    DropdownMenuItem(text = { Text("+ New task…") }, onClick = { picking = false; newTask = "" }, modifier = Modifier.testTag("target-new"))
                }
            }
        }
        val tags = (settings.tags + timer.target?.tags.orEmpty()).distinct()
        if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = "Tags for this focus" }) {
            val on1 = timer.target?.tags.orEmpty().toSet()
            tags.forEach { tag ->
                FilterChip(selected = tag in on1, onClick = {
                    val base = timer.target ?: FocusTarget()
                    val next = if (tag in on1) base.tags - tag else if (base.tags.size < TAGS_PER_SESSION) base.tags + tag else base.tags
                    val target = base.copy(tags = next)
                    actions.setTarget(if (target.homework_id == null && target.course_id == null && target.label == null && next.isEmpty()) null else target)
                }, label = { Text("#$tag") }, modifier = Modifier.testTag("tag-$tag"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Button(onClick = actions::toggle, modifier = Modifier.testTag("timer-toggle")) {
                Text(if (!on) (if (phase == "focus") "Start focus" else "Start break") else if (paused) "Resume" else "Pause")
            }
            if (on || phase != "focus") OutlinedButton(onClick = actions::skip, modifier = Modifier.testTag("timer-skip")) {
                Text(if (phase == "focus") "Take a break" else "Skip break")
            }
            if (on) TextButton(onClick = actions::stop, modifier = Modifier.testTag("timer-stop")) { Text("Stop") }
        }
    }
}
