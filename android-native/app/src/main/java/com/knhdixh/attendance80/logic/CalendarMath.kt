package com.knhdixh.attendance80.logic

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Calendar calculations use the same local-time resolution as JavaScript Date. */
object CalendarMath {
    private const val MIN = 60000L
    private const val DAY = 24 * 60 * MIN
    private val views = mapOf("day" to 1, "3day" to 3, "week" to 7, "month" to 0)
    private fun local(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
    private fun resolve(d: java.time.LocalDateTime, zone: ZoneId) = d.atZone(zone).toInstant().toEpochMilli()
    fun startOfDay(ms: Long, zone: ZoneId) = resolve(local(ms, zone).toLocalDate().atStartOfDay(), zone)
    fun addDays(ms: Long, n: Int, zone: ZoneId) = resolve(local(ms, zone).plusDays(n.toLong()), zone)
    fun mondayOf(ms: Long, zone: ZoneId) = addDays(startOfDay(ms, zone), -(local(ms, zone).dayOfWeek.value - 1), zone)
    fun atMinutes(day: Long, minutes: Int, zone: ZoneId) = resolve(local(day, zone).toLocalDate().atStartOfDay().plusMinutes(minutes.toLong()), zone)
    data class Period(val start: Long, val end: Long, val days: List<Long>)
    fun period(view: String, date: Long, zone: ZoneId): Period {
        val start: Long
        val n: Int
        if (view == "month") {
            val first = local(startOfDay(date, zone), zone).withDayOfMonth(1)
            start = mondayOf(resolve(first, zone), zone)
            val next = resolve(first.plusMonths(1), zone)
            n = (ceil(floor((next - start).toDouble() / DAY + 0.5) / 7) * 7).toInt()
        } else {
            start = if (view == "week") mondayOf(date, zone) else startOfDay(date, zone)
            n = views[view] ?: 0
        }
        return Period(start, addDays(start, n, zone), (0 until n).map { addDays(start, it, zone) })
    }
    fun shift(view: String, date: Long, dir: Int, zone: ZoneId): Long = if (view == "month") resolve(local(date, zone).withDayOfMonth(1).plusMonths(dir.toLong()), zone) else addDays(date, dir * (views[view] ?: 0), zone)
    fun isoWeek(ms: Long, zone: ZoneId): Int {
        val d = local(startOfDay(ms, zone), zone).toLocalDate().let { it.plusDays((4 - it.dayOfWeek.value).toLong()) }
        val jan4 = java.time.LocalDate.of(d.year, 1, 4)
        val delta = (resolve(d.atStartOfDay(), zone) - resolve(jan4.atStartOfDay(), zone)).toDouble() / DAY
        return 1 + floor((delta - 3 + jan4.dayOfWeek.value - 1) / 7 + 0.5).toInt()
    }
    data class Item(val id: Int, val start: Long, val end: Long)
    data class Position(val col: Int, var cols: Int)
    fun layout(items: List<Item>): Map<Int, Position> {
        val out = linkedMapOf<Int, Position>(); var group = mutableListOf<Int>(); var ends = mutableListOf<Long>(); var groupEnd = Long.MIN_VALUE
        fun close() { group.forEach { out.getValue(it).cols = ends.size }; group = mutableListOf(); ends = mutableListOf() }
        for (it in items.sortedWith(compareBy<Item> { it.start }.thenByDescending { it.end })) {
            if (it.start >= groupEnd) { close(); groupEnd = Long.MIN_VALUE }
            var col = ends.indexOfFirst { end -> end <= it.start }
            if (col < 0) { col = ends.size; ends.add(it.end) } else ends[col] = it.end
            out[it.id] = Position(col, 1); group.add(it.id); groupEnd = maxOf(groupEnd, it.end)
        }
        close(); return out
    }
    data class Course(val id: Int, val tracked: Boolean)
    fun courseColors(courses: List<Course>): Map<Int, String> {
        val palette = listOf("blue", "orange", "green", "purple", "teal", "pink", "yellow", "red")
        return courses.sortedWith(compareByDescending<Course> { it.tracked }.thenBy { it.id }).mapIndexed { i, c -> c.id to palette[i % palette.size] }.toMap()
    }
    data class Todo(val id: Int, val due_at: Long, val effort: Double)
    data class Planned(val id: Int, val start: Long, val minutes: Double)
    data class AutoPlan(val planned: List<Planned>, val unplaced: List<Int>)
    fun autoPlan(todos: List<Todo>, busy: List<Pair<Long, Long>> = emptyList(), blocks: List<Pair<Long, Long>> = emptyList(), now: Long, until: Long, zone: ZoneId, dayStart: Int = 8, dayEnd: Int = 22, buffer: Int = 10, cap: Double = 240.0, maxBlock: Double = 120.0, step: Int = 15): AutoPlan {
        val grid = step * MIN
        fun snap(t: Long) = (ceil(t.toDouble() / grid) * grid).toLong()
        val taken = (busy.map { (s, e) -> (s - buffer * MIN) to (e + buffer * MIN) } + blocks).sortedBy { it.first }.toMutableList()
        val load = mutableMapOf<Long, Double>()
        fun addLoad(s: Long, minutes: Double) { val d = startOfDay(s, zone); load[d] = (load[d] ?: 0.0) + minutes }
        blocks.forEach { (s, e) -> addLoad(s, (e - s).toDouble() / MIN) }
        val planned = mutableListOf<Planned>(); val unplaced = mutableListOf<Int>()
        for (h in todos) {
            val minutes = min(h.effort, maxBlock); val len = minutes * MIN
            val deadline = if (h.due_at > now) min(h.due_at, until) else until
            var start: Long? = null; var day = startOfDay(now, zone)
            while (start == null && day < deadline) {
                if ((load[day] ?: 0.0) + minutes <= cap) {
                    var t = snap(maxOf(now, atMinutes(day, dayStart * 60, zone)))
                    val to = min(atMinutes(day, dayEnd * 60, zone), deadline)
                    for ((s, e) in taken) { if (e <= t) continue; if (s >= t + len) break; t = snap(e) }
                    if (t + len <= to) start = t
                }
                day = addDays(day, 1, zone)
            }
            if (start == null) unplaced.add(h.id) else {
                planned.add(Planned(h.id, start, minutes)); taken.add(start to (start + len).toLong()); taken.sortBy { it.first }; addLoad(start, minutes)
            }
        }
        return AutoPlan(planned, unplaced)
    }
}
