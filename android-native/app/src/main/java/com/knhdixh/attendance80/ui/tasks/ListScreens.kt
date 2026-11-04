package com.knhdixh.attendance80.ui.tasks

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.logic.Filters
import com.knhdixh.attendance80.net.HomeworkDto
import kotlinx.serialization.json.JsonObject

/** Inbox: tasks without a project, in the order the user set (drag to change it). */
@Composable
fun InboxScreen(env: TaskEnv, onMove: (JsonObject) -> Unit) {
    val entries = TaskModel.flatten(env.data, TaskModel.inbox(env.data))
    TaskLazyList(entries, env, emptyText = "Inbox zero. Tasks you add without a project land here.", onMove = onMove, tag = "inbox-list")
}

/** A saved filter: what `filters.js` finds for its query, one group per comma-separated list. */
@Composable
fun FilterScreen(query: String, env: TaskEnv) {
    val run = Filters.runFilter(query, TaskModel.filterContext(env.data, env.today))
    if (!run.ok) { Text(run.error ?: "That filter doesn't parse.", Modifier.padding(16.dp).testTag("filter-error"), color = MaterialTheme.colorScheme.error); return }
    val byId = env.data.homework.associateBy { it.id }
    val groups = run.lists.map { list -> list.mapNotNull { byId[it.id] } }
    val entries: List<ListEntry> = if (groups.size <= 1) groups.flatten().map { TaskEntry(it) }
    else groups.flatMapIndexed { i, rows -> listOf<ListEntry>(HeaderEntry("List ${i + 1}", "g$i", rows.size)) + rows.map { TaskEntry(it) } }
    TaskLazyList(entries, env, showProject = true, emptyText = "Nothing matches this filter.", tag = "filter-list")
}

@Composable
fun LabelScreen(labelId: Long, env: TaskEnv) {
    val rows = env.data.homework.filter { !it.done && labelId in it.labels }.sortedWith(TaskModel.manual)
    TaskLazyList(rows.map { TaskEntry(it) }, env, showProject = true, emptyText = "No open tasks have this label.", tag = "label-list")
}
