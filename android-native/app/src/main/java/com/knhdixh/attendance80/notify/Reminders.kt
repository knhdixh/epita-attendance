package com.knhdixh.attendance80.notify

import com.knhdixh.attendance80.logic.TAGS
import com.knhdixh.attendance80.logic.countsAttendance
import com.knhdixh.attendance80.logic.effectiveTag
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
        if (choices.exam) addAll(examWarnings(data, zone))
        // These two follow the account's switches (the website's `classes`, `classLead` and `habits`), as the server's cron does.
        if (data.notifications.settings.classes) addAll(classReminders(data, now, zone))
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

/** Where the exam and mandatory warning goes each morning. */
val EXAM_WARNING_TIME: java.time.LocalTime = java.time.LocalTime.of(8, 0)

/**
 * 08:00 on a day with an exam or a mandatory session you have to be at: tracked courses that count for attendance, not
 * excused. One notification a day, listing the sessions, so a day with two exams is one warning, not two.
 */
fun examWarnings(data: AppData, zone: ZoneId): List<Reminder> {
    val courses = data.courses.associateBy { it.id }
    val byDay = data.events.filter { e ->
        val course = courses[e.course_id]
        countsAttendance(course) && e.status != "excused" && TAGS[effectiveTag(e, course)]?.mustAttend == true
    }.groupBy { Instant.ofEpochMilli(it.start_at).atZone(zone).toLocalDate() }
    return byDay.map { (date, events) ->
        val sorted = events.sortedBy { it.start_at }
        val exams = sorted.count { effectiveTag(it, courses[it.course_id]) == "exam" }
        val lines = sorted.map { e ->
            val name = courses[e.course_id]?.name ?: e.summary
            val tag = TAGS.getValue(effectiveTag(e, courses[e.course_id])).label.lowercase()
            "${hhmm(e.start_at, zone)}–${hhmm(e.end_at, zone)} $name ($tag)" + if (e.location.isNotBlank()) " · ${e.location}" else ""
        }
        Reminder(
            key = "exam:$date", kind = Kinds.EXAM, at = date.atTime(EXAM_WARNING_TIME).atZone(zone).toInstant().toEpochMilli(),
            title = when { exams > 0 && exams == sorted.size -> if (exams == 1) "Exam today" else "$exams exams today"
                           exams == 0 -> if (sorted.size == 1) "Mandatory session today" else "${sorted.size} mandatory sessions today"
                           else -> "${sorted.size} sessions you can't miss today" },
            body = lines.joinToString("\n"), route = "a80://today",
        )
    }
}

/**
 * The same rule as `collectDue` in `src/notify.js`: tracked courses only, sessions marked present or unmarked (one you
 * plan to skip or are excused from is left alone), [classLead][com.knhdixh.attendance80.net.NotifySettingsDto.classLead]
 * minutes before the start. "N min" is counted from when it is posted, so a late alarm doesn't say "in 10 min" at 8 minutes.
 */
fun classReminders(data: AppData, now: Long, zone: ZoneId): List<Reminder> {
    val lead = data.notifications.settings.classLead
    val courses = data.courses.associateBy { it.id }
    return data.events.mapNotNull { e ->
        val course = courses[e.course_id]
        if (course == null || !course.tracked || (e.status != null && e.status != "present")) return@mapNotNull null
        val at = e.start_at - lead * MIN
        val mins = Math.round((e.start_at - maxOf(now, at)).toDouble() / MIN).toInt()
        val name = course.name
        Reminder(
            key = "class:${e.id}:${e.start_at}", kind = Kinds.CLASS, at = at,
            title = if (mins <= 0) "$name starts now" else "$name in $mins min",
            body = listOfNotNull("${hhmm(e.start_at, zone)}–${hhmm(e.end_at, zone)}", e.location.ifBlank { null }).joinToString(" · "),
            route = "a80://session/${e.id}", ref = e.id,
        )
    }
}
