package com.knhdixh.attendance80.ui.search

import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.logic.Habits
import com.knhdixh.attendance80.logic.Countdowns
import java.time.ZoneId
import java.text.Normalizer
import java.util.Locale

data class PaletteItem(val id: String, val section: String, val label: String, val route: String? = null,
                       val sub: String = "", val keywords: String = "", val home: Boolean = false,
                       val action: PaletteAction? = null, val recent: Boolean = true)

private val pages = listOf("today" to "Today", "attendance" to "Attendance", "upcoming" to "Upcoming",
    "calendar" to "Calendar", "sessions" to "Sessions", "homework" to "Homework", "focus" to "Focus",
    "habits" to "Habits", "countdowns" to "Countdowns", "breaks" to "Breaks", "courses" to "Courses", "settings" to "Settings")
private val sections = listOf("Navigation", "Actions", "Focus", "Habits", "Countdowns", "Courses", "Homework", "Sessions")
private fun fold(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")

fun paletteItems(data: AppData?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<PaletteItem> {
    if (data == null) return emptyList()
    val items = pages.map { (route, label) -> PaletteItem("nav:$route", "Navigation", "Go to $label", route, home = true) }.toMutableList()
    items += PaletteItem("add-homework", "Actions", "Add homework", "homework/new", home = true, action = PaletteAction.OpenForm("homework"))
    items += PaletteItem("new-habit", "Actions", "New habit", "habits/new", keywords = "routine streak tracker add", home = true, action = PaletteAction.OpenForm("habit"))
    items += PaletteItem("new-countdown", "Actions", "New countdown", "countdowns/new", keywords = "count up timer date exam", home = true, action = PaletteAction.OpenForm("countdown"))
    if (data.events.isNotEmpty()) items += PaletteItem("break", "Actions", "Plan a break", "breaks", keywords = "holiday trip time off skip", home = true, action = PaletteAction.OpenForm("break"))
    items += PaletteItem("add-calendar", "Actions", "Add calendar", "settings", keywords = "zeus link ics subscribe", action = PaletteAction.OpenForm("calendar"))
    items += PaletteItem("upload", "Actions", "Upload .ics file", "settings", home = true, action = PaletteAction.OpenForm("calendar-upload"))
    items += PaletteItem("calendar:plan", "Actions", "Plan my week", "calendar", sub = "fit open to-dos into free time", keywords = "schedule auto time block calendar", home = true, action = PaletteAction.OpenForm("calendar-plan"))
    listOf("day" to "day view", "3day" to "3 days", "week" to "week view", "month" to "month view").forEach { (view, label) ->
        items += PaletteItem("calendar:$view", "Actions", "Calendar: $label", "calendar", keywords = "calendar", action = PaletteAction.OpenForm("calendar-view", input = view))
    }
    data.calendars.filter { it.kind == "link" }.forEach { c ->
        items += PaletteItem("sync:${c.id}", "Actions", "Update ${c.name}", keywords = "sync refresh calendar", action = PaletteAction.SyncCalendar(c.id))
    }
    val coursesById = data.courses.associateBy { it.id }
    val unmarked = data.events.filter { e -> e.start_at <= now && e.status == null && coursesById[e.course_id]?.let { it.tracked && it.attendance } == true }
    if (unmarked.isNotEmpty()) items += PaletteItem("mark-all", "Actions", "Mark ${unmarked.size} past session${if (unmarked.size == 1) "" else "s"} as attended", home = true, keywords = "unmarked present attendance", action = PaletteAction.BulkAttendance(unmarked.map { it.id }))
    items += PaletteItem("focus:start", "Focus", "Start focus", "focus", home = true, keywords = "pomodoro timer", action = PaletteAction.Focus("start"))
    listOf("pause" to "Pause timer", "resume" to "Resume timer", "skip" to "Skip timer phase", "stop" to "Stop timer").forEach { (command, label) ->
        items += PaletteItem("focus:$command", "Focus", label, "focus", keywords = "pomodoro focus timer", action = PaletteAction.Focus(command))
    }
    data.homework.filterNot { it.done }.take(12).forEach { h ->
        items += PaletteItem("focus:homework:${h.id}", "Focus", "Focus on: ${h.title}", "focus", action = PaletteAction.Focus("target", homeworkId = h.id), recent = false)
    }
    data.focus.settings.tasks.take(12).forEachIndexed { index, task ->
        items += PaletteItem("focus:task:$index", "Focus", "Focus on: $task", "focus", action = PaletteAction.Focus("target", task = task), recent = false)
    }
    items += PaletteItem("focus:new-task", "Focus", "New focus task", "focus", action = PaletteAction.Focus("new-task"))
    val today = Habits.localKey(now, zone)
    data.habits.list.filterNot { it.archived }.forEach { habit ->
        val counts = data.habits.logs.filter { it.habit_id == habit.id }.associate { it.day to it.count }
        val stats = Habits.habitStats(habit, counts, today, Habits.localKey(habit.created_at, zone))
        if (stats.due && !stats.todayDone) items += PaletteItem("habit:check:${habit.id}", "Habits", "Check off: ${habit.name}", home = true, keywords = "habit done tick", action = PaletteAction.CheckHabit(habit.id, today, (counts[today] ?: 0) + 1), recent = false)
    }
    if (data.integrations.feedUrl.isNotBlank()) items += PaletteItem("copy-feed", "Actions", "Copy timetable calendar link", keywords = "feed ics subscribe google apple outlook", action = PaletteAction.CopyFeed(data.integrations.feedUrl.replace(Regex("\\.ics$"), "/timetable.ics")))
    items += PaletteItem("logout", "Actions", "Sign out", keywords = "log out")
    items += data.courses.map { PaletteItem("course:${it.id}", "Courses", it.name, "course/${it.id}", keywords = "course") }
    val tracked = data.courses.filter { it.tracked }.map { it.id }.toSet()
    val nextBySummary = linkedMapOf<String, com.knhdixh.attendance80.net.EventDto>()
    data.events.filter { it.course_id in tracked }.sortedBy { it.start_at }.forEach { event ->
        if (nextBySummary[event.summary]?.end_at?.let { it <= now } != false) nextBySummary[event.summary] = event
    }
    items += nextBySummary.values.map { PaletteItem("session:${it.id}", "Sessions", it.summary, "session/${it.id}", sub = it.location, keywords = "session") }
    items += data.homework.map { PaletteItem("homework:${it.id}", "Homework", it.title, "homework/${it.id}", sub = it.due_date.orEmpty(), action = PaletteAction.OpenForm("homework-edit", entityId = it.id)) }
    items += data.habits.list.filterNot { it.archived }.map { PaletteItem("habit:${it.id}", "Habits", it.name, "habits/${it.id}", keywords = "habit") }
    items += data.countdowns.map { PaletteItem("countdown:${it.id}", "Countdowns", it.title, "countdowns/${it.id}", keywords = "countdown", home = it.pinned, action = PaletteAction.OpenForm("countdown-edit", entityId = it.id)) }
    return items
}

private fun score(item: PaletteItem, query: String): Double {
    val label = fold(item.label)
    val i = label.indexOf(query)
    if (i == 0) return 100.0
    if (i > 0) return (if (label[i - 1] in " (–-") 80.0 else 60.0) - i / 100.0
    val hay = "$label ${fold(item.sub)} ${fold(item.keywords)}"
    if (hay.contains(query)) return 40.0
    val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size > 1 && words.all { hay.contains(it) }) return 30.0
    val initials = label.split(Regex("[\\s–-]+")).filter { it.isNotEmpty() }.joinToString("") { it.take(1) }
    return if (query.length > 1 && initials.startsWith(query.replace(" ", ""))) 10.0 else 0.0
}

fun searchPalette(data: AppData?, query: String, recents: List<String> = emptyList(), now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): List<PaletteItem> {
    val all = paletteItems(data, now, zone)
    val q = fold(query.trim())
    val recent = if (q.isEmpty()) recents.mapNotNull { id -> all.find { it.id == id } }.distinctBy { it.id }.take(3).map { it.copy(section = "Recent") } else emptyList()
    val matches = sections.flatMap { section ->
        val candidates = all.filter { it.section == section }
        if (q.isEmpty()) candidates.filter { it.home && recent.none { r -> r.id == it.id } }
        else candidates.map { it to score(it, q) }.filter { it.second > 0 }.sortedByDescending { it.second }
            .take(if (section in setOf("Habits", "Countdowns", "Courses", "Homework", "Sessions", "Focus")) 6 else 8).map { it.first }
    }
    if (q.isEmpty() || data == null) return recent + matches
    val fallback = mutableListOf<PaletteItem>()
    val habit = Habits.parseHabit(query)
    if (habit.name.isNotBlank() && habit.chips.isNotEmpty()) fallback += PaletteItem("fallback:habit", "Add", "Add habit: ${habit.name}", sub = habit.chips.joinToString(" · "), action = PaletteAction.AddHabit(query), recent = false)
    val countdown = Countdowns.parseCountdown(query, now, zone)
    if (countdown != null && countdown.title.isNotBlank()) fallback += PaletteItem("fallback:countdown", "Add", "Add countdown: ${countdown.title}", action = PaletteAction.AddCountdown(query), recent = false)
    if (data.events.isNotEmpty()) fallback += PaletteItem("fallback:sessions", "Search", "Search all sessions for “${query.trim()}”", "sessions", action = PaletteAction.SessionSearch(query.trim()), recent = false)
    return matches + fallback
}
