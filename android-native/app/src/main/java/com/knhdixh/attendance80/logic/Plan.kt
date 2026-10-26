package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import com.knhdixh.attendance80.net.HomeworkDto
import com.knhdixh.attendance80.net.IntBooleanSerializer
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

const val DAY_START = 8
const val DAY_END = 22
private const val HOUR_MS = 60 * MIN_MS
private const val DAY_MS = 24 * HOUR_MS
private const val MIN_GAP = 30 * MIN_MS
private const val HOMEWORK_EFFORT = 60
private const val SOON_MS = 7 * DAY_MS

fun effortOf(h: HomeworkDto): Int =
    h.effort_minutes ?: if (h.kind == "catchup") 90 else HOMEWORK_EFFORT

fun localMidnight(ms: Long, tz: String): Long {
    val zone = ZoneId.of(tz)
    val localDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    return localDate.atStartOfDay(zone).toInstant().toEpochMilli()
}

fun atHour(midnight: Long, hours: Int, tz: String): Long {
    val zone = ZoneId.of(tz)
    val localDate = Instant.ofEpochMilli(midnight).atZone(zone).toLocalDate()
    return localDate.atTime(hours, 0).atZone(zone).toInstant().toEpochMilli()
}

data class FreeBlock(
    val start: Long,
    val end: Long,
)

fun freeBlocks(from: Long, to: Long, busy: List<Pair<Long, Long>>): List<FreeBlock> {
    val out = mutableListOf<FreeBlock>()
    var t = from
    for ((s, e) in busy) {
        if (e <= t) continue
        if (s >= to) break
        if (s - t >= MIN_GAP) {
            out.add(FreeBlock(start = t, end = s))
        }
        t = max(t, e)
    }
    if (to - t >= MIN_GAP) {
        out.add(FreeBlock(start = t, end = to))
    }
    return out
}

private fun urgency(dueAt: Long, latestStart: Long, now: Long): String {
    if (dueAt < now) return "overdue"
    if (latestStart - now < DAY_MS) return "urgent"
    return if (dueAt - now < 3 * DAY_MS) "due" else "soon"
}

@Serializable
data class PlanGapFit(
    val id: Long,
    val title: String,
    val minutes: Int,
    val partial: Boolean = false,
)

@Serializable
data class PlanGap(
    val start: Long,
    val end: Long,
    val minutes: Int,
    var left: Int = 0,
    val fits: MutableList<PlanGapFit> = mutableListOf(),
)

@Serializable
data class PlanTodoItem(
    val id: Long,
    val course_id: Long? = null,
    val title: String,
    val notes: String = "",
    val due_date: String? = null,
    val due_time: String? = null,
    val tz: String? = null,
    val due_at: Long = 0L,
    val remind_minutes: Int = 0,
    @Serializable(with = IntBooleanSerializer::class) val done: Boolean = false,
    val gcal_event_id: String? = null,
    val sync_error: String? = null,
    val created_at: Long = 0L,
    val updated_at: Long = 0L,
    val kind: String? = null,
    val event_id: Long? = null,
    val effort_minutes: Int? = null,
    val effort: Int,
    val latestStart: Long,
    val course: String? = null,
    val urgency: String,
    val beforeSession: Long? = null,
)

@Serializable
data class WeekLoad(
    var classHours: Double = 0.0,
    var workHours: Double = 0.0,
    var freeHours: Double = 0.0,
    val clashes: MutableList<List<Long>> = mutableListOf(),
    var load: Double = 0.0,
)

@Serializable
data class DayPlan(
    val now: Long,
    val tz: String,
    val today: Long,
    val current: List<Long>,
    val next: Long?,
    val todaySessions: List<Long>,
    val tomorrowFirst: Long?,
    val gaps: List<PlanGap>,
    val todo: List<PlanTodoItem>,
    val later: Int,
    val week: WeekLoad,
)

fun buildPlan(
    courses: List<CourseDto>,
    events: List<EventDto>,
    homework: List<HomeworkDto>,
    now: Long = System.currentTimeMillis(),
    tz: String = ZoneId.systemDefault().id,
): DayPlan {
    val byId = courses.associateBy { it.id }
    val sessions = events
        .filter { e ->
            val c = byId[e.course_id]
            c?.tracked == true && !(e.status == "absent" && e.start_at > now) && e.status != "excused"
        }
        .sortedBy { it.start_at }

    val timed = sessions.filter { it.end_at - it.start_at < 20 * HOUR_MS }

    val today = localMidnight(now, tz)
    val tomorrow = localMidnight(today + DAY_MS + 2 * HOUR_MS, tz)
    val current = timed.filter { it.start_at <= now && it.end_at > now }
    val upcoming = timed.filter { it.start_at > now }
    val todaySessions = timed.filter { it.end_at > today && it.start_at < tomorrow }

    val nextByCourse = mutableMapOf<Long, EventDto>()
    for (e in upcoming) {
        if (!nextByCourse.containsKey(e.course_id)) {
            nextByCourse[e.course_id] = e
        }
    }

    val open = homework.filter { !it.done }.map { h ->
        val effort = effortOf(h)
        val dueAt = h.due_at ?: 0L
        val latestStart = dueAt - effort * MIN_MS
        val courseName = byId[h.course_id]?.name
        val nextSession = nextByCourse[h.course_id]
        val beforeSession = if (nextSession != null && abs(nextSession.start_at - dueAt) < MIN_MS) {
            nextSession.id
        } else {
            null
        }
        val urg = urgency(dueAt, latestStart, now)
        PlanTodoItem(
            id = h.id,
            course_id = h.course_id,
            title = h.title,
            notes = h.notes,
            due_date = h.due_date,
            due_time = h.due_time,
            tz = h.tz,
            due_at = dueAt,
            remind_minutes = h.remind_minutes,
            done = h.done,
            gcal_event_id = h.gcal_event_id,
            sync_error = h.sync_error,
            created_at = h.created_at,
            updated_at = h.updated_at,
            kind = h.kind,
            event_id = h.event_id,
            effort_minutes = h.effort_minutes,
            effort = effort,
            latestStart = latestStart,
            course = courseName,
            urgency = urg,
            beforeSession = beforeSession,
        )
    }.toMutableList()

    open.sortWith { a, b ->
        val aOverdue = if (a.due_at < now) 1 else 0
        val bOverdue = if (b.due_at < now) 1 else 0
        val overdueCmp = bOverdue.compareTo(aOverdue)
        if (overdueCmp != 0) overdueCmp else a.latestStart.compareTo(b.latestStart)
    }

    val todo = open.filter { it.due_at < now + SOON_MS }
    val later = open.size - todo.size

    val busy = timed.map { it.start_at to it.end_at }
    val dayFrom = max(now, atHour(today, DAY_START, tz))
    val dayTo = atHour(today, DAY_END, tz)

    val gaps = freeBlocks(dayFrom, dayTo, busy).map { g ->
        val minutes = ((g.end - g.start).toDouble() / MIN_MS.toDouble()).roundToInt()
        PlanGap(
            start = g.start,
            end = g.end,
            minutes = minutes,
            left = minutes,
            fits = mutableListOf(),
        )
    }

    val pending = todo.toMutableList()
    for (g in gaps) {
        var left = g.minutes
        var i = 0
        while (i < pending.size && left >= 30) {
            val item = pending[i]
            if (item.effort <= left) {
                g.fits.add(PlanGapFit(id = item.id, title = item.title, minutes = item.effort))
                left -= item.effort
                pending.removeAt(i)
            } else {
                i++
            }
        }
        g.left = left
    }

    if (pending.isNotEmpty()) {
        val roomy = gaps.filter { it.left >= 45 }.maxByOrNull { it.left }
        if (roomy != null) {
            val item = pending[0]
            roomy.fits.add(PlanGapFit(id = item.id, title = item.title, minutes = roomy.left, partial = true))
            roomy.left = 0
        }
    }

    val weekEnd = today + 7 * DAY_MS
    val week = WeekLoad()
    var totalFreeHours = 0.0
    for (d in 0 until 7) {
        val mid = localMidnight(today + d * DAY_MS + 2 * HOUR_MS, tz)
        val from = if (d == 0) dayFrom else atHour(mid, DAY_START, tz)
        val to = atHour(mid, DAY_END, tz)
        val blocks = freeBlocks(from, to, busy)
        totalFreeHours += blocks.sumOf { (it.end - it.start).toDouble() / HOUR_MS.toDouble() }
    }
    week.freeHours = totalFreeHours

    val inWeek = upcoming.filter { it.start_at < weekEnd }
    week.classHours = inWeek.sumOf { (it.end_at - it.start_at).toDouble() / HOUR_MS.toDouble() }
    week.workHours = todo.sumOf { it.effort.toDouble() / 60.0 }

    for (i in inWeek.indices) {
        var j = i + 1
        while (j < inWeek.size && inWeek[j].start_at < inWeek[i].end_at) {
            if (inWeek[j].course_id != inWeek[i].course_id) {
                week.clashes.add(listOf(inWeek[i].id, inWeek[j].id))
            }
            j++
        }
    }

    week.load = when {
        week.freeHours != 0.0 -> week.workHours / week.freeHours
        week.workHours != 0.0 -> Double.POSITIVE_INFINITY
        else -> 0.0
    }

    val tomorrowFirst = timed.find { it.start_at >= tomorrow && it.start_at < tomorrow + DAY_MS }?.id

    return DayPlan(
        now = now,
        tz = tz,
        today = today,
        current = current.map { it.id },
        next = upcoming.firstOrNull()?.id,
        todaySessions = todaySessions.map { it.id },
        tomorrowFirst = tomorrowFirst,
        gaps = gaps,
        todo = todo,
        later = later,
        week = week,
    )
}
