package com.knhdixh.attendance80.ui.calendar

import com.knhdixh.attendance80.net.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal val calZone: ZoneId = ZoneId.of("Europe/Paris")
/** Wednesday 4 November 2026, 10:00 in Paris. */
internal val calNow: Long = Instant.parse("2026-11-04T09:00:00Z").toEpochMilli()
internal fun at(date: String, h: Int, m: Int = 0): Long = LocalDate.parse(date).atTime(h, m).atZone(calZone).toInstant().toEpochMilli()
internal fun calEmpty(): AppData = ApiClient.json.decodeFromString(object {}.javaClass.getResource("/api/data_empty.json")!!.readText())

/**
 * Wednesday's classes: Algorithmique 9:00–11:00 (attended) and Physique 10:00–12:00 (a clash, unmarked), an exam on
 * Thursday at 14:00, and an all-day "Journée" on Friday. To-dos: one planned Thursday 16:00 for 1 h, one overdue,
 * one due Friday 18:00, one done. Plus focus time on Tuesday, a countdown on Friday and a break the next Monday.
 */
internal fun calData(): AppData = calEmpty().copy(
    courses = listOf(CourseDto(1, "Algorithmique"), CourseDto(2, "Physique"), CourseDto(3, "Hidden", tracked = false)),
    events = listOf(
        EventDto(1, 1, "Algorithmique", location = "Amphi 1", start_at = at("2026-11-04", 9), end_at = at("2026-11-04", 11), status = "present"),
        EventDto(2, 2, "Physique", start_at = at("2026-11-04", 10), end_at = at("2026-11-04", 12)),
        EventDto(3, 1, "Algorithmique", start_at = at("2026-11-05", 14), end_at = at("2026-11-05", 16), tag = "exam"),
        EventDto(4, 2, "Journée", start_at = at("2026-11-06", 0), end_at = at("2026-11-07", 0)),
        EventDto(5, 3, "Not tracked", start_at = at("2026-11-04", 15), end_at = at("2026-11-04", 16)),
    ),
    homework = listOf(
        HomeworkDto(10, course_id = 1, title = "Planned report", due_date = "2026-11-06", due_time = "18:00", due_at = at("2026-11-06", 18), planned_at = at("2026-11-05", 16, 30), planned_minutes = 60),
        HomeworkDto(11, course_id = 2, title = "Overdue lab", due_date = "2026-11-03", due_time = null, due_at = at("2026-11-03", 0), effort_minutes = 45),
        HomeworkDto(12, title = "Read chapter", due_date = "2026-11-06", due_time = "18:00", due_at = at("2026-11-06", 18), effort_minutes = 90),
        HomeworkDto(13, course_id = 1, title = "Done task", due_date = "2026-11-05", due_time = null, due_at = at("2026-11-05", 0), done = true, planned_at = at("2026-11-04", 13), planned_minutes = 30),
    ),
    focus = calEmpty().focus.copy(sessions = listOf(FocusSessionDto(20, "f20", label = "Deep work", started_at = at("2026-11-03", 14), ended_at = at("2026-11-03", 14, 50), focus_seconds = 3000))),
    countdowns = listOf(CountdownDto(30, "Exam week", at("2026-11-06", 0), all_day = true, repeat = "none", units = "days", after = "keep", color = "purple", pinned = false, created_at = 0)),
    breaks = listOf(BreakDto(40, "Trip", "2026-11-09", "2026-11-10", sessions = 2)),
)

/** Records the calendar's writes. */
internal class FakeCalendarActions(var ok: Boolean = true) : CalendarActions {
    val calls = mutableListOf<String>()
    val messages = mutableListOf<String>()
    var created: kotlinx.serialization.json.JsonObject? = null
    override suspend fun setPlan(h: HomeworkDto, plannedAt: Long?, minutes: Int?) = ok.also { calls += "plan ${h.id} $plannedAt $minutes" }
    override suspend fun keep(items: List<Proposal>) = ok.also { calls += "keep ${items.map { it.id }}" }
    override suspend fun create(body: kotlinx.serialization.json.JsonObject) = ok.also { created = body; calls += "create" }
    override fun message(text: String) { messages += text }
}
