package com.knhdixh.attendance80.widget

import com.knhdixh.attendance80.logic.Filters
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.HomeworkDto
import com.knhdixh.attendance80.ui.tasks.TaskModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.ZoneId

/** What a task list widget shows: Today, the Inbox, a project (with its subprojects) or a saved filter. */
@Serializable
data class TaskSource(val kind: String, val id: Long = 0) {
    companion object {
        val TODAY = TaskSource("today")
        val INBOX = TaskSource("inbox")
        fun project(id: Long) = TaskSource("project", id)
        fun filter(id: Long) = TaskSource("filter", id)
    }

    /** The `a80://` link that opens the same list in the app. */
    val route: String get() = when (kind) { "inbox" -> "a80://inbox"; "project" -> "a80://project/$id"; "filter" -> "a80://filter/$id"; else -> "a80://today" }
}

/** One line of the widget: [dueDate] goes back with a tick, so a repeating task that has moved on since it was drawn is left alone. */
data class WidgetTaskRow(val id: Long, val title: String, val priority: Int, val meta: String, val dueDate: String?, val overdue: Boolean)

/** [missing]: the project or filter is gone (deleted since the widget was set up). */
data class WidgetTaskList(val title: String, val rows: List<WidgetTaskRow>, val total: Int, val missing: Boolean = false)

const val WIDGET_TASK_LIMIT = 40

private fun row(h: HomeworkDto, today: String, showDate: Boolean) = WidgetTaskRow(
    h.id, h.title, h.priority,
    meta = listOfNotNull(
        h.due_date?.takeIf { showDate && it != "9999-12-31" }?.let { TaskModel.dayLabel(it, today) + (h.due_time?.let { t -> " $t" } ?: "") },
        if (h.recurrence != null) "repeats" else null,
    ).joinToString(" · "),
    dueDate = h.due_date?.takeIf { it != "9999-12-31" }, overdue = h.due_date != null && h.due_date < today && !h.done,
)

/** The open tasks for [source] in the order the app shows them. Pure: the widget draws what this returns, and a tick changes the snapshot it is built from. */
fun taskWidgetList(d: AppData?, source: TaskSource, now: Long, zone: ZoneId, limit: Int = WIDGET_TASK_LIMIT): WidgetTaskList {
    if (d == null) return WidgetTaskList("", emptyList(), 0)
    val today = TaskModel.todayKey(now, zone)
    fun cut(title: String, all: List<WidgetTaskRow>) = WidgetTaskList(title, all.take(limit), all.size)
    return when (source.kind) {
        "inbox" -> cut("Inbox", TaskModel.inbox(d).map { row(it, today, true) })
        "project" -> {
            val project = d.projects.find { it.id == source.id } ?: return WidgetTaskList("Project", emptyList(), 0, missing = true)
            val ids = TaskModel.projectIds(d, project.id)
            val sectionPos = d.sections.associate { it.id to it.position }
            val rows = d.homework.filter { !it.done && it.project_id in ids && it.parent_id == null }
                .sortedWith(compareBy<HomeworkDto>({ it.section_id?.let { s -> sectionPos[s] } ?: -1 }, { it.position }, { it.due_at ?: Long.MAX_VALUE }, { it.id }))
            cut(project.name, rows.map { row(it, today, true) })
        }
        "filter" -> {
            val f = d.filters.find { it.id == source.id } ?: return WidgetTaskList("Filter", emptyList(), 0, missing = true)
            val run = Filters.runFilter(f.query, TaskModel.filterContext(d, today))
            val byId = d.homework.associateBy { it.id }
            cut(f.name, if (!run.ok) emptyList() else run.tasks.mapNotNull { byId[it.id] }.map { row(it, today, true) })
        }
        else -> {
            val (overdue, due) = TaskModel.overdueAndToday(d, today)
            cut("Today", (overdue + due).map { row(it, today, true) })
        }
    }
}

/** The choices on the configuration screen: Today, Inbox, every project (nested, archived ones left out) and every filter. */
fun taskSourceChoices(d: AppData): List<Pair<String, TaskSource>> = buildList {
    add("Today" to TaskSource.TODAY); add("Inbox" to TaskSource.INBOX)
    TaskModel.projectTree(d).forEach { (p, depth) -> add("  ".repeat(depth) + p.name to TaskSource.project(p.id)) }
    d.filters.sortedBy { it.position }.forEach { add(it.name to TaskSource.filter(it.id)) }
}

/**
 * Which list each placed widget shows, by appWidgetId, in a file next to the snapshot (excluded from backup: widget ids
 * mean nothing on another phone). A widget with no entry shows Today.
 */
class TaskWidgetPrefs(private val file: File) {
    private val serializer = MapSerializer(Int.serializer(), TaskSource.serializer())

    @Synchronized private fun read(): Map<Int, TaskSource> = runCatching { ApiClient.json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())

    @Synchronized private fun write(m: Map<Int, TaskSource>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ApiClient.json.encodeToString(serializer, m))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun source(appWidgetId: Int): TaskSource = read()[appWidgetId] ?: TaskSource.TODAY
    @Synchronized fun set(appWidgetId: Int, s: TaskSource) = write(read() + (appWidgetId to s))
    @Synchronized fun remove(ids: IntArray) = write(read() - ids.toSet())
    @Synchronized fun clear() { file.delete() }
}
