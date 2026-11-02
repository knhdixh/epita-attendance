package com.knhdixh.attendance80.widget

import com.knhdixh.attendance80.logic.FocusSettings
import com.knhdixh.attendance80.logic.FocusTimer
import com.knhdixh.attendance80.logic.buildPlan
import com.knhdixh.attendance80.logic.remaining
import com.knhdixh.attendance80.net.AppData
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/** A session the widget can show. The title is the course name, as on the Today screen. */
@Serializable
data class WidgetClass(val id: Long, val title: String, val start: Long, val end: Long, val where: String = "")

/** One of the top *Do next* items. `dueAt` is 0 when it has no due time. */
@Serializable
data class WidgetTodo(val id: Long, val title: String, val dueAt: Long = 0L)

/** The timer as the widget draws it. While running, `endsAt` is when it ends; while paused, `leftMs` is what is left. */
@Serializable
data class WidgetTimer(
    val phase: String = "idle",
    val next: String = "focus",
    val running: Boolean = false,
    val paused: Boolean = false,
    val endsAt: Long = 0L,
    val leftMs: Long = 0L,
    val label: String? = null,
    val nextMinutes: Int = 25,
)

/**
 * Everything the widget shows, as plain data. `DataRepository`'s snapshot fills `classes` and `todo`; `FocusController`
 * fills `timer`. The widget reads this and never the network, so it works offline and in a cold process.
 */
@Serializable
data class WidgetSnapshot(
    val signedIn: Boolean = false,
    val classes: List<WidgetClass> = emptyList(),
    val todo: List<WidgetTodo> = emptyList(),
    val timer: WidgetTimer = WidgetTimer(),
    val builtAt: Long = 0L,
) {
    /** The class you are in, else the next one, at [now]. The list holds several so the widget moves on by itself. */
    fun currentClass(now: Long): WidgetClass? = classes.firstOrNull { it.end > now }

    companion object {
        const val MAX_CLASSES = 8
        const val MAX_TODO = 3
        const val TIMED_LIMIT_MS = 20L * 60 * 60 * 1000
    }
}

/** The plan part: classes and *Do next*. Same rules as [buildPlan] (tracked courses, no excused or future-absent sessions). */
fun buildWidgetPlan(data: AppData?, now: Long, zone: ZoneId): WidgetSnapshot {
    if (data == null) return WidgetSnapshot(signedIn = false, builtAt = now)
    val courses = data.courses.associateBy { it.id }
    val classes = data.events
        .filter { e ->
            val c = courses[e.course_id]
            c?.tracked == true && !(e.status == "absent" && e.start_at > now) && e.status != "excused" &&
                e.end_at - e.start_at < WidgetSnapshot.TIMED_LIMIT_MS && e.end_at > now
        }
        .sortedBy { it.start_at }
        .take(WidgetSnapshot.MAX_CLASSES)
        .map { WidgetClass(it.id, courses[it.course_id]?.name ?: it.summary, it.start_at, it.end_at, it.location) }
    val todo = buildPlan(data.courses, data.events, data.homework, now, zone.id).todo
        .take(WidgetSnapshot.MAX_TODO)
        .map { WidgetTodo(it.id, it.title, it.due_at) }
    return WidgetSnapshot(signedIn = true, classes = classes, todo = todo, builtAt = now)
}

/** The timer part. */
fun buildWidgetTimer(t: FocusTimer, settings: FocusSettings, now: Long): WidgetTimer = WidgetTimer(
    phase = t.phase, next = t.next, running = t.running(), paused = t.paused(),
    endsAt = if (t.running()) t.endsAt else 0L,
    leftMs = if (t.paused()) remaining(t, now) else 0L,
    label = t.target?.label?.takeIf { it.isNotBlank() },
    nextMinutes = t.custom ?: settings.minutes(t.next),
)

/**
 * When the widget next needs to redraw by itself: the current class ends or the next one starts, "Next class · Tue" turns
 * into "Next class" at midnight, or a due time passes (so "overdue" appears). Null when nothing will change.
 */
fun nextWidgetRefresh(s: WidgetSnapshot, now: Long, zone: ZoneId): Long? {
    val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val c = s.currentClass(now)
    val boundary = c?.let { if (it.start > now) it.start else it.end }
    val due = s.todo.map { it.dueAt }.filter { it > now }.minOrNull()
    return listOfNotNull(boundary, due, midnight).min() + 1000
}
