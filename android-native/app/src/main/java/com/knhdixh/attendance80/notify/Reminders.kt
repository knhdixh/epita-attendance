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
