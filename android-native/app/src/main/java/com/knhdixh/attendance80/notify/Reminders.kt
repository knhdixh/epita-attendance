package com.knhdixh.attendance80.notify

import com.knhdixh.attendance80.logic.countsAttendance
import com.knhdixh.attendance80.net.AppData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A button on a reminder. [type] says what it does, [value] and [number] are what it needs (a status, a day, a count). */
data class ReminderAction(val label: String, val type: String, val value: String? = null, val number: Int? = null)

/**
 * One notification the app means to post at [at]. [key] is stable for the same thing (a session, an item, a day), so a
 * reminder is posted once. [ref] is the id of what it is about; [route] is the `a80://` link a tap opens.
 */
data class Reminder(
    val key: String, val kind: String, val at: Long, val title: String, val body: String, val route: String,
    val ref: Long? = null, val actions: List<ReminderAction> = emptyList(),
)

/** The device's switches (Settings → Notifications). [homework] null = not chosen yet: on, unless Google Calendar already reminds you. */
data class NotifyChoices(val checkin: Boolean = true, val homework: Boolean? = null, val exam: Boolean = true) {
    fun homeworkOn(googleConnected: Boolean) = homework ?: !googleConnected
}

object Kinds {
    const val CHECKIN = "checkin"
    const val HOMEWORK = "homework"
    const val EXAM = "exam"
    const val CLASS = "class"
    const val HABIT = "habit"
}

private const val MIN = 60_000L
private const val HOUR = 60 * MIN

/** How long after its time a reminder still goes (the phone was off, or an inexact alarm ran late). Same idea as `LATE` in `src/notify.js`. */
fun lateWindow(kind: String): Long = when (kind) {
    Kinds.CLASS -> 5 * MIN
    else -> 3 * HOUR
}

/** Today's and tomorrow's: the plan stops at the start of the day after tomorrow. */
fun planHorizon(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

fun startOfNextDay(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

internal fun hhmm(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(zone))

/**
 * Everything that may be posted now or later today and tomorrow, soonest first. Pure: the same data, switches, time
 * and zone always give the same list.
 */
fun planReminders(data: AppData, choices: NotifyChoices, now: Long, zone: ZoneId): List<Reminder> {
    val all = buildList {
        if (choices.checkin) addAll(checkIns(data, zone))
        if (choices.homeworkOn(data.integrations.gcal != null)) addAll(homeworkReminders(data, zone))
    }
    val horizon = planHorizon(now, zone)
    return all.filter { it.at <= horizon && it.at >= now - lateWindow(it.kind) }.sortedWith(compareBy({ it.at }, { it.key }))
}

/**
 * "Were you at <course>?" when a tracked session that counts for attendance ends and is still unmarked. A session
 * that is marked (here, on the web, or from the notification) is not in the list, so it is never asked twice.
 */
fun checkIns(data: AppData, zone: ZoneId): List<Reminder> {
    val courses = data.courses.associateBy { it.id }
    return data.events.mapNotNull { e ->
        val course = courses[e.course_id]
        if (!countsAttendance(course) || e.status != null) return@mapNotNull null
        val name = course?.name ?: e.summary
        Reminder(
            key = "checkin:${e.id}:${e.end_at}", kind = Kinds.CHECKIN, at = e.end_at,
            title = "Were you at $name?",
            body = "${hhmm(e.start_at, zone)}–${hhmm(e.end_at, zone)}",
            route = "a80://session/${e.id}", ref = e.id,
            actions = listOf(
                ReminderAction("Present", "checkin", "present"), ReminderAction("Absent", "checkin", "absent"),
                ReminderAction("Excused", "checkin", "excused"),
            ),
        )
    }
}

/**
 * `reminderMinutes()` in `src/homework.js`: minutes before the due time. A timed item uses `remind_minutes` as it is;
 * an all-day item counts back from the start of its day, so "1 day before" would be midnight, and is moved to 18:00.
 */
fun reminderMinutes(remindMinutes: Int?, timed: Boolean): Int? = when {
    remindMinutes == null -> null
    timed -> remindMinutes
    remindMinutes == 0 -> 0
    else -> Math.ceil(remindMinutes / 1440.0).toInt() * 1440 - 18 * 60
}

/**
 * When an item's reminder goes, or null. Timed: that many minutes before the due time. All-day: 18:00 on the day
 * [reminderMinutes] says, worked out on the calendar in the item's own time zone, so it stays 18:00 across a clock change
 * (counting 1080 real minutes back would land on 17:00 or 19:00 the week the clocks move).
 */
fun reminderAt(hw: com.knhdixh.attendance80.net.HomeworkDto, device: ZoneId): Long? {
    val minutes = reminderMinutes(hw.remind_minutes, hw.due_time != null) ?: return null
    val zone = hw.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: device
    val date = hw.due_date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return null
    if (hw.due_time != null) {
        val time = runCatching { java.time.LocalTime.parse(hw.due_time) }.getOrNull() ?: return null
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli() - minutes * MIN
    }
    if (minutes == 0) return date.atStartOfDay(zone).toInstant().toEpochMilli()
    val daysBefore = (minutes + 18 * 60) / 1440 // the whole days the "N days before" counts
    return date.minusDays(daysBefore.toLong()).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
}

/** Reminders for undone homework with a reminder set (the web's `remind_minutes`), on the device. Done and Focus on this are on the notification. */
fun homeworkReminders(data: AppData, device: ZoneId, locale: java.util.Locale = java.util.Locale.getDefault()): List<Reminder> {
    val courses = data.courses.associateBy { it.id }
    return data.homework.mapNotNull { hw ->
        if (hw.done) return@mapNotNull null
        val at = reminderAt(hw, device) ?: return@mapNotNull null
        val zone = hw.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: device
        val day = DateTimeFormatter.ofPattern("EEE d MMM", locale).format(java.time.LocalDate.parse(hw.due_date))
        val due = if (hw.due_time != null) "Due $day at ${hw.due_time}" else "Due $day"
        Reminder(
            key = "homework:${hw.id}:$at", kind = Kinds.HOMEWORK, at = at, title = hw.title,
            body = listOfNotNull(due, hw.course_id?.let { courses[it]?.name }).joinToString(" · "),
            route = "a80://homework/${hw.id}", ref = hw.id,
            actions = listOf(ReminderAction("Done", "homework-done"), ReminderAction("Focus on this", "homework-focus")),
        )
    }
}
