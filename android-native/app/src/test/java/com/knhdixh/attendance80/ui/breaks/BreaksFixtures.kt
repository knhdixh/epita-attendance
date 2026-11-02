package com.knhdixh.attendance80.ui.breaks

import com.knhdixh.attendance80.net.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal val breaksZone: ZoneId = ZoneId.of("Europe/Paris")
/** Monday 2 November 2026, 08:00 in Paris. */
internal val breaksNow: Long = Instant.parse("2026-11-02T07:00:00Z").toEpochMilli()
internal fun breaksEmpty(): AppData = ApiClient.json.decodeFromString(object {}.javaClass.getResource("/api/data_empty.json")!!.readText())

/**
 * Seven weeks of classes from 2 November: Algorithmique every weekday at 09:00 (35 sessions, so 7 skips at 80%),
 * Physique on Tuesdays and Thursdays with one absence already, and an exam on Friday 13 November.
 */
internal fun breaksData(): AppData {
    var id = 0L
    val events = mutableListOf<EventDto>()
    var day = LocalDate.parse("2026-11-02")
    while (day <= LocalDate.parse("2026-12-18")) {
        if (day.dayOfWeek.value <= 5) {
            val start = day.atTime(9, 0).atZone(breaksZone).toInstant().toEpochMilli()
            events += EventDto(++id, 1, "Algorithmique", start_at = start, end_at = start + 7_200_000,
                tag = if (day.toString() == "2026-11-13") "exam" else null)
            if (day.dayOfWeek.value == 2 || day.dayOfWeek.value == 4) {
                val p = day.atTime(14, 0).atZone(breaksZone).toInstant().toEpochMilli()
                events += EventDto(++id, 2, "Physique", start_at = p, end_at = p + 7_200_000)
            }
        }
        day = day.plusDays(1)
    }
    val past = LocalDate.parse("2026-10-27").atTime(14, 0).atZone(breaksZone).toInstant().toEpochMilli()
    events += EventDto(++id, 2, "Physique", start_at = past, end_at = past + 7_200_000, status = "absent")
    return breaksEmpty().copy(courses = listOf(CourseDto(1, "Algorithmique"), CourseDto(2, "Physique")), events = events,
        breaks = listOf(BreakDto(5, "Trip home", "2026-12-14", "2026-12-15", sessions = 3)))
}
