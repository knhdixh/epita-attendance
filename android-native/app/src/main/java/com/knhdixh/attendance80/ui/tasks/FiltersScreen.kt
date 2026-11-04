package com.knhdixh.attendance80.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.logic.Filters
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.FilterDto
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a filter query finds right now, or why it doesn't parse. Pure, for the editor's live preview. */
data class FilterPreview(val ok: Boolean, val titles: List<String>, val count: Int, val error: String?)

fun previewFilter(query: String, d: AppData, today: String): FilterPreview {
    if (query.isBlank()) return FilterPreview(true, emptyList(), 0, null)
    val run = Filters.runFilter(query, TaskModel.filterContext(d, today))
    if (!run.ok) return FilterPreview(false, emptyList(), 0, run.error)
    val byId = d.homework.associateBy { it.id }
    val rows = run.lists.flatten().distinctBy { it.id }.mapNotNull { byId[it.id] }
    return FilterPreview(true, rows.take(5).map { it.title }, rows.size, null)
}

/** Create or edit a filter. The preview under the query updates as you type; a query that doesn't parse says where it went wrong. */
@Composable
fun FilterEditor(initial: FilterDto?, env: TaskEnv, onSave: (name: String, query: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var query by remember { mutableStateOf(initial?.query.orEmpty()) }
    val preview = remember(query, env.data, env.today) { previewFilter(query, env.data, env.today) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)).testTag("filter-editor"), contentAlignment = Alignment.Center) {
        Surface(Modifier.padding(16.dp), shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp)) {
                Text(if (initial == null) "New filter" else "Edit filter", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("filter-name"), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("filter-query"), label = { Text("Query, like today & p1") }, singleLine = true, isError = !preview.ok)
                Column(Modifier.padding(vertical = 8.dp).testTag("filter-preview")) {
                    if (!preview.ok) Text(preview.error ?: "That doesn't parse.", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("filter-preview-error"))
                    else if (query.isNotBlank()) {
                        Text("${preview.count} matching task${if (preview.count == 1) "" else "s"}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("filter-preview-count"))
                        preview.titles.forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        if (preview.count > preview.titles.size) Text("and ${preview.count - preview.titles.size} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Cancel") }
                    TextButton({ onSave(name.trim(), query.trim()); onDismiss() }, enabled = name.isNotBlank() && query.isNotBlank() && preview.ok, modifier = Modifier.testTag("filter-save")) { Text("Save") }
                }
            }
        }
    }
}

/** Browse › Filters & Labels: make, change and delete them, and star the ones that should be in Browse's favourites. */
@Composable
fun FiltersScreen(env: TaskEnv, actions: TaskActions, onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<FilterDto?>(null) }
    var newFilter by remember { mutableStateOf(false) }
    var labelDialog by remember { mutableStateOf<Long?>(null) }     // 0 = new, else the label being renamed
    var menu by remember { mutableStateOf<String?>(null) }
    val d = env.data
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("filters-list")) {
            item("fh") { Row(Modifier.padding(start = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({ newFilter = true }, Modifier.testTag("filters-add")) { Icon(Icons.Outlined.Add, "New filter") }
            } }
            if (d.filters.isEmpty()) item("fe") { Text("A filter is a saved search, like today & p1.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(d.filters, key = { "f${it.id}" }) { f ->
                Row(Modifier.fillMaxWidth().clickable { onNavigate("filter/${f.id}") }.padding(start = 16.dp).heightIn(min = 56.dp).testTag("filter-row-${f.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FilterList, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) { Text(f.name); Text(f.query, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Box {
                        IconButton({ menu = "f${f.id}" }, Modifier.testTag("filter-menu-${f.id}")) { Icon(Icons.Outlined.MoreVert, "Filter options") }
                        DropdownMenu(menu == "f${f.id}", { menu = null }) {
                            DropdownMenuItem({ Text("Edit") }, { editing = f; menu = null }, Modifier.testTag("filter-edit-${f.id}"))
                            DropdownMenuItem({ Text(if (f.favorite) "Remove from favourites" else "Add to favourites") }, { scope.launch { actions.updateEntity("filter", f.id, buildJsonObject { put("favorite", !f.favorite) }) }; menu = null }, Modifier.testTag("filter-favourite-${f.id}"))
                            DropdownMenuItem({ Text("Delete") }, { scope.launch { actions.deleteEntity("filter", f.id) }; menu = null }, Modifier.testTag("filter-delete-${f.id}"))
                        }
                    }
                }
            }
            item("lh") { Row(Modifier.padding(start = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Labels", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton({ labelDialog = 0L }, Modifier.testTag("labels-add")) { Icon(Icons.Outlined.Add, "New label") }
            } }
            if (d.labels.isEmpty()) item("le") { Text("Labels tag tasks across projects. Type @name when you add one.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(d.labels, key = { "l${it.id}" }) { l ->
                Row(Modifier.fillMaxWidth().clickable { onNavigate("label/${l.id}") }.padding(start = 16.dp).heightIn(min = 48.dp).testTag("label-row-${l.id}"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Label, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(l.name, Modifier.weight(1f).padding(start = 16.dp))
                    Text("${d.homework.count { !it.done && l.id in it.labels }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        IconButton({ menu = "l${l.id}" }, Modifier.testTag("label-menu-${l.id}")) { Icon(Icons.Outlined.MoreVert, "Label options") }
                        DropdownMenu(menu == "l${l.id}", { menu = null }) {
                            DropdownMenuItem({ Text("Rename") }, { labelDialog = l.id; menu = null }, Modifier.testTag("label-rename-${l.id}"))
                            DropdownMenuItem({ Text(if (l.favorite) "Remove from favourites" else "Add to favourites") }, { scope.launch { actions.updateEntity("label", l.id, buildJsonObject { put("favorite", !l.favorite) }) }; menu = null }, Modifier.testTag("label-favourite-${l.id}"))
                            DropdownMenuItem({ Text("Delete") }, { scope.launch { actions.deleteEntity("label", l.id) }; menu = null }, Modifier.testTag("label-delete-${l.id}"))
                        }
                    }
                }
            }
        }
        if (newFilter || editing != null) FilterEditor(editing, env, onSave = { n, q ->
            val e = editing
            scope.launch { if (e == null) actions.createEntity("filter", buildJsonObject { put("name", n); put("query", q) }) else actions.updateEntity("filter", e.id, buildJsonObject { put("name", n); put("query", q) }) }
        }, onDismiss = { newFilter = false; editing = null })
        labelDialog?.let { id ->
            val label = d.labels.find { it.id == id }
            NameDialog(if (label == null) "New label" else "Rename label", label?.name.orEmpty(), if (label == null) "Add" else "Save", onSave = { n ->
                scope.launch { if (label == null) actions.createEntity("label", buildJsonObject { put("name", n) }) else actions.updateEntity("label", id, buildJsonObject { put("name", n) }) }
            }, onDismiss = { labelDialog = null })
        }
    }
}
