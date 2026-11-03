package com.knhdixh.attendance80.data

import com.knhdixh.attendance80.logic.Habits
import com.knhdixh.attendance80.logic.Recurrence
import com.knhdixh.attendance80.net.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * What a queued change does to the snapshot, before the server has answered: the same rules as the server's
 * (`src/homework-store.js`, `src/tasks.js`, `src/habits.js`), so the screen shows the result at once and the server's
 * row replaces it later. Pure functions on [AppData].
 */
object LocalEffects {
    private const val NO_PLACE = Long.MIN_VALUE

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
    private fun JsonObject.int(k: String): Int? = long(k)?.toInt()
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.booleanOrNull ?: it.longOrNull?.let { n -> n != 0L } }
    private fun JsonObject.ids(k: String): List<Long>? = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }

    /** `m` applied to [d]. A change that doesn't fit what the snapshot has (a row that is gone) changes nothing. */
    fun apply(d: AppData, m: QueuedMutation, zone: ZoneId = ZoneId.systemDefault()): AppData = when (m.kind) {
        "task.create" -> createTask(d, m)
        "task.update" -> updateTask(d, m, idOf(m), zone)
        "task.delete" -> deleteTasks(d, setOf(idOf(m)))
        "task.complete" -> complete(d, idOf(m), m.at, m.body.str("occurrence"))
        "task.uncomplete" -> uncomplete(d, idOf(m))
        "task.move" -> move(d, m.body)
        "project.create" -> d.copy(projects = d.projects + ProjectDto(m.temp!!, m.body.long("parent_id"), m.body.str("name").orEmpty().trim(), m.body.str("color"),
            m.body.str("view") ?: "list", m.body.int("position") ?: ((d.projects.maxOfOrNull { it.position } ?: -1) + 1), m.body.bool("favorite") ?: false, m.body.bool("archived") ?: false, m.clientId, m.at, m.at))
        "project.update" -> d.copy(projects = d.projects.map { p -> if (p.id != idOf(m)) p else p.copy(
            parent_id = if ("parent_id" in m.body) m.body.long("parent_id") else p.parent_id, name = m.body.str("name")?.trim() ?: p.name,
            color = if ("color" in m.body) m.body.str("color") else p.color, view = m.body.str("view") ?: p.view, position = m.body.int("position") ?: p.position,
            favorite = m.body.bool("favorite") ?: p.favorite, archived = m.body.bool("archived") ?: p.archived, updated_at = m.at) })
        "project.delete" -> deleteProject(d, idOf(m))
        "project.reorder" -> d.copy(projects = reorder(d.projects, m.body.ids("ids"), { it.id }, { p, i -> p.copy(position = i) }))
        "section.create" -> d.copy(sections = d.sections + SectionDto(m.temp!!, m.body.long("project_id") ?: 0, m.body.str("name").orEmpty().trim(),
            m.body.int("position") ?: ((d.sections.filter { it.project_id == m.body.long("project_id") }.maxOfOrNull { it.position } ?: -1) + 1), m.clientId, m.at, m.at))
        "section.update" -> d.copy(sections = d.sections.map { s -> if (s.id != idOf(m)) s else s.copy(name = m.body.str("name")?.trim() ?: s.name, position = m.body.int("position") ?: s.position, updated_at = m.at) })
        "section.delete" -> { val id = idOf(m); deleteTasks(d.copy(sections = d.sections.filterNot { it.id == id }), d.homework.filter { it.section_id == id }.map { it.id }.toSet()) }
        "section.reorder" -> d.copy(sections = reorder(d.sections, m.body.ids("ids"), { it.id }, { s, i -> s.copy(position = i) }))
        "label.create" -> d.copy(labels = d.labels + LabelDto(m.temp!!, m.body.str("name").orEmpty().trim(), m.body.str("color"),
            m.body.int("position") ?: ((d.labels.maxOfOrNull { it.position } ?: -1) + 1), m.body.bool("favorite") ?: false, m.clientId, m.at, m.at))
        "label.update" -> d.copy(labels = d.labels.map { l -> if (l.id != idOf(m)) l else l.copy(name = m.body.str("name")?.trim() ?: l.name,
            color = if ("color" in m.body) m.body.str("color") else l.color, position = m.body.int("position") ?: l.position, favorite = m.body.bool("favorite") ?: l.favorite, updated_at = m.at) })
        "label.delete" -> { val id = idOf(m); d.copy(labels = d.labels.filterNot { it.id == id }, homework = d.homework.map { if (id in it.labels) it.copy(labels = it.labels - id) else it }) }
        "label.reorder" -> d.copy(labels = reorder(d.labels, m.body.ids("ids"), { it.id }, { l, i -> l.copy(position = i) }))
        "filter.create" -> d.copy(filters = d.filters + FilterDto(m.temp!!, m.body.str("name").orEmpty().trim(), m.body.str("query").orEmpty(), m.body.str("color"),
            m.body.int("position") ?: ((d.filters.maxOfOrNull { it.position } ?: -1) + 1), m.body.bool("favorite") ?: false, m.clientId, m.at, m.at))
        "filter.update" -> d.copy(filters = d.filters.map { f -> if (f.id != idOf(m)) f else f.copy(name = m.body.str("name")?.trim() ?: f.name, query = m.body.str("query") ?: f.query,
            color = if ("color" in m.body) m.body.str("color") else f.color, position = m.body.int("position") ?: f.position, favorite = m.body.bool("favorite") ?: f.favorite, updated_at = m.at) })
        "filter.delete" -> d.copy(filters = d.filters.filterNot { it.id == idOf(m) })
        "filter.reorder" -> d.copy(filters = reorder(d.filters, m.body.ids("ids"), { it.id }, { f, i -> f.copy(position = i) }))
        "comment.create" -> bump(d, m.about, 1)
        "comment.delete" -> bump(d, m.about, -1)
        "reminder.create" -> d.copy(homework = d.homework.map { if (it.id == m.about) it.copy(reminders = it.reminders + TaskReminderDto(m.temp!!, m.body.long("at"), m.body.int("before_minutes"))) else it })
        "reminder.update" -> d.copy(homework = d.homework.map { h -> if (h.reminders.none { it.id == idOf(m) }) h else h.copy(reminders = h.reminders.map { r ->
            if (r.id == idOf(m)) TaskReminderDto(r.id, m.body.long("at"), m.body.int("before_minutes")) else r }) })
        "reminder.delete" -> d.copy(homework = d.homework.map { h -> if (h.reminders.none { it.id == idOf(m) }) h else h.copy(reminders = h.reminders.filterNot { it.id == idOf(m) }) })
        "habit.create" -> createHabit(d, m)
        "habit.update" -> d.copy(habits = d.habits.copy(list = d.habits.list.map { h -> if (h.id != idOf(m)) h else updateHabit(h, m.body) }))
        "habit.delete" -> { val id = idOf(m); d.copy(habits = d.habits.copy(list = d.habits.list.filterNot { it.id == id }, logs = d.habits.logs.filterNot { it.habit_id == id })) }
        "habit.log" -> { val id = idOf(m, 2); val day = m.body.str("day").orEmpty(); val n = m.body.int("count") ?: 0
            d.copy(habits = d.habits.copy(logs = d.habits.logs.filterNot { it.habit_id == id && it.day == day } + listOfNotNull(HabitLogDto(id, day, n).takeIf { n > 0 }))) }
        "habit.order" -> { val ids = m.body.ids("ids").orEmpty(); d.copy(habits = d.habits.copy(list = d.habits.list.map { h -> ids.indexOf(h.id).takeIf { it >= 0 }?.let { h.copy(position = it) } ?: h })) }
        "attendance.set" -> d.copy(events = d.events.map { e -> if (e.id != idOf(m)) e else e.copy(status = if ("status" in m.body) m.body.str("status") else e.status) })
        else -> d
    }

    /** The id a change is about: the last numeric segment of its path (`api/homework/12/complete` is 12; [from] counts from the end). */
    private fun idOf(m: QueuedMutation, fromEnd: Int = 1): Long {
        val numbers = m.path.split('/').mapNotNull { it.toLongOrNull() }
        return numbers.getOrNull(numbers.size - fromEnd) ?: numbers.firstOrNull() ?: NO_PLACE
    }

    private fun <T> reorder(list: List<T>, ids: List<Long>?, id: (T) -> Long, set: (T, Int) -> T): List<T> =
        if (ids == null) list else list.map { x -> ids.indexOf(id(x)).takeIf { it >= 0 }?.let { set(x, it) } ?: x }

    private fun bump(d: AppData, id: Long?, by: Int) = d.copy(homework = d.homework.map { if (it.id == id) it.copy(comment_count = maxOf(0, it.comment_count + by)) else it })

    // ------------------------------------------------------------ tasks

    /** `due_at` as the server computes it: the due time in the task's zone, or the last millisecond of the day. */
    fun dueAt(date: String?, time: String?, tz: String?): Long? {
        if (date == null) return null
        val zone = runCatching { ZoneId.of(tz ?: "UTC") }.getOrDefault(ZoneId.of("UTC"))
        val day = LocalDate.parse(date)
        return if (time != null) day.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
        else day.atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli() + 999
    }

    /** [id] and every task below it. */
    fun subtree(d: AppData, id: Long): List<HomeworkDto> = descendants(d.homework, setOf(id)).let { ids -> d.homework.filter { it.id in ids } }

    private fun descendants(rows: List<HomeworkDto>, ids: Set<Long>): Set<Long> {
        val all = ids.toMutableSet()
        var grew = true
        while (grew) { grew = false; for (h in rows) if (h.parent_id != null && h.parent_id in all && all.add(h.id)) grew = true }
        return all
    }

    private fun lastPosition(rows: List<HomeworkDto>, projectId: Long?, sectionId: Long?, parentId: Long?) =
        (rows.filter { it.project_id == projectId && it.section_id == sectionId && it.parent_id == parentId }.maxOfOrNull { it.position } ?: -1) + 1

    private fun createTask(d: AppData, m: QueuedMutation): AppData {
        if (d.homework.any { it.client_id == m.clientId }) return d
        val b = m.body
        val parent = b.long("parent_id")?.let { id -> d.homework.find { it.id == id } }
        val section = b.long("section_id")?.let { id -> d.sections.find { it.id == id } }
        val project = parent?.project_id ?: section?.project_id ?: b.long("project_id")
        val sectionId = parent?.section_id ?: b.long("section_id")
        val date = b.str("due_date")?.takeIf { it != "9999-12-31" }
        val time = if (date == null) null else b.str("due_time")
        val tz = b.str("tz") ?: ZoneId.systemDefault().id
        val row = HomeworkDto(
            id = m.temp!!, course_id = b.long("course_id"), title = b.str("title").orEmpty().trim(), notes = b.str("notes").orEmpty(),
            due_date = date, due_time = time, tz = tz, due_at = dueAt(date, time, tz), remind_minutes = b.int("remind_minutes"), done = false,
            created_at = m.at, updated_at = m.at, effort_minutes = b.int("effort_minutes"), planned_at = b.long("planned_at"), planned_minutes = b.int("planned_minutes"),
            project_id = project, section_id = sectionId, parent_id = b.long("parent_id"), priority = b.int("priority") ?: 1,
            position = b.int("position") ?: lastPosition(d.homework, project, sectionId, b.long("parent_id")), recurrence = b.str("recurrence")?.takeIf { it.isNotEmpty() },
            deadline_date = b.str("deadline_date"), client_id = m.clientId, labels = b.ids("label_ids").orEmpty(),
        )
        return d.copy(homework = (d.homework + row).sortedBy { it.due_at ?: Long.MAX_VALUE })
    }

    private fun updateTask(d: AppData, m: QueuedMutation, id: Long, zone: ZoneId): AppData {
        val b = m.body
        var out = d.copy(homework = d.homework.map { h ->
            if (h.id != id) h else {
                val date = if ("due_date" in b) b.str("due_date")?.takeIf { it != "9999-12-31" } else h.due_date
                val time = if (date == null) null else if ("due_time" in b) b.str("due_time") else h.due_time
                val tz = b.str("tz") ?: h.tz
                h.copy(
                    title = b.str("title")?.trim() ?: h.title, notes = if ("notes" in b) b.str("notes").orEmpty() else h.notes,
                    course_id = if ("course_id" in b) b.long("course_id") else h.course_id,
                    due_date = date, due_time = time, tz = tz,
                    due_at = if (listOf("due_date", "due_time", "tz").any { it in b }) dueAt(date, time, tz) else h.due_at,
                    remind_minutes = if ("remind_minutes" in b) b.int("remind_minutes") else h.remind_minutes,
                    effort_minutes = if ("effort_minutes" in b) b.int("effort_minutes") else h.effort_minutes,
                    planned_at = if ("planned_at" in b) b.long("planned_at") else h.planned_at,
                    planned_minutes = if ("planned_minutes" in b) b.int("planned_minutes") else h.planned_minutes,
                    priority = b.int("priority") ?: h.priority, deadline_date = if ("deadline_date" in b) b.str("deadline_date") else h.deadline_date,
                    recurrence = if ("recurrence" in b) b.str("recurrence")?.takeIf { it.isNotEmpty() } else h.recurrence,
                    labels = b.ids("label_ids") ?: h.labels, updated_at = m.at,
                )
            }
        }.sortedBy { it.due_at ?: Long.MAX_VALUE })
        if (listOf("project_id", "section_id", "parent_id", "position").any { it in b }) {
            out = move(out, buildJsonObject {
                put("ids", JsonArray(listOf(JsonPrimitive(id))))
                for (k in listOf("project_id", "section_id", "parent_id", "position")) if (k in b) put(k, b.getValue(k))
            })
        }
        if (b.bool("done") != null) {
            out = if (b.bool("done") == true) complete(out, id, m.at, b.str("occurrence"), zone) else uncomplete(out, id)
        }
        return out
    }

    private fun deleteTasks(d: AppData, ids: Set<Long>): AppData {
        val gone = descendants(d.homework, ids)
        return d.copy(homework = d.homework.filterNot { it.id in gone })
    }

    private fun deleteProject(d: AppData, id: Long): AppData {
        val tree = mutableSetOf(id)
        var grew = true
        while (grew) { grew = false; for (p in d.projects) if (p.parent_id != null && p.parent_id in tree && tree.add(p.id)) grew = true }
        val doomed = d.homework.filter { it.project_id in tree }.map { it.id }.toSet()
        return deleteTasks(d.copy(projects = d.projects.filterNot { it.id in tree }, sections = d.sections.filterNot { it.project_id in tree }), doomed)
    }

    /** Complete: a repeating task moves to its next date; anything else (and its open subtasks) is done. A request for a date the task has left does nothing. */
    fun complete(d: AppData, id: Long, now: Long, occurrence: String? = null, zone: ZoneId = ZoneId.systemDefault()): AppData {
        val row = d.homework.find { it.id == id } ?: return d
        if (row.done) return d
        if (row.recurrence != null && occurrence != null && occurrence != row.due_date) return d
        val ids = descendants(d.homework, setOf(id))
        if (row.recurrence != null && row.due_date != null) {
            val tz = row.tz ?: zone.id
            val today = Habits.keyInZone(now, tz)
            val rule = Recurrence.parseRecurrence(row.recurrence, today, row.due_date)
            val next = rule.rule?.let { Recurrence.nextOccurrence(it, row.due_date, today) }
            if (next != null) return d.copy(homework = d.homework.map { h ->
                when {
                    h.id == id -> h.copy(due_date = next, due_at = dueAt(next, h.due_time, tz), planned_at = null, planned_minutes = null, updated_at = now)
                    h.id in ids && h.done -> h.copy(done = false, completed_at = null, updated_at = now)
                    else -> h
                }
            }.sortedBy { it.due_at ?: Long.MAX_VALUE })
        }
        return d.copy(homework = d.homework.map { if (it.id in ids && !it.done) it.copy(done = true, completed_at = now, updated_at = now) else it })
    }

    /** Undo of a completion. A repeating task that moved on is put back by the server (it knows the date); here it stays until the reload. */
    fun uncomplete(d: AppData, id: Long): AppData = d.copy(homework = d.homework.map { if (it.id == id && it.done) it.copy(done = false, completed_at = null) else it })

    /** `POST /api/homework/move`: the destination is the three ids given (any left out are null; leave all out to reorder in place). */
    private fun move(d: AppData, b: JsonObject): AppData {
        val ids = b.ids("ids").orEmpty().filter { id -> d.homework.any { it.id == id } }
        if (ids.isEmpty()) return d
        val inPlace = listOf("project_id", "section_id", "parent_id").none { it in b }
        val first = d.homework.first { it.id == ids[0] }
        var parentId = if (inPlace) first.parent_id else b.long("parent_id")
        var sectionId = if (inPlace) first.section_id else b.long("section_id")
        var projectId = if (inPlace) first.project_id else b.long("project_id")
        if (parentId in ids) return d
        parentId?.let { p -> d.homework.find { it.id == p } }?.let { projectId = it.project_id; sectionId = it.section_id }
        sectionId?.let { s -> d.sections.find { it.id == s } }?.let { if (parentId == null) projectId = it.project_id }
        val below = descendants(d.homework, ids.toSet()) - ids.toSet()
        val siblings = d.homework.filter { h -> h.id !in ids && h.project_id == projectId && h.section_id == sectionId && h.parent_id == parentId }.sortedBy { it.position }.map { it.id }
        val at = (b.int("position") ?: siblings.size).coerceIn(0, siblings.size)
        val order = siblings.take(at) + ids + siblings.drop(at)
        return d.copy(homework = d.homework.map { h ->
            when {
                h.id in ids -> h.copy(project_id = projectId, section_id = sectionId, parent_id = parentId, position = order.indexOf(h.id))
                h.id in below -> h.copy(project_id = projectId, section_id = sectionId)
                h.id in order -> h.copy(position = order.indexOf(h.id))
                else -> h
            }
        })
    }

    // ------------------------------------------------------------ habits

    private fun schedule(e: JsonElement?): HabitScheduleDto? {
        val o = e as? JsonObject ?: return null
        return HabitScheduleDto(o.str("type") ?: return null, o.ids("days")?.map { it.toInt() }, o.int("n"))
    }

    private fun createHabit(d: AppData, m: QueuedMutation): AppData {
        if (d.habits.list.any { it.client_id == m.clientId }) return d
        val b = m.body
        val s = schedule(b["schedule"]) ?: HabitScheduleDto("daily")
        val h = HabitDto(m.temp!!, b.str("name").orEmpty(), b.str("icon"), b.str("color") ?: "blue", s, b.int("target") ?: 1, b.str("unit"), b.str("remind_at"),
            (d.habits.list.maxOfOrNull { it.position } ?: -1) + 1, false, m.at, m.clientId)
        return d.copy(habits = d.habits.copy(list = d.habits.list + h))
    }

    private fun updateHabit(h: HabitDto, b: JsonObject) = h.copy(
        name = b.str("name") ?: h.name, icon = if ("icon" in b) b.str("icon") else h.icon, color = b.str("color") ?: h.color,
        schedule = schedule(b["schedule"]) ?: h.schedule, target = b.int("target") ?: h.target, unit = if ("unit" in b) b.str("unit") else h.unit,
        remind_at = if ("remind_at" in b) b.str("remind_at") else h.remind_at, position = b.int("position") ?: h.position, archived = b.bool("archived") ?: h.archived,
    )

    // ------------------------------------------------------------ answers

    /** The temporary id [from] became [to] on the server: every reference to it in [d] follows. */
    fun remap(d: AppData, from: Long, to: Long): AppData = d.copy(
        homework = d.homework.map { h ->
            val moved = h.copy(id = if (h.id == from) to else h.id, project_id = if (h.project_id == from) to else h.project_id,
                section_id = if (h.section_id == from) to else h.section_id, parent_id = if (h.parent_id == from) to else h.parent_id,
                labels = h.labels.map { if (it == from) to else it })
            moved.copy(reminders = moved.reminders.map { if (it.id == from) it.copy(id = to) else it })
        },
        projects = d.projects.map { it.copy(id = if (it.id == from) to else it.id, parent_id = if (it.parent_id == from) to else it.parent_id) },
        sections = d.sections.map { it.copy(id = if (it.id == from) to else it.id, project_id = if (it.project_id == from) to else it.project_id) },
        labels = d.labels.map { if (it.id == from) it.copy(id = to) else it },
        filters = d.filters.map { if (it.id == from) it.copy(id = to) else it },
        habits = d.habits.copy(list = d.habits.list.map { if (it.id == from) it.copy(id = to) else it }, logs = d.habits.logs.map { if (it.habit_id == from) it.copy(habit_id = to) else it }),
    )

    /**
     * The server's answer to [m] folded into [d]. A row is replaced by the server's only when [settled] says no later queued
     * change touches it (or it would flicker back); otherwise only the fields the server decides are taken over.
     */
    fun reconcile(d: AppData, m: QueuedMutation, answer: JsonObject?, settled: (Long) -> Boolean): AppData {
        answer ?: return d
        val json = com.knhdixh.attendance80.net.ApiClient.json
        fun task(row: HomeworkDto, d: AppData) = d.copy(homework = d.homework.map { h ->
            if (h.id != row.id) h
            else if (settled(row.id)) (if ("labels" in answer) row else row.copy(labels = h.labels, reminders = h.reminders, comment_count = h.comment_count))
            else h.copy(created_at = row.created_at, updated_at = row.updated_at, client_id = row.client_id ?: h.client_id, gcal_event_id = row.gcal_event_id, sync_error = row.sync_error)
        }.sortedBy { it.due_at ?: Long.MAX_VALUE })
        return when {
            m.kind.startsWith("task.") && m.kind != "task.delete" && m.kind != "task.move" -> task(json.decodeFromJsonElement(HomeworkDto.serializer(), answer), d)
            m.kind == "task.move" -> (answer["homework"] as? JsonArray).orEmpty().fold(d) { acc, e -> task(json.decodeFromJsonElement(HomeworkDto.serializer(), e), acc) }
            m.kind == "project.create" || m.kind == "project.update" -> json.decodeFromJsonElement(ProjectDto.serializer(), answer).let { r -> d.copy(projects = d.projects.map { if (it.id == r.id && settled(r.id)) r else it }) }
            m.kind == "section.create" || m.kind == "section.update" -> json.decodeFromJsonElement(SectionDto.serializer(), answer).let { r -> d.copy(sections = d.sections.map { if (it.id == r.id && settled(r.id)) r else it }) }
            m.kind == "label.create" || m.kind == "label.update" -> json.decodeFromJsonElement(LabelDto.serializer(), answer).let { r -> d.copy(labels = d.labels.map { if (it.id == r.id && settled(r.id)) r else it }) }
            m.kind == "filter.create" || m.kind == "filter.update" -> json.decodeFromJsonElement(FilterDto.serializer(), answer).let { r -> d.copy(filters = d.filters.map { if (it.id == r.id && settled(r.id)) r else it }) }
            m.kind == "habit.create" || m.kind == "habit.update" -> json.decodeFromJsonElement(HabitDto.serializer(), answer).let { r -> d.copy(habits = d.habits.copy(list = d.habits.list.map { if (it.id == r.id && settled(r.id)) r else it })) }
            m.kind == "reminder.create" -> d.copy(homework = d.homework.map { h -> h.copy(reminders = h.reminders.map { r -> if (r.id == (answer["id"] as? JsonPrimitive)?.longOrNull) TaskReminderDto(r.id, (answer["at"] as? JsonPrimitive)?.longOrNull, (answer["before_minutes"] as? JsonPrimitive)?.longOrNull?.toInt()) else r }) })
            else -> d
        }
    }
}
