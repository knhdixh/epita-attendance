package com.knhdixh.attendance80.ui.search

import com.knhdixh.attendance80.net.AppData
import java.text.Normalizer
import java.util.Locale

data class PaletteItem(val id: String, val section: String, val label: String, val route: String? = null,
                       val sub: String = "", val keywords: String = "", val home: Boolean = false)

private val pages = listOf("today" to "Today", "attendance" to "Attendance", "upcoming" to "Upcoming",
    "calendar" to "Calendar", "sessions" to "Sessions", "homework" to "Homework", "focus" to "Focus",
    "habits" to "Habits", "countdowns" to "Countdowns", "breaks" to "Breaks", "courses" to "Courses", "settings" to "Settings")
private val sections = listOf("Navigation", "Actions", "Focus", "Habits", "Countdowns", "Courses", "Homework", "Sessions")
private fun fold(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")

fun paletteItems(data: AppData?): List<PaletteItem> {
    if (data == null) return emptyList()
    val items = pages.map { (route, label) -> PaletteItem("nav:$route", "Navigation", "Go to $label", route, home = true) }.toMutableList()
    items += PaletteItem("add-homework", "Actions", "Add homework", "homework/new", home = true)
    items += PaletteItem("new-habit", "Actions", "New habit", "habits/new", keywords = "routine streak tracker add", home = true)
    items += PaletteItem("new-countdown", "Actions", "New countdown", "countdowns/new", keywords = "count up timer date exam", home = true)
    if (data.events.isNotEmpty()) items += PaletteItem("break", "Actions", "Plan a break", "breaks", keywords = "holiday trip time off skip", home = true)
    items += PaletteItem("logout", "Actions", "Sign out", keywords = "log out")
    items += data.courses.map { PaletteItem("course:${it.id}", "Courses", it.name, "course/${it.id}", keywords = "course") }
    items += data.events.map { PaletteItem("session:${it.id}", "Sessions", it.summary, "session/${it.id}", sub = it.location, keywords = "session") }
    items += data.homework.map { PaletteItem("homework:${it.id}", "Homework", it.title, "homework/${it.id}", sub = it.due_date.orEmpty()) }
    items += data.habits.list.filterNot { it.archived }.map { PaletteItem("habit:${it.id}", "Habits", it.name, "habits/${it.id}", keywords = "habit") }
    items += data.countdowns.map { PaletteItem("countdown:${it.id}", "Countdowns", it.title, "countdowns/${it.id}", keywords = "countdown", home = it.pinned) }
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

fun searchPalette(data: AppData?, query: String): List<PaletteItem> {
    val all = paletteItems(data)
    val q = fold(query.trim())
    return sections.flatMap { section ->
        val candidates = all.filter { it.section == section }
        if (q.isEmpty()) candidates.filter { it.home }
        else candidates.map { it to score(it, q) }.filter { it.second > 0 }.sortedByDescending { it.second }
            .take(if (section in setOf("Habits", "Countdowns", "Courses", "Homework", "Sessions", "Focus")) 6 else 8).map { it.first }
    }
}
