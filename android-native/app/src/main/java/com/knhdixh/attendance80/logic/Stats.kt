package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.max

@Serializable
data class TagInfo(
    val label: String,
    val counts: Boolean,
    val mustAttend: Boolean,
)

val TAGS: Map<String, TagInfo> = mapOf(
    "normal" to TagInfo(label = "Normal", counts = true, mustAttend = false),
    "mandatory" to TagInfo(label = "Mandatory", counts = true, mustAttend = true),
    "exam" to TagInfo(label = "Exam", counts = true, mustAttend = true),
    "optional" to TagInfo(label = "Optional", counts = false, mustAttend = false),
)

val STATUSES: List<String> = listOf("present", "absent", "excused")

@Serializable
data class GroupSplit(
    val group: String,
    val family: String,
)

private val GROUP_SPLIT_REGEX = Regex("""^(GR?\s?\d+)\s*[-–—]\s*(.+)$""", RegexOption.IGNORE_CASE)

// "GR3 - French for Fall 26 T1" -> GroupSplit(group = "GR3", family = "French for Fall 26 T1")
fun splitGroup(name: String): GroupSplit? {
    val m = GROUP_SPLIT_REGEX.find(name.trim()) ?: return null
    val group = m.groupValues[1].uppercase().replace("\\s".toRegex(), "")
    val family = m.groupValues[2].trim()
    return GroupSplit(group = group, family = family)
}

// A tracked course whose calendar counts attendance (a Sisu timetable, say, doesn't).
fun countsAttendance(c: CourseDto?): Boolean =
    c != null && c.tracked && c.attendance

fun effectiveTag(ev: EventDto, course: CourseDto?): String =
    ev.tag?.takeIf { it.isNotBlank() } ?: course?.tag?.takeIf { it.isNotBlank() } ?: "normal"

private fun weightOf(ev: EventDto, unit: String): Double =
    if (unit == "hours") (ev.end_at - ev.start_at).toDouble() / 3600000.0 else 1.0

@Serializable
data class AttendanceStats(
    val total: Double = 0.0,
    val present: Double = 0.0,
    val absent: Double = 0.0,
    val plannedAbsent: Double = 0.0,
    val excused: Double = 0.0,
    val optional: Double = 0.0,
    val pastMarked: Double = 0.0,
    val pastPresent: Double = 0.0,
    val unmarkedPast: Int = 0,
    val upcoming: Int = 0,
    val sessions: Int = 0,
    val missedMustAttend: List<EventDto> = emptyList(),
    val maxWeight: Double = 0.0,
    val allowedAbsence: Double = 0.0,
    val remaining: Double = 0.0,
    val remainingDisplay: Double = 0.0,
    val projectedRate: Double = 1.0,
    val currentRate: Double? = null,
    val level: String = "ok",
)

@Serializable
data class CourseStats(
    val course: CourseDto,
    val stats: AttendanceStats,
)

@Serializable
data class StatsByCourseResult(
    val perCourse: List<CourseStats>,
    val overall: AttendanceStats,
)

fun computeStats(
    events: List<EventDto>,
    coursesById: Map<Long, CourseDto>,
    threshold: Double = 0.8,
    unit: String = "sessions",
    now: Long = System.currentTimeMillis(),
): AttendanceStats {
    var total = 0.0
    var absent = 0.0
    var plannedAbsent = 0.0
    var excused = 0.0
    var optional = 0.0
    var pastMarked = 0.0
    var pastPresent = 0.0
    var unmarkedPast = 0
    var upcoming = 0
    var sessions = 0
    val missedMustAttend = mutableListOf<EventDto>()
    var maxWeight = 0.0

    for (ev in events) {
        val course = coursesById[ev.course_id]
        val tag = effectiveTag(ev, course)
        val w = weightOf(ev, unit)
        val past = ev.start_at <= now
        sessions++

        val tagInfo = TAGS[tag]
        if (tagInfo == null || !tagInfo.counts) {
            optional += w
            continue
        }
        if (ev.status == "excused") {
            excused += w
            continue
        }

        total += w
        maxWeight = max(maxWeight, w)

        if (ev.status == "absent") {
            absent += w
            if (!past) plannedAbsent += w
            if (tagInfo.mustAttend) missedMustAttend.add(ev)
        }

        if (past) {
            when (ev.status) {
                "present" -> {
                    pastPresent += w
                    pastMarked += w
                }
                "absent" -> {
                    pastMarked += w
                }
                else -> {
                    unmarkedPast++
                }
            }
        } else {
            upcoming++
        }
    }

    val present = total - absent
    val allowedAbsence = total * (1.0 - threshold)
    val remaining = allowedAbsence - absent
    val remainingDisplay = if (unit == "hours") {
        floor((remaining + 1e-9) * 2.0) / 2.0
    } else {
        floor(remaining + 1e-9)
    }
    val projectedRate = if (total != 0.0) (total - absent) / total else 1.0
    val currentRate = if (pastMarked != 0.0) pastPresent / pastMarked else null
    val level = when {
        remaining < -1e-9 -> "fail"
        remaining < max(1.0, maxWeight) - 1e-9 || missedMustAttend.isNotEmpty() -> "risk"
        else -> "ok"
    }

    return AttendanceStats(
        total = total,
        present = present,
        absent = absent,
        plannedAbsent = plannedAbsent,
        excused = excused,
        optional = optional,
        pastMarked = pastMarked,
        pastPresent = pastPresent,
        unmarkedPast = unmarkedPast,
        upcoming = upcoming,
        sessions = sessions,
        missedMustAttend = missedMustAttend,
        maxWeight = maxWeight,
        allowedAbsence = allowedAbsence,
        remaining = remaining,
        remainingDisplay = remainingDisplay,
        projectedRate = projectedRate,
        currentRate = currentRate,
        level = level,
    )
}

fun statsByCourse(
    courses: List<CourseDto>,
    events: List<EventDto>,
    threshold: Double = 0.8,
    unit: String = "sessions",
    now: Long = System.currentTimeMillis(),
): StatsByCourseResult {
    val byId = courses.associateBy { it.id }
    val groups = courses.associate { it.id to mutableListOf<EventDto>() }
    for (ev in events) {
        groups[ev.course_id]?.add(ev)
    }

    val perCourse = courses.map { c ->
        CourseStats(
            course = c,
            stats = computeStats(
                events = groups[c.id].orEmpty(),
                coursesById = byId,
                threshold = threshold,
                unit = unit,
                now = now,
            )
        )
    }

    val tracked = courses.filter { countsAttendance(it) }.map { it.id }.toSet()
    val trackedEvents = events.filter { it.course_id in tracked }
    val overall = computeStats(
        events = trackedEvents,
        coursesById = byId,
        threshold = threshold,
        unit = unit,
        now = now,
    )

    return StatsByCourseResult(
        perCourse = perCourse,
        overall = overall,
    )
}
