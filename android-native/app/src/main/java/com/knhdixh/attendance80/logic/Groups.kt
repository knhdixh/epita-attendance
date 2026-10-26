package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CalendarDto
import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import com.knhdixh.attendance80.net.IntBooleanSerializer
import kotlinx.serialization.Serializable

@Serializable
data class CourseWithGroup(
    val id: Long,
    val name: String,
    @Serializable(with = IntBooleanSerializer::class) val tracked: Boolean = true,
    val tag: String = "normal",
    @Serializable(with = IntBooleanSerializer::class) val attendance: Boolean = true,
    val recording_url: String? = null,
    val group: String,
) {
    val course: CourseDto get() = CourseDto(
        id = id,
        name = name,
        tracked = tracked,
        tag = tag,
        attendance = attendance,
        recording_url = recording_url,
    )
}

@Serializable
data class GroupFamily(
    val family: String,
    val courses: List<CourseWithGroup>,
)

private val NATURAL_SORT_COMPARATOR = Comparator<String> { s1, s2 ->
    val r = Regex("""(\d+)|(\D+)""")
    val tokens1 = r.findAll(s1).map { it.value }.toList()
    val tokens2 = r.findAll(s2).map { it.value }.toList()
    val len = minOf(tokens1.size, tokens2.size)
    for (i in 0 until len) {
        val t1 = tokens1[i]
        val t2 = tokens2[i]
        val n1 = t1.toLongOrNull()
        val n2 = t2.toLongOrNull()
        val cmp = if (n1 != null && n2 != null) n1.compareTo(n2) else t1.compareTo(t2)
        if (cmp != 0) return@Comparator cmp
    }
    tokens1.size.compareTo(tokens2.size)
}

fun groupFamilies(courses: List<CourseDto>): List<GroupFamily> {
    val fam = mutableMapOf<String, MutableList<CourseWithGroup>>()
    for (c in courses) {
        val g = splitGroup(c.name) ?: continue
        fam.getOrPut(g.family) { mutableListOf() }.add(
            CourseWithGroup(
                id = c.id,
                name = c.name,
                tracked = c.tracked,
                tag = c.tag,
                attendance = c.attendance,
                recording_url = c.recording_url,
                group = g.group,
            )
        )
    }
    for (list in fam.values) {
        list.sortWith { a, b -> NATURAL_SORT_COMPARATOR.compare(a.group, b.group) }
    }
    return fam.entries.sortedBy { it.key }.map {
        GroupFamily(family = it.key, courses = it.value.toList())
    }
}

fun sanitizeScope(scope: List<Long>, calendars: List<CalendarDto>): List<Long> {
    val valid = calendars.map { it.id }.toSet()
    val filtered = scope.filter { it in valid }
    return if (filtered.size < valid.size) filtered else emptyList()
}

fun applyScope(
    allEvents: List<EventDto>,
    calendars: List<CalendarDto>,
    scope: List<Long>,
): List<EventDto> {
    val sanitized = sanitizeScope(scope, calendars)
    if (sanitized.isEmpty()) return allEvents
    val on = sanitized.toSet()
    return allEvents.filter { e ->
        e.cals.isEmpty() || e.cals.any { it in on }
    }
}

fun courseCalendars(allEvents: List<EventDto>): Map<Long, Set<Long>> {
    val map = mutableMapOf<Long, MutableSet<Long>>()
    for (e in allEvents) {
        val set = map.getOrPut(e.course_id) { mutableSetOf() }
        set.addAll(e.cals)
    }
    return map
}

fun groupLabel(calIds: Collection<Long>?, calendars: List<CalendarDto>): String {
    if (calendars.size < 2 || calIds.isNullOrEmpty()) return ""
    val set = calIds.toSet()
    val names = calendars.filter { it.id in set }.map { it.name }
    if (names.isEmpty()) return ""
    return if (names.size == calendars.size) "All groups" else names.joinToString(" · ")
}

fun scopeNote(scope: List<Long>, calendars: List<CalendarDto>): String {
    val sanitized = sanitizeScope(scope, calendars)
    if (sanitized.isEmpty()) return ""
    val names = calendars.associate { it.id to it.name }
    return "Counting ${sanitized.mapNotNull { names[it] }.joinToString(" + ")} only"
}

fun toggleScope(
    value: String,
    currentScope: List<Long>,
    calendars: List<CalendarDto>,
): List<Long> {
    if (value == "all") return emptyList()
    val id = value.toLongOrNull() ?: return currentScope
    val on = sanitizeScope(currentScope, calendars).toMutableSet()
    if (on.isEmpty()) {
        on.add(id)
    } else if (on.contains(id)) {
        on.remove(id)
    } else {
        on.add(id)
    }
    return if (on.size >= calendars.size) emptyList() else on.toList()
}
