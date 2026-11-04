package com.knhdixh.attendance80.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.logic.Dates
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.CommentDto
import java.time.ZoneId

/** One result. [route] is where it goes; a task or a comment opens the task's sheet ([taskId]). */
data class SearchHit(val kind: String, val key: String, val title: String, val detail: String = "", val route: String? = null, val taskId: Long? = null)

object SearchModel {
    val kinds = listOf("task" to "Tasks", "comment" to "Comments", "project" to "Projects", "label" to "Labels", "course" to "Courses", "session" to "Sessions")

    private fun has(text: String?, q: String) = text != null && text.contains(q, ignoreCase = true)

    /** Every match for [q] across tasks, comments, projects, labels, courses and sessions. [comments] holds the ones that have been fetched. */
    fun run(d: AppData, q0: String, comments: Map<Long, List<CommentDto>>, zone: ZoneId, limit: Int = 20): List<SearchHit> {
        val q = q0.trim()
        if (q.isEmpty()) return emptyList()
        val projectName = { id: Long? -> id?.let { p -> d.projects.find { it.id == p }?.name } ?: "Inbox" }
        val out = mutableListOf<SearchHit>()
        d.homework.filter { TaskModel.matchesTask(it, q) }.sortedWith(compareBy({ it.done }, { it.due_date ?: "9999" })).take(limit)
            .forEach { out += SearchHit("task", "t${it.id}", it.title, projectName(it.project_id) + if (it.done) " · done" else "", taskId = it.id) }
        comments.forEach { (taskId, list) ->
            val task = d.homework.find { it.id == taskId } ?: return@forEach
            list.filter { has(it.body, q) }.forEach { out += SearchHit("comment", "c${it.id}:${taskId}", it.body.lineSequence().first().take(120), "On ${task.title}", taskId = taskId) }
        }
        d.projects.filter { !it.archived && has(it.name, q) }.take(limit).forEach { out += SearchHit("project", "p${it.id}", it.name, route = "project/${it.id}") }
        d.labels.filter { has(it.name, q) }.take(limit).forEach { out += SearchHit("label", "l${it.id}", "@${it.name}", route = "label/${it.id}") }
        d.courses.filter { has(it.name, q) }.take(limit).forEach { out += SearchHit("course", "k${it.id}", it.name, route = "course/${it.id}") }
        d.events.filter { has(it.summary, q) || has(it.location, q) }.sortedBy { it.start_at }.take(limit)
            .forEach { out += SearchHit("session", "s${it.id}", it.summary, Dates.formatDate(it.start_at, zone) + " " + Dates.formatTime(it.start_at, zone), route = "session/${it.id}") }
        return out
    }
}

@Composable
fun SearchScreen(env: TaskEnv, actions: TaskActions, recents: List<String>, onSearched: (String) -> Unit, onNavigate: (String) -> Unit, autoFocus: Boolean = true) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) repeat(20) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(50) } }
    // Comments aren't in the snapshot: fetch them for the tasks that have some, once, so they can be searched too.
    val fetched = remember { mutableStateMapOf<Long, List<CommentDto>>() }
    val withComments = env.data.homework.filter { it.comment_count > 0 && it.id > 0 }.map { it.id }
    LaunchedEffect(withComments) { withComments.take(60).filter { it !in fetched }.forEach { id -> fetched[id] = actions.comments(id) } }
    val hits = remember(query, env.data, fetched.toMap()) { SearchModel.run(env.data, query, fetched.toMap(), env.zone) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus).testTag("search-tab-input"), singleLine = true,
            placeholder = { Text("Tasks, comments, projects, labels, courses, sessions") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }, Modifier.testTag("search-tab-clear")) { Icon(Icons.Outlined.Close, "Clear search") } })
        LazyColumn(Modifier.fillMaxSize().testTag("search-tab-results")) {
            if (query.isBlank()) {
                if (recents.isNotEmpty()) {
                    item("rh") { Text("Recent searches", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(recents, key = { "r:$it" }) { r -> Text(r, Modifier.fillMaxWidth().clickable { query = r }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("search-recent")) }
                } else item("hint") { Text("Search everything: tasks, their comments, projects, labels, courses and sessions.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (hits.isEmpty()) item("none") { Text("Nothing found for “${query.trim()}”.", Modifier.padding(16.dp).testTag("search-tab-none"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else SearchModel.kinds.forEach { (kind, title) ->
                val rows = hits.filter { it.kind == kind }
                if (rows.isNotEmpty()) {
                    item("h$kind") { Text(title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(rows, key = { it.key }) { hit ->
                        Column(Modifier.fillMaxWidth().clickable {
                            onSearched(query)
                            if (hit.taskId != null) env.onOpen(hit.taskId) else hit.route?.let(onNavigate)
                        }.padding(horizontal = 16.dp, vertical = 8.dp).testTag("search-hit-${hit.key}")) {
                            Text(hit.title)
                            if (hit.detail.isNotBlank()) Text(hit.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
