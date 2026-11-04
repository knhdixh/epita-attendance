package com.knhdixh.attendance80.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.net.AppData

private data class BrowseRow(val label: String, val route: String, val icon: ImageVector, val depth: Int = 0, val count: Int? = null)

/** Browse: favourites, projects (nested), filters and labels, then the school screens, Focus, Habits, Countdowns and Settings. */
@Composable
fun BrowseScreen(data: AppData?, onNavigate: (String) -> Unit, onNewProject: () -> Unit = {}) {
    val projects = data?.let { d -> TaskModel.projectTree(d) }.orEmpty()
    fun count(id: Long) = data?.homework?.count { !it.done && it.project_id == id } ?: 0
    val favourites = buildList {
        data?.projects?.filter { it.favorite && !it.archived }?.forEach { add(BrowseRow(it.name, "project/${it.id}", Icons.Outlined.Folder, count = count(it.id))) }
        data?.filters?.filter { it.favorite }?.forEach { add(BrowseRow(it.name, "filter/${it.id}", Icons.Outlined.FilterList)) }
        data?.labels?.filter { it.favorite }?.forEach { add(BrowseRow(it.name, "label/${it.id}", Icons.Outlined.Label)) }
    }
    val school = listOf(
        BrowseRow("Attendance", "attendance", Icons.Outlined.Assessment), BrowseRow("Homework", "homework", Icons.Outlined.Assignment), BrowseRow("Courses", "courses", Icons.Outlined.School),
        BrowseRow("Sessions", "sessions", Icons.AutoMirrored.Outlined.List), BrowseRow("Calendar", "calendar", Icons.Outlined.CalendarMonth),
        BrowseRow("Breaks", "breaks", Icons.Outlined.BeachAccess),
    )
    val more = listOf(
        BrowseRow("Focus", "focus", Icons.Outlined.Timer), BrowseRow("Habits", "habits", Icons.Outlined.CheckCircle),
        BrowseRow("Countdowns", "countdowns", Icons.Outlined.HourglassBottom), BrowseRow("Completed", "completed", Icons.Outlined.TaskAlt),
        BrowseRow("Settings", "settings", Icons.Outlined.Settings),
    )
    @Composable fun row(r: BrowseRow) {
        Row(Modifier.fillMaxWidth().clickable { onNavigate(r.route) }.padding(start = (16 + r.depth * 20).dp, end = 16.dp).heightIn(min = 48.dp).testTag("browse-${r.route}"), verticalAlignment = Alignment.CenterVertically) {
            Icon(r.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(r.label, Modifier.weight(1f))
            r.count?.takeIf { it > 0 }?.let { Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
    @Composable fun heading(text: String, trailing: (@Composable () -> Unit)? = null) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); trailing?.invoke()
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("browse-list")) {
        if (favourites.isNotEmpty()) { item("fav-h") { heading("Favourites") }; items(favourites, key = { "fav:" + it.route }) { row(it) } }
        item("projects-h") { heading("Projects") { IconButton(onNewProject, Modifier.testTag("browse-new-project")) { Icon(Icons.Outlined.Add, "New project") } } }
        if (projects.isEmpty()) item("projects-empty") { Text("Projects keep related tasks together.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(projects, key = { "p:" + it.first.id }) { (p, depth) -> row(BrowseRow(p.name, "project/${p.id}", Icons.Outlined.Folder, depth, count(p.id))) }
        item("filters-h") { heading("Filters & Labels") }
        item("filters-row") { row(BrowseRow("Filters & Labels", "filters", Icons.Outlined.FilterList)) }
        items(data?.filters.orEmpty(), key = { "f:" + it.id }) { row(BrowseRow(it.name, "filter/${it.id}", Icons.Outlined.FilterList, 1)) }
        items(data?.labels.orEmpty(), key = { "l:" + it.id }) { row(BrowseRow(it.name, "label/${it.id}", Icons.Outlined.Label, 1)) }
        item("school-h") { heading("School") }
        items(school, key = { "s:" + it.route }) { row(it) }
        item("more-h") { heading("More") }
        items(more, key = { "m:" + it.route }) { row(it) }
        item("end") { Spacer(Modifier.height(88.dp)) }   // a snackbar sits over the last row otherwise
    }
}
