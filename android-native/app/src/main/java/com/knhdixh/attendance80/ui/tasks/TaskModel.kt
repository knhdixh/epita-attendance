package com.knhdixh.attendance80.ui.tasks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.knhdixh.attendance80.logic.FilterContext
import com.knhdixh.attendance80.logic.FilterName
import com.knhdixh.attendance80.logic.FilterTask
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.HomeworkDto
import com.knhdixh.attendance80.net.ProjectDto
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What a task row shows after the title. [kind] picks the colour: "overdue" red, "today" green, "date", "label", "plain". */
data class Meta(val text: String, val kind: String = "plain")

/** Pure helpers behind the Todoist screens (no Compose state), so they can be unit tested. */
object TaskModel {
    /** Todoist's API numbers priorities the other way round: 4 is "p1". */
    fun priorityLabel(priority: Int) = "p${5 - priority.coerceIn(1, 4)}"
    fun priorityColor(priority: Int): Color = when (priority) {
        4 -> Color(0xFFD1453B); 3 -> Color(0xFFEB8909); 2 -> Color(0xFF246FE0); else -> Color(0xFF808080)
    }

    fun todayKey(now: Long, zone: ZoneId): String = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    val manual: Comparator<HomeworkDto> = compareBy<HomeworkDto>({ it.position }, { it.due_at ?: Long.MAX_VALUE }, { it.id })

    fun children(d: AppData, id: Long) = d.homework.filter { it.parent_id == id }.sortedWith(manual)
    fun open(d: AppData) = d.homework.filter { !it.done }
    /** Tasks that live in the Inbox: no project, not a subtask. */
    fun inbox(d: AppData) = d.homework.filter { !it.done && it.project_id == null && it.parent_id == null }.sortedWith(manual)

    fun overdueAndToday(d: AppData, today: String, exclude: Set<Long> = emptySet()): Pair<List<HomeworkDto>, List<HomeworkDto>> {
        val rows = d.homework.filter { !it.done && it.due_date != null && it.due_date <= today && it.id !in exclude }
        val sort = compareBy<HomeworkDto>({ it.due_date }, { it.due_time ?: "" }, { -it.priority }, { it.position }, { it.id })
        return rows.filter { it.due_date!! < today }.sortedWith(sort) to rows.filter { it.due_date == today }.sortedWith(sort)
    }

    /** The project and its subprojects, depth first, with the depth. Archived ones are left out unless [archived]. */
    fun projectTree(d: AppData, archived: Boolean = false): List<Pair<ProjectDto, Int>> {
        val out = mutableListOf<Pair<ProjectDto, Int>>()
        fun walk(parent: Long?, depth: Int) {
            d.projects.filter { it.parent_id == parent && (archived || !it.archived) }.sortedWith(compareBy({ it.position }, { it.id }))
                .forEach { out += it to depth; walk(it.id, depth + 1) }
        }
        walk(null, 0)
        return out
    }

    fun projectIds(d: AppData, id: Long): Set<Long> {
        val all = mutableSetOf(id)
        var grew = true
        while (grew) { grew = false; d.projects.forEach { if (it.parent_id in all && all.add(it.id)) grew = true } }
        return all
    }

    fun filterTask(h: HomeworkDto) = FilterTask(h.id, h.title, h.notes, h.project_id, h.section_id, h.parent_id, h.due_date, h.due_time, h.priority, h.labels,
        h.course_id, h.recurrence, h.done, h.position)

    fun filterContext(d: AppData, today: String) = FilterContext(today, d.homework.map(::filterTask),
        d.projects.map { FilterName(it.id, it.name, it.parent_id, it.archived) }, d.sections.map { FilterName(it.id, it.name) },
        d.labels.map { FilterName(it.id, it.name) }, d.courses.map { FilterName(it.id, it.name) })

    /** "Today", "Tomorrow", a weekday within the week, or the date. */
    fun dayLabel(date: String, today: String): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        val t = LocalDate.parse(today)
        return when {
            d == t -> "Today"
            d == t.plusDays(1) -> "Tomorrow"
            d == t.minusDays(1) -> "Yesterday"
            d.isAfter(t) && d.isBefore(t.plusDays(7)) -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            d.year == t.year -> d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            else -> d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
        }
    }

    fun meta(h: HomeworkDto, d: AppData, today: String, showProject: Boolean, showSubtaskCount: Boolean = true): List<Meta> = buildList {
        h.due_date?.let { day ->
            val kind = if (day < today && !h.done) "overdue" else if (day == today) "today" else "date"
            add(Meta(dayLabel(day, today) + (h.due_time?.let { " $it" } ?: ""), kind))
        }
        if (h.recurrence != null) add(Meta("↻ ${h.recurrence}", "recurrence"))
        h.deadline_date?.let { add(Meta("Deadline ${dayLabel(it, today)}", if (it < today && !h.done) "overdue" else "plain")) }
        h.labels.mapNotNull { id -> d.labels.find { it.id == id }?.name }.forEach { add(Meta("@$it", "label")) }
        if (showSubtaskCount) {
            val subs = d.homework.filter { it.parent_id == h.id }
            if (subs.isNotEmpty()) add(Meta("${subs.count { it.done }}/${subs.size}"))
        }
        if (h.comment_count > 0) add(Meta("${h.comment_count} comment${if (h.comment_count == 1) "" else "s"}"))
        h.course_id?.let { id -> d.courses.find { it.id == id }?.let { add(Meta(it.name, "course")) } }
        if (showProject) {
            val p = h.project_id?.let { id -> d.projects.find { it.id == id }?.name } ?: "Inbox"
            add(Meta("#$p", "project"))
        }
    }

    /** Bold, italic, code and links, as much of Markdown as a task title needs. */
    fun inlineMarkdown(text: String, link: Color = Color(0xFF246FE0)): AnnotatedString = buildAnnotatedString {
        val token = Regex("""\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`|\[(.+?)]\((.+?)\)""")
        var at = 0
        for (m in token.findAll(text)) {
            append(text.substring(at, m.range.first))
            when {
                m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
                m.groups[2] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[2]) }
                m.groups[3] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(m.groupValues[3]) }
                else -> withStyle(SpanStyle(color = link)) { append(m.groupValues[4]) }
            }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }

    /** Everything that matches [q] (case-insensitive) in tasks, comments' counts are not searched (the app has no comment text in the snapshot). */
    fun matchesTask(h: HomeworkDto, q: String) = q.isNotBlank() && (h.title.contains(q, true) || h.notes.contains(q, true))

    private fun siblings(d: AppData, project: Long?, section: Long?, parent: Long?, without: Long) =
        d.homework.filter { !it.done && it.id != without && it.project_id == project && it.section_id == section && it.parent_id == parent }.sortedWith(manual)

    private fun moveBody(id: Long, project: Long?, section: Long?, parent: Long?, position: Int) = buildJsonObject {
        put("ids", JsonArray(listOf(JsonPrimitive(id))))
        put("project_id", project?.let(::JsonPrimitive) ?: JsonNull)
        put("section_id", section?.let(::JsonPrimitive) ?: JsonNull)
        put("parent_id", parent?.let(::JsonPrimitive) ?: JsonNull)
        put("position", position)
    }

    /**
     * The `POST /api/homework/move` body for a drag that ended on [target] (below it when [below]); [nest] +1 makes the
     * task a subtask of the row above it, -1 takes it out of its parent. [rows] is what the screen shows, top to bottom.
     * Null when there is nothing to do.
     */
    fun planMove(d: AppData, rows: List<HomeworkDto>, id: Long, target: Long?, below: Boolean, nest: Int = 0): JsonObject? {
        val h = rows.find { it.id == id } ?: return null
        if (nest > 0) {
            val above = rows.subList(0, rows.indexOf(h)).lastOrNull { it.parent_id == h.parent_id && it.section_id == h.section_id && it.project_id == h.project_id } ?: return null
            return moveBody(id, above.project_id, above.section_id, above.id, children(d, above.id).count { it.id != id && !it.done })
        }
        if (nest < 0) {
            val parent = d.homework.find { it.id == h.parent_id } ?: return null
            val at = siblings(d, parent.project_id, parent.section_id, parent.parent_id, id).indexOfFirst { it.id == parent.id } + 1
            return moveBody(id, parent.project_id, parent.section_id, parent.parent_id, at)
        }
        val t = rows.find { it.id == target }?.takeIf { it.id != id } ?: return null
        val list = siblings(d, t.project_id, t.section_id, t.parent_id, id)
        return moveBody(id, t.project_id, t.section_id, t.parent_id, list.indexOfFirst { it.id == t.id } + if (below) 1 else 0)
    }

    /** Moves a subtask to [position] among its siblings (where its parent keeps it). */
    fun moveWithin(h: HomeworkDto, position: Int) = moveBody(h.id, h.project_id, h.section_id, h.parent_id, position)

    /** [roots] followed by their open subtasks, indented by depth. Subtasks of ids in [collapsed] are left out. */
    fun flatten(d: AppData, roots: List<HomeworkDto>, collapsed: Set<Long> = emptySet()): List<TaskEntry> {
        val out = mutableListOf<TaskEntry>()
        fun add(h: HomeworkDto, depth: Int) {
            out += TaskEntry(h, depth)
            if (h.id !in collapsed) d.homework.filter { it.parent_id == h.id && !it.done }.sortedWith(manual).forEach { add(it, depth + 1) }
        }
        roots.forEach { add(it, 0) }
        return out
    }

    /** How a project page orders and groups its tasks. [sort]: manual, date or priority. [group]: none, date, priority or label. */
    data class ProjectOptions(val sort: String = "manual", val group: String = "none", val completed: Boolean = false)

    private fun comparator(sort: String): Comparator<HomeworkDto> = when (sort) {
        "date" -> compareBy<HomeworkDto>({ it.due_date ?: "9999" }, { it.due_time ?: "" }, { -it.priority }, { it.position }, { it.id })
        "priority" -> compareBy<HomeworkDto>({ -it.priority }, { it.due_date ?: "9999" }, { it.position }, { it.id })
        else -> manual
    }

    private fun groupKey(h: HomeworkDto, group: String, d: AppData, today: String): Pair<Int, String> = when (group) {
        "priority" -> (4 - h.priority) to if (h.priority == 1) "No priority" else priorityLabel(h.priority)
        "date" -> if (h.due_date == null) 9 to "No date" else if (h.due_date < today) 0 to "Overdue" else 1 to ("${h.due_date}" + "|" + dayLabel(h.due_date, today))
        else -> (h.labels.firstOrNull()?.let { id -> d.labels.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 999) to
            (h.labels.firstOrNull()?.let { id -> d.labels.find { it.id == id }?.name?.let { "@$it" } } ?: "No label")
    }

    /**
     * The rows of a project page. Manual sort with no grouping keeps the sections (id "s<id>", then "s0" for tasks without one);
     * otherwise the sections give way to groups. Open top-level tasks come first, then, with [ProjectOptions.completed], the done ones.
     */
    fun projectEntries(d: AppData, projectId: Long, o: ProjectOptions, today: String, collapsedSections: Set<Long> = emptySet(), collapsedTasks: Set<Long> = emptySet()): List<ListEntry> {
        val ids = setOf(projectId)
        val roots = d.homework.filter { it.project_id in ids && it.parent_id == null }
        val open = roots.filter { !it.done }.sortedWith(comparator(o.sort))
        val out = mutableListOf<ListEntry>()
        if (o.group == "none") {
            val sections = d.sections.filter { it.project_id == projectId }.sortedWith(compareBy({ it.position }, { it.id }))
            out += flatten(d, open.filter { it.section_id == null }, collapsedTasks)
            for (s in sections) {
                val rows = open.filter { it.section_id == s.id }
                out += HeaderEntry(s.name, "s${s.id}", rows.size)
                if (s.id !in collapsedSections) out += flatten(d, rows, collapsedTasks)
            }
        } else {
            open.groupBy { groupKey(it, o.group, d, today) }.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (k, rows) ->
                out += HeaderEntry(k.second.substringAfter('|'), "g${k.first}${k.second}", rows.size)
                out += flatten(d, rows, collapsedTasks)
            }
        }
        if (o.completed) {
            val done = roots.filter { it.done }.sortedByDescending { it.completed_at ?: 0L }
            if (done.isNotEmpty()) { out += HeaderEntry("Completed", "done", done.size); out += done.map { TaskEntry(it) } }
        }
        return out
    }

    /** Which column a card dropped at ([x], [y]) lands in, and where: [columns] are (section id, left, right), [cards] (id, section id, top, height). */
    fun boardDrop(columns: List<Triple<Long?, Float, Float>>, cards: List<Triple<Long, Long?, Pair<Float, Float>>>, id: Long, x: Float, y: Float): Pair<Long?, Int>? {
        val col = columns.find { x >= it.second && x < it.third } ?: return null
        val inCol = cards.filter { it.second == col.first && it.first != id }.sortedBy { it.third.first }
        val at = inCol.count { it.third.first + it.third.second / 2 < y }
        return col.first to at
    }

    fun boardMove(d: AppData, projectId: Long, id: Long, section: Long?, position: Int) = moveBody(id, projectId, section, null, position)
}


sealed interface ListEntry { val key: String }
data class TaskEntry(val h: HomeworkDto, val depth: Int = 0) : ListEntry { override val key get() = "t${h.id}" }
data class HeaderEntry(val title: String, val id: String, val count: Int? = null, val trailing: (@androidx.compose.runtime.Composable () -> Unit)? = null) : ListEntry { override val key get() = "h$id" }