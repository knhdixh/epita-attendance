package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.logic.Habits.addDays
import kotlinx.serialization.json.*

/** Port of `public/filters.js`: Todoist's filter language (a subset) plus `course:` and `school`. Checked against the JS by `FiltersGoldenTest`. */
sealed class FilterNode {
    data class Date(val `is`: String) : FilterNode()
    data class Before(val day: String) : FilterNode()
    data class After(val day: String) : FilterNode()
    data class Range(val from: String, val to: String) : FilterNode()
    data object NoDate : FilterNode()
    data class Priority(val n: Int) : FilterNode()
    data class Project(val name: String, val sub: Boolean) : FilterNode()
    data class Section(val name: String) : FilterNode()
    data class Label(val name: String) : FilterNode()
    data object NoLabels : FilterNode()
    data object Subtask : FilterNode()
    data object Recurring : FilterNode()
    data class Search(val text: String) : FilterNode()
    data class Course(val text: String) : FilterNode()
    data object School : FilterNode()
    data object All : FilterNode()
    data class Not(val of: FilterNode) : FilterNode()
    data class And(val of: List<FilterNode>) : FilterNode()
    data class Or(val of: List<FilterNode>) : FilterNode()

    /** The same shape as the JS tree, for the golden test. */
    fun toJson(): JsonObject = buildJsonObject {
        when (val n = this@FilterNode) {
            is Date -> { put("t", "date"); put("is", n.`is`) }
            is Before -> { put("t", "before"); put("day", n.day) }
            is After -> { put("t", "after"); put("day", n.day) }
            is Range -> { put("t", "range"); put("from", n.from); put("to", n.to) }
            NoDate -> put("t", "nodate")
            is Priority -> { put("t", "priority"); put("n", n.n) }
            is Project -> { put("t", "project"); put("name", n.name); put("sub", n.sub) }
            is Section -> { put("t", "section"); put("name", n.name) }
            is Label -> { put("t", "label"); put("name", n.name) }
            NoLabels -> put("t", "nolabels")
            Subtask -> put("t", "subtask")
            Recurring -> put("t", "recurring")
            is Search -> { put("t", "search"); put("text", n.text) }
            is Course -> { put("t", "course"); put("text", n.text) }
            School -> put("t", "school")
            All -> put("t", "all")
            is Not -> { put("t", "not"); put("of", n.of.toJson()) }
            is And -> { put("t", "and"); put("of", JsonArray(n.of.map { it.toJson() })) }
            is Or -> { put("t", "or"); put("of", JsonArray(n.of.map { it.toJson() })) }
        }
    }
}

data class FilterParse(val ok: Boolean, val lists: List<FilterNode> = emptyList(), val error: String? = null, val at: Int = 0)

/** The fields of a task a filter looks at. */
data class FilterTask(
    val id: Long, val title: String = "", val notes: String? = "", val project_id: Long? = null, val section_id: Long? = null, val parent_id: Long? = null,
    val due_date: String? = null, val due_time: String? = null, val priority: Int = 1, val labels: List<Long> = emptyList(), val course_id: Long? = null,
    val recurrence: String? = null, val done: Boolean = false, val position: Int = 0,
)
data class FilterName(val id: Long, val name: String, val parent_id: Long? = null, val archived: Boolean = false)
data class FilterContext(
    val today: String, val tasks: List<FilterTask> = emptyList(), val projects: List<FilterName> = emptyList(), val sections: List<FilterName> = emptyList(),
    val labels: List<FilterName> = emptyList(), val courses: List<FilterName> = emptyList(),
)
data class FilterRun(val ok: Boolean, val lists: List<List<FilterTask>> = emptyList(), val tasks: List<FilterTask> = emptyList(), val error: String? = null, val at: Int = 0)

object Filters {
    const val NO_DATE = "9999-12-31"
    private const val HELP = "Try \"today\", \"overdue\", \"p1\", \"#project\", \"@label\" or \"search: words\"."

    private class Piece(val op: Char?, val text: String?, val at: Int)
    private class Fail(message: String, val at: Int) : Exception(message)

    private fun pieces(query: String): List<Piece> {
        val out = mutableListOf<Piece>()
        var text = ""; var at = 0; var quoted = false
        fun flush() { if (text.isNotBlank()) out.add(Piece(null, text.trim(), at + text.length - text.trimStart().length)); text = "" }
        for (i in query.indices) {
            val c = query[i]
            if (c == '"') quoted = !quoted
            if (!quoted && c in "&|!(),") { flush(); out.add(Piece(c, null, i)); at = i + 1; continue }
            if (text.isEmpty()) at = i
            text += c
        }
        flush()
        return out
    }

    private fun unquote(s: String) = s.trim().replace(jsRegex("^\"(.*)\"$"), "$1").trim()

    private fun term(raw: String, today: String): Any { // FilterNode, or an error String
        val t = raw.lowercase().replace(jsRegex("\\s+"), " ").trim()
        when (t) {
            "today" -> return FilterNode.Date(today)
            "tomorrow" -> return FilterNode.Date(addDays(today, 1))
            "yesterday" -> return FilterNode.Date(addDays(today, -1))
            "overdue", "over due" -> return FilterNode.Before(today)
            "no date", "no due date" -> return FilterNode.NoDate
            "all", "view all" -> return FilterNode.All
            "subtask" -> return FilterNode.Subtask
            "recurring" -> return FilterNode.Recurring
            "no labels", "no label" -> return FilterNode.NoLabels
            "no priority" -> return FilterNode.Priority(1)
            "school" -> return FilterNode.School
        }
        jsRegex("^p([1-4])$").find(t)?.let { return FilterNode.Priority(5 - it.groupValues[1].toInt()) }
        jsRegex("^next (\\d{1,3}) days?$").find(t)?.let { return FilterNode.Range(today, addDays(today, it.groupValues[1].toInt())) }

        fun dateArg(label: String, arg: String, make: (String) -> FilterNode): Any {
            val day = QuickAddParser.dayFromPhrase(arg, today)
                ?: return if (arg.trim().isNotEmpty()) "I couldn't read the date \"${arg.trim()}\" in \"$label\"." else "\"$label\" needs a date, for example \"$label fri\"."
            return make(day)
        }
        val ci = RegexOption.IGNORE_CASE
        jsRegex("^\\s*date\\s*:\\s*(.*)$", ci).find(raw)?.let { return dateArg("date:", it.groupValues[1]) { d -> FilterNode.Date(d) } }
        jsRegex("^\\s*(?:due\\s+)?before\\s*:\\s*(.*)$", ci).find(raw)?.let { return dateArg("due before:", it.groupValues[1]) { d -> FilterNode.Before(d) } }
        jsRegex("^\\s*(?:due\\s+)?after\\s*:\\s*(.*)$", ci).find(raw)?.let { return dateArg("due after:", it.groupValues[1]) { d -> FilterNode.After(d) } }
        jsRegex("^\\s*search\\s*:\\s*(.*)$", ci).find(raw)?.let {
            val text = unquote(it.groupValues[1])
            return if (text.isNotEmpty()) FilterNode.Search(text.lowercase()) else "\"search:\" needs some words, for example \"search: buy milk\"."
        }
        jsRegex("^\\s*course\\s*:\\s*(.*)$", ci).find(raw)?.let {
            val text = unquote(it.groupValues[1])
            return if (text.isNotEmpty()) FilterNode.Course(text.lowercase()) else "\"course:\" needs a course name, for example \"course: algorithms\"."
        }
        jsRegex("^\\s*##\\s*(.+)$").find(raw)?.let { return FilterNode.Project(unquote(it.groupValues[1]).lowercase(), true) }
        jsRegex("^\\s*#\\s*(.+)$").find(raw)?.let { return FilterNode.Project(unquote(it.groupValues[1]).lowercase(), false) }
        jsRegex("^\\s*/\\s*(.+)$").find(raw)?.let { return FilterNode.Section(unquote(it.groupValues[1]).lowercase()) }
        jsRegex("^\\s*@\\s*(\\S+)$").find(raw)?.let { return FilterNode.Label(it.groupValues[1].lowercase()) }
        if (raw.trim().startsWith("@")) return "A label name has no spaces. Join labels with & or |."
        QuickAddParser.dayFromPhrase(raw.trim(), today)?.let { return FilterNode.Date(it) }
        return "I don't know \"${raw.trim()}\". $HELP"
    }

    /** Reads a query: one tree per comma-separated list, or the error and where it is. */
    fun parseFilter(query: String?, today: String): FilterParse {
        val q = query ?: ""
        val ps = pieces(q)
        if (ps.isEmpty()) return FilterParse(false, error = "Type a filter. $HELP", at = 0)
        var i = 0
        fun peek() = ps.getOrNull(i)
        fun fail(message: String, at: Int): Nothing = throw Fail(message, at)
        lateinit var or: () -> FilterNode
        fun atom(): FilterNode {
            val p = ps.getOrNull(i) ?: fail("The filter ends too soon. Something is missing after the last & | ! or (.", q.length)
            if (p.op == '(') {
                i++
                val inner = or()
                if (peek()?.op != ')') fail("Missing \")\".", p.at)
                i++
                return inner
            }
            if (p.op != null) fail("I didn't expect \"${p.op}\" here.", p.at)
            i++
            val r = term(p.text!!, today)
            if (r is String) fail(r, p.at)
            return r as FilterNode
        }
        fun not(): FilterNode {
            var negations = 0
            while (peek()?.op == '!') { i++; negations++ }
            var node = atom()
            repeat(negations) { node = FilterNode.Not(node) }
            return node
        }
        fun and(): FilterNode {
            val parts = mutableListOf(not())
            while (peek()?.op == '&') { i++; parts.add(not()) }
            return if (parts.size == 1) parts[0] else FilterNode.And(parts)
        }
        or = {
            val parts = mutableListOf(and())
            while (peek()?.op == '|') { i++; parts.add(and()) }
            if (parts.size == 1) parts[0] else FilterNode.Or(parts)
        }
        return try {
            val lists = mutableListOf<FilterNode>()
            while (i < ps.size) {
                if (peek()!!.op == ',') { i++; continue }
                lists.add(or())
                if (i < ps.size && peek()!!.op != ',') {
                    val p = peek()!!
                    fail(if (p.op == ')') "Too many \")\"." else "I didn't expect \"${p.op ?: p.text}\" here.", p.at)
                }
            }
            if (lists.isEmpty()) FilterParse(false, error = "Type a filter. $HELP", at = 0) else FilterParse(true, lists)
        } catch (e: Fail) {
            FilterParse(false, error = e.message, at = e.at)
        }
    }

    private fun dateOf(t: FilterTask) = t.due_date?.takeIf { it != NO_DATE }

    /** By date (none last), then time, then urgency (urgent first), then their place in the list. */
    fun sortTasks(tasks: List<FilterTask>): List<FilterTask> {
        fun day(t: FilterTask) = dateOf(t) ?: "9999-99-99"
        return tasks.sortedWith(compareBy<FilterTask> { day(it) }.thenBy { it.due_time ?: "" }.thenByDescending { it.priority }.thenBy { it.position }.thenBy { it.id })
    }

    private fun compile(ast: FilterNode, ctx: FilterContext): (FilterTask) -> Boolean {
        fun byName(list: List<FilterName>, name: String) = list.filter { it.name.lowercase() == name }.map { it.id }
        fun withSubs(ids: List<Long>): Set<Long> {
            val all = ids.toMutableSet()
            var grew = true
            while (grew) {
                grew = false
                for (p in ctx.projects) if (p.parent_id != null && p.parent_id in all && p.id !in all) { all.add(p.id); grew = true }
            }
            return all
        }
        fun go(n: FilterNode): (FilterTask) -> Boolean = when (n) {
            is FilterNode.And -> { val fs = n.of.map(::go); val f: (FilterTask) -> Boolean = { t -> fs.all { it(t) } }; f }
            is FilterNode.Or -> { val fs = n.of.map(::go); val f: (FilterTask) -> Boolean = { t -> fs.any { it(t) } }; f }
            is FilterNode.Not -> { val f = go(n.of); val g: (FilterTask) -> Boolean = { t -> !f(t) }; g }
            FilterNode.All -> { _ -> true }
            is FilterNode.Date -> { t -> dateOf(t) == n.`is` }
            is FilterNode.Before -> { t -> dateOf(t)?.let { it < n.day } == true }
            is FilterNode.After -> { t -> dateOf(t)?.let { it > n.day } == true }
            is FilterNode.Range -> { t -> dateOf(t)?.let { it >= n.from && it <= n.to } == true }
            FilterNode.NoDate -> { t -> dateOf(t) == null }
            is FilterNode.Priority -> { t -> t.priority == n.n }
            is FilterNode.Project -> {
                val inbox = n.name == "inbox"
                val ids: Set<Long> = if (n.sub) withSubs(byName(ctx.projects, n.name)) else byName(ctx.projects, n.name).toSet()
                val f: (FilterTask) -> Boolean = { t -> if (inbox) t.project_id == null else t.project_id in ids }
                f
            }
            is FilterNode.Section -> { val ids = byName(ctx.sections, n.name).toSet(); val f: (FilterTask) -> Boolean = { t -> t.section_id != null && t.section_id in ids }; f }
            is FilterNode.Label -> { val ids = byName(ctx.labels, n.name).toSet(); val f: (FilterTask) -> Boolean = { t -> t.labels.any { it in ids } }; f }
            FilterNode.NoLabels -> { t -> t.labels.isEmpty() }
            FilterNode.Subtask -> { t -> t.parent_id != null }
            FilterNode.Recurring -> { t -> !t.recurrence.isNullOrEmpty() }
            is FilterNode.Search -> { t -> t.title.lowercase().contains(n.text) || (t.notes ?: "").lowercase().contains(n.text) }
            is FilterNode.Course -> { val ids = ctx.courses.filter { it.name.lowercase().contains(n.text) }.map { it.id }.toSet(); val f: (FilterTask) -> Boolean = { t -> t.course_id != null && t.course_id in ids }; f }
            FilterNode.School -> { t -> t.course_id != null }
        }
        return go(ast)
    }

    /** Runs a query over the open tasks (those in archived projects are left out). */
    fun runFilter(query: String?, ctx: FilterContext): FilterRun {
        val parsed = parseFilter(query, ctx.today)
        if (!parsed.ok) return FilterRun(false, error = parsed.error, at = parsed.at)
        val archived = ctx.projects.filter { it.archived }.map { it.id }.toSet()
        val open = ctx.tasks.filter { !it.done && (it.project_id == null || it.project_id !in archived) }
        val lists = parsed.lists.map { ast -> sortTasks(open.filter(compile(ast, ctx))) }
        val seen = HashSet<Long>()
        return FilterRun(true, lists, lists.flatten().filter { seen.add(it.id) })
    }
}
