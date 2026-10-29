package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

private const val JS_WORD_BOUNDARY = """(?:(?<=[A-Za-z0-9_])(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])(?=[A-Za-z0-9_]))"""

val HOLIDAY_RE = Regex(
    """(?i)$JS_WORD_BOUNDARY(vacances|holidays?|bank holiday|jour f[eéEÉ]ri[eéEÉ]|f[eéEÉ]ri[eéEÉ]|cong[eéEÉ]s?)$JS_WORD_BOUNDARY"""
)

private const val DAY_MS = 86400000L
private const val EPS = 1e-9

fun isDate(s: String?): Boolean {
    if (s.isNullOrEmpty() || !s.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) return false
    return try {
        LocalDate.parse(s).toString() == s
    } catch (_: Exception) {
        false
    }
}

fun addDays(date: String, n: Int): String =
    LocalDate.parse(date).plusDays(n.toLong()).toString()

// 0 = Sunday, 1 = Monday, ..., 6 = Saturday (matches JS Date.prototype.getUTCDay())
fun weekdayOf(date: String): Int =
    LocalDate.parse(date).dayOfWeek.value % 7

fun dayDiff(a: String, b: String): Int =
    ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b)).toInt()

fun makeDayKey(tz: String): (Long) -> String {
    val zone = ZoneId.of(tz)
    return { ms -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString() }
}

fun levelOf(stats: AttendanceStats): String {
    if (stats.remaining < -EPS) return "fail"
    if (stats.remaining < max(1.0, stats.maxWeight) - EPS) return "risk"
    return "ok"
}

data class CandidateSession(
    val e: EventDto,
    val course: CourseDto,
    val day: String,
    val mustAttend: Boolean,
    val skippable: Boolean,
    val weight: Double,
)

data class BreakModel(
    val courses: List<CourseDto>,
    val events: List<EventDto>,
    val threshold: Double,
    val unit: String,
    val now: Long,
    val tz: String,
    val byId: Map<Long, CourseDto>,
    val tracked: List<CourseDto>,
    val evByCourse: Map<Long, List<EventDto>>,
    val base: Map<Long, AttendanceStats>,
    val holidays: Set<String>,
    val candidates: List<CandidateSession>,
    val today: String,
    val lastDay: String,
    val noSkip: List<CourseDto>,
    val dayKey: (Long) -> String,
)

fun breakModel(
    courses: List<CourseDto>,
    events: List<EventDto>,
    threshold: Double = 0.8,
    unit: String = "sessions",
    now: Long = System.currentTimeMillis(),
    tz: String = ZoneId.systemDefault().id,
): BreakModel {
    val dayKey = makeDayKey(tz)
    val byId = courses.associateBy { it.id }
    val tracked = courses.filter { countsAttendance(it) }

    val evByCourse = tracked.associate { c ->
        c.id to events.filter { it.course_id == c.id }
    }

    val base = tracked.associate { c ->
        c.id to computeStats(
            events = evByCourse[c.id].orEmpty(),
            coursesById = byId,
            threshold = threshold,
            unit = unit,
            now = now,
        )
    }

    val holidays = mutableSetOf<String>()
    for (e in events) {
        val c = byId[e.course_id]
        if (c?.tracked == true || !HOLIDAY_RE.containsMatchIn(c?.name.orEmpty())) continue
        val endLimit = max(e.end_at, e.start_at + 1)
        var t = e.start_at
        while (t < endLimit) {
            holidays.add(dayKey(t))
            t += DAY_MS
        }
    }

    val candidates = mutableListOf<CandidateSession>()
    for (c in tracked) {
        for (e in evByCourse[c.id].orEmpty()) {
            if (e.start_at <= now) continue
            val tag = effectiveTag(e, c)
            val tagInfo = TAGS[tag]
            val mustAttend = tagInfo?.mustAttend == true && e.status != "excused"
            val skippable = tagInfo?.counts == true && e.status != "absent" && e.status != "excused"
            val weight = if (unit == "hours") (e.end_at - e.start_at).toDouble() / 3600000.0 else 1.0
            candidates.add(
                CandidateSession(
                    e = e,
                    course = c,
                    day = dayKey(e.start_at),
                    mustAttend = mustAttend,
                    skippable = skippable,
                    weight = weight,
                )
            )
        }
    }

    val lastDay = if (events.isNotEmpty()) {
        dayKey(events.maxOf { it.start_at })
    } else {
        dayKey(now)
    }

    val today = dayKey(now)

    val noSkip = tracked.filter { c ->
        val b = base[c.id] ?: return@filter false
        b.remaining < 1.0 - EPS && candidates.any { it.course.id == c.id && it.skippable }
    }

    return BreakModel(
        courses = courses,
        events = events,
        threshold = threshold,
        unit = unit,
        now = now,
        tz = tz,
        byId = byId,
        tracked = tracked,
        evByCourse = evByCourse,
        base = base,
        holidays = holidays,
        candidates = candidates,
        today = today,
        lastDay = lastDay,
        noSkip = noSkip,
        dayKey = dayKey,
    )
}

fun holidayCount(m: BreakModel, from: String, to: String): Int {
    var n = 0
    var d = from
    while (d <= to) {
        if (d in m.holidays) n++
        d = addDays(d, 1)
    }
    return n
}

@Serializable
data class CourseSimulation(
    val course: CourseDto,
    val skipped: Int,
    val before: AttendanceStats,
    val after: AttendanceStats,
    val level: String,
)

@Serializable
data class BreakSimulation(
    val from: String,
    val to: String,
    val days: Int,
    val level: String,
    val sessions: List<EventDto>,
    val conflicts: List<EventDto>,
    val courses: List<CourseSimulation>,
    val holidayDays: Int,
)

fun simulateBreak(m: BreakModel, from: String, to: String): BreakSimulation {
    val actualFrom = if (from < m.today) m.today else from
    val inRange = m.candidates.filter { it.day in actualFrom..to }
    val skipped = inRange.filter { it.skippable }
    val conflicts = inRange.filter { it.mustAttend }

    val courseIds = skipped.map { it.course.id }.distinct()
    val courses = courseIds.mapNotNull { cid ->
        val course = m.byId[cid] ?: return@mapNotNull null
        val ids = skipped.filter { it.course.id == cid }.map { it.e.id }.toSet()
        val modifiedEvents = m.evByCourse[cid].orEmpty().map { e ->
            if (e.id in ids) e.copy(status = "absent") else e
        }
        val after = computeStats(
            events = modifiedEvents,
            coursesById = m.byId,
            threshold = m.threshold,
            unit = m.unit,
            now = m.now,
        )
        CourseSimulation(
            course = course,
            skipped = ids.size,
            before = m.base[cid] ?: computeStats(emptyList(), m.byId, m.threshold, m.unit, m.now),
            after = after,
            level = levelOf(after),
        )
    }.sortedBy { it.after.remaining }

    val level = when {
        conflicts.isNotEmpty() || courses.any { it.level == "fail" } -> "fail"
        courses.any { it.level == "risk" } -> "risk"
        else -> "ok"
    }

    return BreakSimulation(
        from = actualFrom,
        to = to,
        days = max(0, dayDiff(actualFrom, to) + 1),
        level = level,
        sessions = skipped.map { it.e },
        conflicts = conflicts.map { it.e },
        courses = courses,
        holidayDays = holidayCount(m, actualFrom, to),
    )
}

private data class DayBucket(
    val w: MutableMap<Long, Double> = mutableMapOf(),
    var must: Int = 0,
    var sessions: Int = 0,
)

private fun buckets(m: BreakModel, from: String, n: Int): List<DayBucket> {
    val days = List(n) { DayBucket() }
    for (x in m.candidates) {
        val i = dayDiff(from, x.day)
        if (i < 0 || i >= n) continue
        if (x.mustAttend) days[i].must++
        if (x.skippable) {
            days[i].w[x.course.id] = (days[i].w[x.course.id] ?: 0.0) + x.weight
            days[i].sessions++
        }
    }
    return days
}

private fun checkWindow(m: BreakModel, acc: Map<Long, Double>): Double? {
    var margin = Double.POSITIVE_INFINITY
    for ((cid, w) in acc) {
        val s = m.base[cid] ?: continue
        val rem = s.remaining - w
        if (rem < -EPS) return null
        margin = minOf(margin, rem / max(1.0, s.maxWeight))
    }
    return margin
}

@Serializable
data class BreakWindow(
    val from: String,
    val to: String,
    val days: Int,
    val sessions: Int,
    val margin: Double? = null,
    val level: String,
    val holidayDays: Int,
)

@Serializable
data class LongestSafeWindow(
    val from: String,
    val to: String,
    val days: Int,
    val sessions: Int,
)

@Serializable
data class BreakWindowsResult(
    val windows: List<BreakWindow>,
    val longest: LongestSafeWindow? = null,
)

fun findBreakWindows(
    m: BreakModel,
    days: Int,
    from: String? = null,
    to: String? = null,
    limit: Int = 5,
): BreakWindowsResult {
    val targetDays = max(1, minOf(60, days))
    val actualFrom = if (from.isNullOrEmpty() || from < m.today) m.today else from
    val actualTo = if (to.isNullOrEmpty() || to > m.lastDay) m.lastDay else to
    val n = dayDiff(actualFrom, actualTo) + 1
    if (n < targetDays) return BreakWindowsResult(windows = emptyList(), longest = null)

    val b = buckets(m, actualFrom, n)
    val found = mutableListOf<BreakWindow>()

    for (s in 0..(n - targetDays)) {
        val acc = mutableMapOf<Long, Double>()
        var must = 0
        var sessions = 0
        for (d in s until (s + targetDays)) {
            must += b[d].must
            sessions += b[d].sessions
            for ((cid, w) in b[d].w) {
                acc[cid] = (acc[cid] ?: 0.0) + w
            }
        }
        if (must > 0) continue
        val margin = checkWindow(m, acc) ?: continue
        val wFrom = addDays(actualFrom, s)
        val wTo = addDays(actualFrom, s + targetDays - 1)
        found.add(
            BreakWindow(
                from = wFrom,
                to = wTo,
                days = targetDays,
                sessions = sessions,
                margin = margin,
                level = if (margin < 1.0 - EPS) "risk" else "ok",
                holidayDays = holidayCount(m, wFrom, wTo),
            )
        )
    }

    found.sortWith { a, b ->
        val levelCmp = when {
            a.level == b.level -> 0
            a.level == "ok" -> -1
            else -> 1
        }
        if (levelCmp != 0) return@sortWith levelCmp
        val sessCmp = a.sessions.compareTo(b.sessions)
        if (sessCmp != 0) return@sortWith sessCmp
        val holCmp = b.holidayDays.compareTo(a.holidayDays)
        if (holCmp != 0) return@sortWith holCmp
        val marCmp = (b.margin ?: Double.POSITIVE_INFINITY).compareTo(a.margin ?: Double.POSITIVE_INFINITY)
        if (marCmp != 0) return@sortWith marCmp
        a.from.compareTo(b.from)
    }

    val windows = mutableListOf<BreakWindow>()
    for (w in found) {
        if (windows.size >= limit) break
        if (windows.any { o -> !(w.to < o.from || w.from > o.to) }) continue
        windows.add(w)
    }
    windows.sortBy { it.from }

    val longest = if (windows.isEmpty()) longestSafe(m, actualFrom, actualTo) else null
    return BreakWindowsResult(windows = windows, longest = longest)
}

fun longestSafe(m: BreakModel, from: String, to: String): LongestSafeWindow? {
    val n = dayDiff(from, to) + 1
    if (n <= 0) return null
    val b = buckets(m, from, n)
    var best: LongestSafeWindow? = null

    for (s in 0 until n) {
        val acc = mutableMapOf<Long, Double>()
        var sessions = 0
        for (d in s until n) {
            if (b[d].must > 0) break
            for ((cid, w) in b[d].w) {
                acc[cid] = (acc[cid] ?: 0.0) + w
            }
            sessions += b[d].sessions
            if (checkWindow(m, acc) == null) break
            val len = d - s + 1
            if (best == null || len > best.days || (len == best.days && sessions < best.sessions)) {
                best = LongestSafeWindow(
                    from = addDays(from, s),
                    to = addDays(from, d),
                    days = len,
                    sessions = sessions,
                )
            }
        }
    }
    return best
}

@Serializable
data class WeekOverviewItem(
    val from: String,
    val to: String,
    val level: String,
    val sessions: Int,
    val holidayDays: Int,
    val blockers: List<String>,
)

fun weekOverview(m: BreakModel, weeks: Int = 12): List<WeekOverviewItem> {
    val monday = addDays(m.today, -((weekdayOf(m.today) + 6) % 7))
    val result = mutableListOf<WeekOverviewItem>()

    for (i in 0 until weeks) {
        val from = addDays(monday, i * 7)
        if (from > m.lastDay) break
        val to = addDays(from, 6)
        val simFrom = if (from < m.today) m.today else from
        val sim = simulateBreak(m, simFrom, to)
        val blockers = mutableListOf<String>()
        for (e in sim.conflicts) {
            val course = m.byId[e.course_id]
            val tag = effectiveTag(e, course)
            blockers.add("$tag: ${course?.name.orEmpty()}")
        }
        for (c in sim.courses) {
            if (c.level == "fail") {
                blockers.add("below minimum: ${c.course.name}")
            }
        }
        result.add(
            WeekOverviewItem(
                from = from,
                to = to,
                level = if (sim.sessions.isNotEmpty()) sim.level else "free",
                sessions = sim.sessions.size,
                holidayDays = sim.holidayDays,
                blockers = blockers,
            )
        )
    }
    return result
}

@Serializable
data class StartDayGridItem(
    val date: String,
    val to: String? = null,
    val holiday: Boolean = false,
    val level: String = "free",
    val sessions: Int = 0,
    val blockers: List<String> = emptyList(),
    val past: Boolean = false,
    val beyond: Boolean = false,
)

fun startDayGrid(m: BreakModel, days: Int): List<List<StartDayGridItem>> {
    val monday = addDays(m.today, -((weekdayOf(m.today) + 6) % 7))
    val weeks = mutableListOf<List<StartDayGridItem>>()
    var start = monday

    while (start <= m.lastDay) {
        val week = (0 until 7).map { i ->
            val date = addDays(start, i)
            if (date < m.today) {
                StartDayGridItem(date = date, past = true)
            } else if (date > m.lastDay) {
                StartDayGridItem(date = date, beyond = true)
            } else {
                val sim = simulateBreak(m, date, addDays(date, days - 1))
                val blockers = mutableListOf<String>()
                for (e in sim.conflicts) {
                    val course = m.byId[e.course_id]
                    val tag = effectiveTag(e, course)
                    blockers.add("$tag: ${course?.name.orEmpty()}")
                }
                for (c in sim.courses) {
                    if (c.level == "fail") {
                        blockers.add("below minimum: ${c.course.name}")
                    }
                }
                StartDayGridItem(
                    date = date,
                    to = sim.to,
                    holiday = date in m.holidays,
                    level = if (sim.sessions.isNotEmpty() || sim.conflicts.isNotEmpty()) sim.level else "free",
                    sessions = sim.sessions.size,
                    blockers = blockers,
                )
            }
        }
        weeks.add(week)
        start = addDays(start, 7)
    }
    return weeks
}
