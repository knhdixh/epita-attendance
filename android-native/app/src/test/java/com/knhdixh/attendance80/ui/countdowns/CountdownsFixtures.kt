package com.knhdixh.attendance80.ui.countdowns

import com.knhdixh.attendance80.net.*
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal val cdZone: ZoneId = ZoneId.of("Europe/Paris")
/** Wednesday 4 November 2026, 10:00 in Paris. */
internal val cdNow: Long = Instant.parse("2026-11-04T09:00:00Z").toEpochMilli()
internal fun cdAt(date: String, h: Int = 0, m: Int = 0) = LocalDate.parse(date).atTime(h, m).atZone(cdZone).toInstant().toEpochMilli()

/** Trip (pinned, with a progress bar), a weekly gym session, no coffee since September, and a past deadline moved to Past. An exam is in the timetable. */
internal fun cdData(): AppData {
    val base: AppData = ApiClient.json.decodeFromString(object {}.javaClass.getResource("/api/data_empty.json")!!.readText())
    return base.copy(
        courses = listOf(CourseDto(1, "Algorithmique")),
        events = listOf(EventDto(1, 1, "Algorithmique", location = "Amphi 1", start_at = cdAt("2026-12-10", 9), end_at = cdAt("2026-12-10", 11), tag = "exam")),
        countdowns = listOf(
            CountdownDto(1, "Trip home", cdAt("2026-12-19"), true, "none", "auto", "up", cdAt("2026-10-01"), "teal", true, "Book the train", null, null, 0),
            CountdownDto(2, "Gym", cdAt("2026-11-02", 18), false, "weekly", "auto", "up", null, "orange", false, null, null, null, 0),
            CountdownDto(3, "No coffee", cdAt("2026-09-01"), true, "none", "days", "up", null, "purple", false, null, null, null, 0),
            CountdownDto(4, "Old deadline", cdAt("2026-10-01"), true, "none", "auto", "past", null, "gray", false, null, null, null, 0),
        ))
}

internal class FakeCountdownActions : CountdownActions {
    val calls = mutableListOf<String>()
    val messages = mutableListOf<String>()
    var body: JsonObject? = null
    override suspend fun create(body: JsonObject): CountdownDto? {
        this.body = body; calls += "create"
        return CountdownDto(9, body["title"].toString().trim('"'), cdAt("2026-12-12", 9), false, "none", "auto", "up", null, "blue", false, null, null, null, 0)
    }
    override suspend fun update(id: Long, body: JsonObject) = true.also { this.body = body; calls += "update $id" }
    override suspend fun delete(id: Long) = true.also { calls += "delete $id" }
    override fun message(text: String) { messages += text }
}
