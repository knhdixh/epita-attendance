package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.FocusSessionDto
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Serializable
data class DayTotal(
    var minutes: Double = 0.0,
    var count: Int = 0,
)

@Serializable
data class WeekDayStat(
    val ms: Long,
    val minutes: Double,
    val count: Int,
    val future: Boolean,
)

@Serializable
data class CourseFocusBreakdown(
    val course_id: Long?,
    val minutes: Double,
)

@Serializable
data class HomeworkFocusBreakdown(
    val homework_id: Long? = null,
    val label: String? = null,
    val course_id: Long? = null,
    var minutes: Double = 0.0,
    var count: Int = 0,
)

@Serializable
data class TagFocusBreakdown(
    val tag: String,
    val minutes: Double,
)

@Serializable
data class RangeBreakdown(
    val days: Int,
    val minutes: Double,
    val count: Int,
    val activeDays: Int,
    val perActiveDay: Double,
    val peakHour: Int?,
    val hours: List<Double>,
    val courses: List<CourseFocusBreakdown>,
    val homework: List<HomeworkFocusBreakdown>,
    val tags: List<TagFocusBreakdown>,
)

@Serializable
data class BestDay(
    val key: String,
    val minutes: Double,
    val count: Int,
)

@Serializable
data class FocusStatsResult(
    val today: DayTotal,
    val streak: Int,
    val week: List<WeekDayStat>,
    val thisWeek: Double,
    val lastWeek: Double,
    val range: RangeBreakdown,
    val best: BestDay?,
)

fun dayStart(ms: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(ms).atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()

fun addDays(ms: Long, n: Int, zoneId: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(ms).atZone(zoneId).plusDays(n.toLong()).toInstant().toEpochMilli()

fun dayKey(ms: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val d = Instant.ofEpochMilli(ms).atZone(zoneId)
    return "${d.year}-${d.monthValue}-${d.dayOfMonth}"
}

fun weekStart(ms: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long {
    val dayW = Instant.ofEpochMilli(ms).atZone(zoneId).dayOfWeek.value % 7
    return addDays(dayStart(ms, zoneId), -((dayW + 6) % 7), zoneId)
}

fun dailyTotals(
    sessions: List<FocusSessionDto>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Map<String, DayTotal> {
    val map = mutableMapOf<String, DayTotal>()
    for (s in sessions) {
        val k = dayKey(s.started_at, zoneId)
        val d = map.getOrPut(k) { DayTotal() }
        d.minutes += s.focus_seconds.toDouble() / 60.0
        if (s.completed) d.count += 1
    }
    return map
}

fun minutesByHomework(sessions: List<FocusSessionDto>): Map<Long, Double> {
    val map = mutableMapOf<Long, Double>()
    for (s in sessions) {
        val hid = s.homework_id ?: continue
        map[hid] = (map[hid] ?: 0.0) + (s.focus_seconds.toDouble() / 60.0)
    }
    return map
}

fun day(
    ms: Long,
    sessions: List<FocusSessionDto>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): DayTotal {
    val totals = dailyTotals(sessions, zoneId)
    return totals[dayKey(ms, zoneId)] ?: DayTotal()
}

fun focusStats(
    sessions: List<FocusSessionDto>,
    now: Long,
    range: Int = 7,
    zoneId: ZoneId = ZoneId.systemDefault(),
): FocusStatsResult {
    val totals = dailyTotals(sessions, zoneId)
    fun day(ms: Long): DayTotal = totals[dayKey(ms, zoneId)] ?: DayTotal()
    val today0 = dayStart(now, zoneId)

    var streak = 0
    var d = if (day(today0).minutes > 0.0) today0 else addDays(today0, -1, zoneId)
    while (day(d).minutes > 0.0) {
        streak++
        d = addDays(d, -1, zoneId)
    }

    val mon = weekStart(now, zoneId)
    val week = (0 until 7).map { i ->
        val ms = addDays(mon, i, zoneId)
        val dt = day(ms)
        WeekDayStat(
            ms = ms,
            minutes = dt.minutes,
            count = dt.count,
            future = ms > today0,
        )
    }
    val elapsed = week.count { !it.future }

    fun sumDays(from: Long, n: Int): Double =
        (0 until n).sumOf { day(addDays(from, it, zoneId)).minutes }

    val thisWeek = sumDays(mon, elapsed)
    val lastWeek = sumDays(addDays(mon, -7, zoneId), elapsed)

    val from = addDays(today0, -(range - 1), zoneId)
    val inRange = sessions.filter { it.started_at >= from }

    val coursesMap = mutableMapOf<Long?, Double>()
    val tagsMap = mutableMapOf<String, Double>()
    val hwMap = mutableMapOf<String, HomeworkFocusBreakdown>()
    val hours = DoubleArray(24)

    for (s in inRange) {
        val m = s.focus_seconds.toDouble() / 60.0
        coursesMap[s.course_id] = (coursesMap[s.course_id] ?: 0.0) + m
        for (tag in s.tags) {
            tagsMap[tag] = (tagsMap[tag] ?: 0.0) + m
        }
        val hasLabel = s.label != null && s.label != ""
        if (s.homework_id != null || hasLabel) {
            val key = s.homework_id?.toString() ?: "label:${s.label}"
            val h = hwMap.getOrPut(key) {
                HomeworkFocusBreakdown(
                    homework_id = s.homework_id,
                    label = if (hasLabel) s.label else null,
                    course_id = s.course_id,
                )
            }
            h.minutes += m
            if (s.completed) h.count += 1
        }
        val hour = Instant.ofEpochMilli(s.started_at).atZone(zoneId).hour
        if (hour in 0..23) {
            hours[hour] += m
        }
    }

    val rangeMinutes = inRange.sumOf { it.focus_seconds.toDouble() / 60.0 }
    val activeDays = inRange.map { dayKey(it.started_at, zoneId) }.distinct().size
    val peakHour = if (hours.any { it > 0.0 }) {
        hours.indices.maxByOrNull { hours[it] }
    } else {
        null
    }

    val courses = coursesMap.entries.map {
        CourseFocusBreakdown(course_id = it.key, minutes = it.value)
    }.sortedByDescending { it.minutes }

    val homework = hwMap.values.sortedByDescending { it.minutes }

    val tags = tagsMap.entries.map {
        TagFocusBreakdown(tag = it.key, minutes = it.value)
    }.sortedByDescending { it.minutes }

    var best: BestDay? = null
    for ((k, total) in totals) {
        if (best == null || total.minutes > best.minutes) {
            best = BestDay(key = k, minutes = total.minutes, count = total.count)
        }
    }

    val rangeBreakdown = RangeBreakdown(
        days = range,
        minutes = rangeMinutes,
        count = inRange.count { it.completed },
        activeDays = activeDays,
        perActiveDay = if (activeDays > 0) rangeMinutes / activeDays.toDouble() else 0.0,
        peakHour = peakHour,
        hours = hours.toList(),
        courses = courses,
        homework = homework,
        tags = tags,
    )

    return FocusStatsResult(
        today = day(today0),
        streak = streak,
        week = week,
        thisWeek = thisWeek,
        lastWeek = lastWeek,
        range = rangeBreakdown,
        best = best,
    )
}

@Serializable
data class DaySummary(
    val date: String,
    val minutes: Int,
)

@Serializable
data class CourseSummary(
    val course_id: Long?,
    val minutes: Int,
)

@Serializable
data class TagSummary(
    val tag: String,
    val minutes: Int,
)

@Serializable
data class Last7Summary(
    val minutes: Int,
    val pomodoros: Int,
    val byDay: List<DaySummary>,
    val byCourse: List<CourseSummary>,
    val byTag: List<TagSummary>,
)

@Serializable
data class TodaySummary(
    val minutes: Int,
    val pomodoros: Int,
)

@Serializable
data class FocusSummaryResult(
    val today: TodaySummary,
    val streak: Int,
    val last7: Last7Summary,
)

fun focusSummary(
    sessions: List<FocusSessionDto>,
    now: Long,
    tz: String,
): FocusSummaryResult {
    val zone = ZoneId.of(tz)
    fun key(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    val byDay = mutableMapOf<String, DayTotal>()
    for (s in sessions) {
        val k = key(s.started_at)
        val d = byDay.getOrPut(k) { DayTotal() }
        d.minutes += s.focus_seconds.toDouble() / 60.0
        if (s.completed) d.count += 1
    }

    val noon = localMidnight(now, tz) + 12 * 3600000L
    fun daysList(n: Int): List<String> =
        (0 until n).map { key(noon - it * 86400000L) }

    val todayKey = key(now)
    val today = byDay[todayKey] ?: DayTotal()

    val days400 = daysList(400)
    val searchDays = if (today.minutes > 0.0) days400 else days400.drop(1)
    var streak = 0
    for (k in searchDays) {
        val d = byDay[k]
        if (d == null || d.minutes <= 0.0) break
        streak++
    }

    val last7Days = daysList(7)
    val weekSet = last7Days.toSet()
    val last7Sessions = sessions.filter { key(it.started_at) in weekSet }

    val coursesMap = mutableMapOf<Long?, Double>()
    val tagsMap = mutableMapOf<String, Double>()
    for (s in last7Sessions) {
        val m = s.focus_seconds.toDouble() / 60.0
        coursesMap[s.course_id] = (coursesMap[s.course_id] ?: 0.0) + m
        for (tag in s.tags) {
            tagsMap[tag] = (tagsMap[tag] ?: 0.0) + m
        }
    }

    val byDayList = last7Days.reversed().map { date ->
        DaySummary(date = date, minutes = (byDay[date]?.minutes ?: 0.0).roundToInt())
    }

    val byCourseList = coursesMap.entries.map {
        CourseSummary(course_id = it.key, minutes = it.value.roundToInt())
    }.sortedByDescending { it.minutes }

    val byTagList = tagsMap.entries.map {
        TagSummary(tag = it.key, minutes = it.value.roundToInt())
    }.sortedByDescending { it.minutes }

    val totalLast7Min = last7Sessions.sumOf { it.focus_seconds.toDouble() / 60.0 }.roundToInt()
    val totalLast7Pomodoros = last7Sessions.count { it.completed }

    return FocusSummaryResult(
        today = TodaySummary(
            minutes = today.minutes.roundToInt(),
            pomodoros = today.count,
        ),
        streak = streak,
        last7 = Last7Summary(
            minutes = totalLast7Min,
            pomodoros = totalLast7Pomodoros,
            byDay = byDayList,
            byCourse = byCourseList,
            byTag = byTagList,
        ),
    )
}
