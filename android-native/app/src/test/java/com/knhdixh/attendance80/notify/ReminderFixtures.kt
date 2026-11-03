package com.knhdixh.attendance80.notify

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.net.*
import java.time.Instant
import java.time.ZoneId

internal val PARIS: ZoneId = ZoneId.of("Europe/Paris")
internal fun at(iso: String, zone: ZoneId = PARIS): Long = java.time.LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()
internal fun testClock(now: Long, zone: ZoneId = PARIS) = object : Clock { override fun now() = now; override fun zone() = zone }

internal fun reminderBase(): AppData = ApiClient.json.decodeFromString(object {}.javaClass.getResource("/api/data.json")!!.readText())

internal fun course(id: Long, name: String, tracked: Boolean = true, attendance: Boolean = true, tag: String = "normal") =
    CourseDto(id, name, tracked, tag, attendance)

internal fun event(id: Long, course: Long, start: String, end: String, status: String? = null, tag: String? = null, summary: String = "Cours", location: String = "") =
    EventDto(id, course, summary, location, "", at(start), at(end), status, tag)

internal fun homework(id: Long, title: String, dueDate: String? = null, dueTime: String? = null, remind: Int? = null, done: Boolean = false, tz: String = "Europe/Paris", course: Long? = null) =
    HomeworkDto(id, course, title, "", dueDate, dueTime, tz, dueDate?.let { d ->
        if (dueTime != null) at("${d}T$dueTime:00") else at("${d}T23:59:00")
    }, remind, done)

/** The shared fixture with everything cleared, so each test says what it has. */
internal fun emptyData(
    courses: List<CourseDto> = emptyList(), events: List<EventDto> = emptyList(), homework: List<HomeworkDto> = emptyList(),
    habits: List<HabitDto> = emptyList(), logs: List<HabitLogDto> = emptyList(), notify: NotifySettingsDto = NotifySettingsDto(tz = "Europe/Paris"), gcal: Boolean = false,
): AppData {
    val d = reminderBase()
    return d.copy(
        courses = courses, events = events, homework = homework, habits = HabitsDto(habits, logs, "2026-01-01"), countdowns = emptyList(),
        notifications = d.notifications.copy(settings = notify),
        integrations = d.integrations.copy(gcal = if (gcal) GcalIntegrationDto("a@b.c", "cal") else null),
        user = d.user.copy(email = "me@example.com"),
    )
}

internal class FakeAlarm : ReminderAlarm { var at: Long? = null; var calls = 0; override fun schedule(at: Long?) { this.at = at; calls++ } }

internal class FakeNotifier(var allowed: Boolean = true) : ReminderNotifier {
    val posted = mutableListOf<Pair<Reminder, String>>()
    val cancelled = mutableListOf<String>()
    var cancelledAll = 0
    override fun canPost() = allowed
    override fun post(reminder: Reminder, owner: String) { posted += reminder to owner }
    override fun cancel(key: String) { cancelled += key }
    override fun cancelAll() { cancelledAll++ }
}

internal fun Long.iso(zone: ZoneId = PARIS): String = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime().toString()
