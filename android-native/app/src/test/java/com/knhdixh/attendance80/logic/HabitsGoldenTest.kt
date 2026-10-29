package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.HabitScheduleDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test

class HabitsGoldenTest {
    private val json = Json { encodeDefaults = false; explicitNulls = true }
    private fun resource(path: String) = json.parseToJsonElement(javaClass.classLoader!!.getResource(path)!!.readText()).jsonObject
    private inline fun <reified T> encoded(value: T): JsonElement = json.parseToJsonElement(json.encodeToString(value))
    @Test fun matchesWebHabits() {
        val f = resource("fixtures/habits.json"); val expected = resource("golden/habits_golden.json")
        val actual = buildJsonObject {
            put("schedules", JsonArray(f.getValue("schedules").jsonArray.map { s -> val clean = Habits.cleanSchedule(s); buildJsonObject { put("clean", encoded(clean)); put("label", Habits.scheduleLabel(clean)) } }))
            put("habits", JsonArray(f.getValue("habits").jsonArray.map { JsonArray(listOf(Habits.cleanHabit(it), Habits.cleanHabit(it, true))) }))
            put("sentences", JsonArray(f.getValue("sentences").jsonArray.map { encoded(Habits.parseHabit(it.jsonPrimitive.content)) }))
            put("stats", JsonArray(f.getValue("stats").jsonArray.map { e -> val c = e.jsonObject; val schedule = json.decodeFromJsonElement<HabitScheduleDto>(c.getValue("schedule")); val counts = c.getValue("counts").jsonObject.mapValues { it.value.jsonPrimitive.int }; val target = c.getValue("target").jsonPrimitive.int; val today = c.getValue("today").jsonPrimitive.content; buildJsonObject { put("stats", encoded(Habits.habitStats(schedule, target, counts, today, c.getValue("start").jsonPrimitive.content))); put("months", encoded(Habits.monthTotals(target, counts, today, 4))) } }))
            put("dates", JsonArray(f.getValue("dates").jsonArray.map { e -> val d = e.jsonPrimitive.content; buildJsonObject { val valid = Habits.isKey(d); put("valid", valid); if (valid) { put("ms", Habits.keyMs(d)); put("plus", Habits.addDays(d, 2)); put("diff", Habits.diffDays(d, "2026-01-01")); put("dow", Habits.dow(d)); put("week", Habits.weekOf(d)); put("month", Habits.monthOf(d)) } } }))
            put("times", JsonArray(f.getValue("times").jsonArray.map { e -> val t = e.jsonObject; val now = t.getValue("now").jsonPrimitive.long; val tz = t.getValue("tz").jsonPrimitive.content; buildJsonObject { put("key", Habits.keyInZone(now, tz)); put("reminder", Habits.reminderDay(t.getValue("remind_at").jsonPrimitive.content, now, tz)) } }))
            put("labels", JsonArray((0..2).flatMap { n -> listOf("day", "week", "month", "time").map { JsonPrimitive(Habits.streakLabel(n, it)) } }))
        }
        assertJsonEquals(expected, actual)
    }
}
