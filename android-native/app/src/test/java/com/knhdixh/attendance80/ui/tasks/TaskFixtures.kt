package com.knhdixh.attendance80.ui.tasks

import com.knhdixh.attendance80.net.*

const val TODAY = "2026-11-04"
val taskZone: java.time.ZoneId = java.time.ZoneId.of("Europe/Paris")
/** 2026-11-04 10:00 in Paris. */
const val taskNow = 1793782800000L

fun task(id: Long, title: String = "Task $id", project: Long? = null, section: Long? = null, parent: Long? = null, position: Int = 0, due: String? = null,
         priority: Int = 1, done: Boolean = false, labels: List<Long> = emptyList(), recurrence: String? = null, course: Long? = null) =
    HomeworkDto(id = id, title = title, project_id = project, section_id = section, parent_id = parent, position = position, due_date = due,
        priority = priority, done = done, labels = labels, recurrence = recurrence, course_id = course, tz = "Europe/Paris")

fun taskData(vararg rows: HomeworkDto, projects: List<ProjectDto> = emptyList(), sections: List<SectionDto> = emptyList(), labels: List<LabelDto> = emptyList(), filters: List<FilterDto> = emptyList()): AppData {
    val base = ApiClient.json.decodeFromString<AppData>(object {}.javaClass.getResource("/api/data_empty.json")!!.readText())
    return base.copy(homework = rows.toList(), projects = projects, sections = sections, labels = labels, filters = filters)
}
