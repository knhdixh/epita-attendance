package com.knhdixh.attendance80.ui.calendar

import com.knhdixh.attendance80.logic.CalendarMath
import com.knhdixh.attendance80.logic.Countdowns
import com.knhdixh.attendance80.logic.DAY_END
import com.knhdixh.attendance80.logic.DAY_START
import com.knhdixh.attendance80.logic.effortOf
import com.knhdixh.attendance80.logic.plannedBlock
import com.knhdixh.attendance80.net.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

internal const val MIN = 60_000L
internal const val HOUR = 60 * MIN
internal const val SNAP = 15            // minutes
internal const val NEW_BLOCK = 60       // a tap on an empty slot proposes an hour
internal const val MAX_BLOCK = 120      // a placed or auto-planned to-do gets its effort, up to 2 h
internal const val PLAN_AHEAD = 14 * 24 * HOUR
val LENGTHS = listOf(15, 30, 45, 60, 90, 120, 180, 240)
val VIEWS = listOf("day" to "Day", "3day" to "3 days", "week" to "Week", "month" to "Month")
val LAYERS = listOf("deadlines" to "Deadlines", "focus" to "Focus time", "countdowns" to "Countdowns and breaks", "done" to "Done to-dos")

@Serializable data class Proposal(val id: Long, val start: Long, val minutes: Int)

/** The page's own state, as `state.cal` in public/calendar-ui.js. Kept per account across navigation. */
@Serializable
data class CalendarUi(
    val view: String = "",
    val date: Long? = null,
    val layers: Map<String, Boolean> = LAYERS.associate { it.first to true },
    val placing: Long? = null,
    val proposals: List<Proposal>? = null,
    val unplaced: List<Long> = emptyList(),
) {
    fun on(layer: String) = layers[layer] != false
}

data class Block(val h: HomeworkDto, val start: Long, val end: Long)
data class CountdownAt(val cd: CountdownDto, val at: Long)

/** Everything the calendar shows between start and end (`collect` on the web). */
data class Collected(
    val byId: Map<Long, CourseDto>, val colors: Map<Long, String>,
    val sessions: List<EventDto>, val blocks: List<Block>, val ghosts: List<Block>, val due: List<HomeworkDto>,
    val fx: List<FocusSessionDto>, val cds: List<CountdownAt>, val brks: List<BreakDto>,
) {
    fun color(courseId: Long?) = courseId?.let { colors[it] } ?: "gray"
}

fun localDate(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

fun collect(data: AppData, ui: CalendarUi, start: Long, end: Long, zone: ZoneId): Collected {
    val byId = data.courses.associateBy { it.id }
    val colors = CalendarMath.courseColors(data.courses.map { CalendarMath.Course(it.id.toInt(), it.tracked) }).mapKeys { it.key.toLong() }
    val sessions = data.events.filter { byId[it.course_id]?.tracked == true && it.end_at > start && it.start_at < end }
    val proposals = ui.proposals.orEmpty().associateBy { it.id }
    val hw = data.homework.associateBy { it.id }
    val blocks = data.homework.filter { it.planned_at != null && it.id !in proposals && (!it.done || ui.on("done")) }
        .mapNotNull { h -> plannedBlock(h)?.let { Block(h, it.first, it.second) } }.filter { it.end > start && it.start < end }
    val ghosts = proposals.values.mapNotNull { p -> hw[p.id]?.let { Block(it, p.start, p.start + p.minutes * MIN) } }.filter { it.end > start && it.start < end }
    val day = 24 * HOUR
    val due = if (ui.on("deadlines")) data.homework.filter { (!it.done || ui.on("done")) && it.due_at != null && it.due_at > start - day && it.due_at < end + day } else emptyList()
    val fx = if (ui.on("focus")) data.focus.sessions.filter { it.ended_at > start && it.started_at < end } else emptyList()
    val cds = mutableListOf<CountdownAt>()
    if (ui.on("countdowns")) for (cd in data.countdowns) {
        // Every occurrence in range (a weekly one shows each week).
        var t = start
        for (i in 0 until 60) {
            val at = Countdowns.occurrence(cd, t, zone).at
            if (at >= end) break
            if (at >= start) cds += CountdownAt(cd, at)
            t = maxOf(at + day, t + day)
            if (cd.repeat == "none" || cd.repeat.isEmpty()) break
        }
    }
    val brks = if (ui.on("countdowns")) data.breaks.filter { it.end_date >= localDate(start, zone) && it.start_date <= localDate(end - 1, zone) } else emptyList()
    return Collected(byId, colors, sessions, blocks, ghosts, due, fx, cds, brks)
}

/** What one day has, for a column or a month cell. */
data class DayItems(
    val day: Long, val timed: List<EventDto>, val allDay: List<EventDto>, val blocks: List<Block>, val ghosts: List<Block>,
    val dueTimed: List<HomeworkDto>, val dueAllDay: List<HomeworkDto>, val fx: List<FocusSessionDto>, val cds: List<CountdownAt>, val brks: List<BreakDto>,
) {
    val empty get() = timed.isEmpty() && blocks.isEmpty() && ghosts.isEmpty() && dueTimed.isEmpty() && allDay.isEmpty() && dueAllDay.isEmpty() && cds.isEmpty()
}

fun dayItems(d: Collected, day: Long, zone: ZoneId): DayItems {
    val end = CalendarMath.addDays(day, 1, zone)
    fun inDay(s: Long, e: Long) = e > day && s < end
    val key = localDate(day, zone)
    return DayItems(day,
        d.sessions.filter { it.end_at - it.start_at < 20 * HOUR && inDay(it.start_at, it.end_at) },
        d.sessions.filter { it.end_at - it.start_at >= 20 * HOUR && inDay(it.start_at, it.end_at) },
        d.blocks.filter { inDay(it.start, it.end) }, d.ghosts.filter { inDay(it.start, it.end) },
        d.due.filter { it.due_time != null && it.due_date == key }, d.due.filter { it.due_time == null && it.due_date == key },
        d.fx.filter { inDay(it.started_at, it.ended_at) }, d.cds.filter { it.at >= day && it.at < end },
        d.brks.filter { it.start_date <= key && it.end_date >= key })
}

/** Open to-dos with no block, or whose block passed without them being done, in the order Today ranks them. */
fun unplannedTodos(homework: List<HomeworkDto>, now: Long): List<HomeworkDto> =
    homework.filter { !it.done && it.due_at != null && (it.planned_at == null || (plannedBlock(it)?.second ?: 0) <= now) }
        .sortedWith(compareByDescending<HomeworkDto> { it.due_at!! < now }.thenBy { it.due_at!! - effortOf(it) * MIN })

/** The block a to-do gets when it's placed somewhere: its effort, up to 2 h. */
fun dropLength(h: HomeworkDto) = minOf(effortOf(h), MAX_BLOCK)

/** "Plan my week" (autoPlanNow on the web): proposals, the to-dos that didn't fit, or why nothing was planned. */
sealed interface AutoPlanResult {
    data class Planned(val proposals: List<Proposal>, val unplaced: List<Long>) : AutoPlanResult
    data class Nothing(val message: String) : AutoPlanResult
}

fun autoPlanNow(data: AppData, now: Long, zone: ZoneId): AutoPlanResult {
    val byId = data.courses.associateBy { it.id }
    val busy = data.events.filter { e -> byId[e.course_id]?.tracked == true && e.end_at > now && e.end_at - e.start_at < 20 * HOUR &&
        e.status != "excused" && !(e.status == "absent" && e.start_at > now) }.map { it.start_at to it.end_at }
    val blocks = data.homework.filter { !it.done }.mapNotNull { plannedBlock(it) }.filter { it.second > now }
    val open = unplannedTodos(data.homework, now)
    val todos = open.filter { it.due_at!! < now + PLAN_AHEAD }.map { CalendarMath.Todo(it.id.toInt(), it.due_at!!, effortOf(it).toDouble()) }
    if (todos.isEmpty()) return AutoPlanResult.Nothing(if (open.isNotEmpty()) "Nothing due in the next two weeks needs a time." else "Every open to-do already has a time.")
    val r = CalendarMath.autoPlan(todos, busy, blocks, now, CalendarMath.addDays(CalendarMath.startOfDay(now, zone), 8, zone), zone,
        dayStart = DAY_START, dayEnd = DAY_END, maxBlock = MAX_BLOCK.toDouble())
    return AutoPlanResult.Planned(r.planned.map { Proposal(it.id.toLong(), it.start, it.minutes.toInt()) }, r.unplaced.map { it.toLong() })
}

/** Hours the grid shows: 7:00 to 22:00, stretched to fit anything earlier or later. */
fun hourRange(days: List<DayItems>, zone: ZoneId): Pair<Int, Int> {
    fun hourOf(ms: Long, day: Long): Double = when {
        ms <= day -> 0.0
        ms >= CalendarMath.addDays(day, 1, zone) -> 24.0
        else -> Instant.ofEpochMilli(ms).atZone(zone).let { it.hour + it.minute / 60.0 }
    }
    var lo = DAY_START - 1
    var hi = DAY_END
    for (x in days) {
        val spans = x.timed.map { it.start_at to it.end_at } + x.blocks.map { it.start to it.end } + x.ghosts.map { it.start to it.end } +
            x.dueTimed.map { it.due_at!! - 30 * MIN to it.due_at }
        for ((s, e) in spans) {
            lo = minOf(lo, kotlin.math.floor(hourOf(s, x.day)).toInt())
            hi = maxOf(hi, kotlin.math.ceil(hourOf(e, x.day)).toInt())
        }
    }
    return lo to hi
}

/** Pairs of different courses' sessions that overlap: they're marked as clashes. */
fun clashes(sessions: List<EventDto>): Set<Long> {
    val out = mutableSetOf<Long>()
    for (i in sessions.indices) for (j in i + 1 until sessions.size) {
        val a = sessions[i]; val b = sessions[j]
        if (a.course_id != b.course_id && a.start_at < b.end_at && b.start_at < a.end_at) { out += a.id; out += b.id }
    }
    return out
}

fun lenLabel(m: Int): String = if (m < 60) "$m min" else { val h = Math.round(m / 30.0) / 2.0; "${if (h == h.toLong().toDouble()) h.toLong().toString() else h.toString()} h" }
