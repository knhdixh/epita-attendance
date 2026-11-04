package com.knhdixh.attendance80.notify

import com.knhdixh.attendance80.logic.Habits
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.HomeworkDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val MIN = 60_000L

/** Where a "before the due time" reminder counts from on a task with a date but no time (a task has no start of day to count back from). */
val ALL_DAY_ANCHOR: LocalTime = LocalTime.of(9, 0)

/** Snooze choices on the notification: the value a button carries. */
object Snooze {
    const val TEN_MIN = "10m"
    const val HOUR = "1h"
    const val TOMORROW = "tomorrow"
    /** Tomorrow morning. */
    val TOMORROW_AT: LocalTime = LocalTime.of(9, 0)

    /** A button's value: the length, and the due date of the occurrence it was pressed on (the snooze is dropped once that moves). */
    fun value(length: String, due: String?) = "$length@${due.orEmpty()}"
    fun dueOf(value: String?): String? = value?.substringAfter('@', "")?.ifEmpty { null }

    /** When a snooze pressed at [now] ends, in [zone]. Null for a value that isn't one of ours. */
    fun until(value: String?, now: Long, zone: ZoneId): Long? = when (value?.substringBefore('@')) {
        TEN_MIN -> now + 10 * MIN
        HOUR -> now + 60 * MIN
        TOMORROW -> Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atTime(TOMORROW_AT).atZone(zone).toInstant().toEpochMilli()
        else -> null
    }
}

/** A snooze: when it ends, and the task's `due_date` when it was pressed ("" for none). */
data class TaskSnooze(val until: Long, val due: String = "")

private fun zoneOf(hw: HomeworkDto, device: ZoneId): ZoneId = hw.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: device

/** The instant a [before_minutes] reminder counts back from: the due time, or [ALL_DAY_ANCHOR] on the due date, in the task's own zone. */
fun dueAnchor(hw: HomeworkDto, device: ZoneId): Long? {
    val date = hw.due_date?.takeIf { it != "9999-12-31" }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val time = hw.due_time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: ALL_DAY_ANCHOR
    return date.atTime(time).atZone(zoneOf(hw, device)).toInstant().toEpochMilli()
}

private fun dueLine(hw: HomeworkDto, locale: java.util.Locale): String? {
    val date = hw.due_date?.takeIf { it != "9999-12-31" }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val day = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date)
    return if (hw.due_time != null) "Due $day at ${hw.due_time}" else "Due $day"
}

internal fun taskActions(hw: HomeworkDto) = listOf(
    ReminderAction("Done", "homework-done", value = hw.due_date), ReminderAction("Snooze", "snooze-menu", value = hw.due_date),
)

/** What a task's notification shows. The same card is posted for a reminder and for the end of a snooze. */
private fun taskReminder(hw: HomeworkDto, key: String, at: Long, data: AppData, locale: java.util.Locale): Reminder {
    val course = hw.course_id?.let { id -> data.courses.find { it.id == id }?.name }
    val project = hw.project_id?.let { id -> data.projects.find { it.id == id }?.name }
    return Reminder(
        key = key, kind = Kinds.TASK, at = at, title = hw.title,
        body = listOfNotNull(dueLine(hw, locale), course ?: project).joinToString(" · ").ifEmpty { "Reminder" },
        route = "a80://homework/${hw.id}", ref = hw.id, actions = taskActions(hw),
    )
}

/**
 * The reminders on open tasks (`task_reminders`): an absolute time, or minutes before the due time. A repeating task's
 * relative reminders follow its due date to the next occurrence, so completing it re-plans them; an absolute one is a single
 * moment and stays as it is. A task the user is not asked about again (done, or the snooze already used) is not listed.
 * [snoozes] maps a task id to its snooze, which counts only while the task is open and still on the due date it had then
 * (a repeating task that was ticked has moved on, and the snooze was about the occurrence before).
 */
fun taskReminders(data: AppData, device: ZoneId, snoozes: Map<Long, TaskSnooze> = emptyMap(), locale: java.util.Locale = java.util.Locale.getDefault()): List<Reminder> =
    data.homework.filter { !it.done }.flatMap { hw ->
        val own = hw.reminders.mapNotNull { r ->
            val at = r.at ?: r.before_minutes?.let { m -> dueAnchor(hw, device)?.minus(m * MIN) } ?: return@mapNotNull null
            taskReminder(hw, "task:${hw.id}:${r.id}:$at", at, data, locale)
        }
        val snoozed = snoozes[hw.id]?.takeIf { it.due == hw.due_date.orEmpty() }?.let { s -> listOf(taskReminder(hw, "snooze:${hw.id}:${s.until}", s.until, data, locale)) }.orEmpty()
        own + snoozed
    }

/** Posted at the chosen time every day when the digest is on. */
fun dailyDigests(data: AppData, at: LocalTime, now: Long, zone: ZoneId): List<Reminder> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val courses = data.courses.associateBy { it.id }
    val counts = data.habits.logs.groupBy { it.habit_id }.mapValues { (_, logs) -> logs.associate { it.day to it.count } }
    return listOf(today, today.plusDays(1)).mapNotNull { date ->
        val day = date.toString()
        val tasks = data.homework.filter { !it.done && it.kind != "catchup" && it.due_date == day }.sortedWith(compareBy({ it.due_time ?: "99" }, { -it.priority }, { it.position }))
        val overdue = if (date == today) data.homework.count { !it.done && it.kind != "catchup" && it.due_date != null && it.due_date < day } else 0
        val classes = data.events.filter { e ->
            val c = courses[e.course_id]
            c != null && c.tracked && Instant.ofEpochMilli(e.start_at).atZone(zone).toLocalDate() == date && e.status != "absent" && e.status != "excused"
        }.sortedBy { it.start_at }
        val habits = data.habits.list.filter { h ->
            if (h.archived) return@filter false
            val start = Habits.keyInZone(h.created_at, zone.id)
            val s = Habits.habitStats(h, counts[h.id].orEmpty(), day, start)
            s.due && !s.todayDone
        }
        if (tasks.isEmpty() && classes.isEmpty() && habits.isEmpty() && overdue == 0) return@mapNotNull null
        fun n(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "$count $many"
        val head = listOfNotNull(
            tasks.size.takeIf { it > 0 }?.let { n(it, "task", "tasks") }, classes.size.takeIf { it > 0 }?.let { n(it, "class", "classes") },
            habits.size.takeIf { it > 0 }?.let { n(it, "habit", "habits") },
        )
        val lines = buildList {
            if (overdue > 0) add("$overdue overdue")
            tasks.take(5).forEach { add("• " + (it.due_time?.let { t -> "$t " } ?: "") + it.title) }
            if (tasks.size > 5) add("and ${tasks.size - 5} more tasks")
            classes.take(4).forEach { add("${hhmm(it.start_at, zone)} ${courses[it.course_id]?.name ?: it.summary}") }
            if (classes.size > 4) add("and ${classes.size - 4} more classes")
            if (habits.isNotEmpty()) add("Habits: " + habits.take(5).joinToString(", ") { it.name } + if (habits.size > 5) " and ${habits.size - 5} more" else "")
        }
        Reminder(
            key = "digest:$day", kind = Kinds.DIGEST, at = date.atTime(at).atZone(zone).toInstant().toEpochMilli(),
            title = if (head.isEmpty()) n(overdue, "task overdue", "tasks overdue") else "Today: " + head.joinToString(", "), body = lines.joinToString("\n"), route = "a80://today",
        )
    }
}
