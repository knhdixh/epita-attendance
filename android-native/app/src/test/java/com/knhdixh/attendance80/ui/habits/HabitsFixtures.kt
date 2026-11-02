package com.knhdixh.attendance80.ui.habits

import com.knhdixh.attendance80.logic.Habits
import com.knhdixh.attendance80.net.*
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId

internal val hbZone: ZoneId = ZoneId.of("Europe/Paris")
/** Wednesday 4 November 2026, 10:00 in Paris. */
internal val hbNow: Long = Instant.parse("2026-11-04T09:00:00Z").toEpochMilli()
internal const val TODAY = "2026-11-04"
private val created = Instant.parse("2026-09-01T08:00:00Z").toEpochMilli()

/**
 * Read: daily, done the last 6 days (a 6-day streak, so today makes 7). Water: 8 glasses a day, 3 today.
 * Gym: Mon/Wed/Fri, so due today. Yoga: Tue/Thu, not due today. Old: archived.
 */
internal fun hbData(): AppData {
    val base: AppData = ApiClient.json.decodeFromString(object {}.javaClass.getResource("/api/data_empty.json")!!.readText())
    val list = listOf(
        HabitDto(1, "Read", "📚", "blue", HabitScheduleDto("daily"), 1, null, "21:00", 0, false, created),
        HabitDto(2, "Water", "💧", "teal", HabitScheduleDto("daily"), 8, "glasses", null, 1, false, created),
        HabitDto(3, "Gym", null, "orange", HabitScheduleDto("days", days = listOf(1, 3, 5)), 1, null, null, 2, false, created),
        HabitDto(4, "Yoga", null, "purple", HabitScheduleDto("days", days = listOf(2, 4)), 1, null, null, 3, false, created),
        HabitDto(5, "Old", null, "gray", HabitScheduleDto("daily"), 1, null, null, 4, true, created),
    )
    val logs = (1..6).map { HabitLogDto(1, Habits.addDays(TODAY, -it), 1) } + HabitLogDto(2, TODAY, 3) + HabitLogDto(5, "2026-10-01", 1)
    return base.copy(habits = HabitsDto(list, logs, since = "2025-10-01"))
}

internal class FakeHabitActions : HabitActions {
    val calls = mutableListOf<String>()
    val messages = mutableListOf<String>()
    var body: JsonObject? = null
    override suspend fun setCount(id: Long, day: String, count: Int) = true.also { calls += "count $id $day $count" }
    override suspend fun create(body: JsonObject): HabitDto? { this.body = body; calls += "create"; return HabitDto(9, "x", null, "blue", HabitScheduleDto("daily"), 1, null, null, 9, false, 0) }
    override suspend fun update(id: Long, body: JsonObject): HabitDto? { this.body = body; calls += "update $id"; return hbData().habits.list.first { it.id == id } }
    override suspend fun delete(id: Long) = true.also { calls += "delete $id" }
    override suspend fun order(ids: List<Long>) = true.also { calls += "order $ids" }
    override fun message(text: String) { messages += text }
}
