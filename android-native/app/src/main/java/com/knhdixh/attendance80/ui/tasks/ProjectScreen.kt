package com.knhdixh.attendance80.ui.tasks

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.knhdixh.attendance80.net.HomeworkDto
import com.knhdixh.attendance80.net.ProjectDto
import com.knhdixh.attendance80.net.SectionDto
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * Asks for a name. Drawn over the screen rather than in a dialog window: simpler to test and no extra window to manage.
 */
@Composable
fun NameDialog(title: String, initial: String = "", confirm: String = "Save", onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .4f)).pointerInput(Unit) { detectTapGestures { onDismiss() } }.testTag("name-dialog"), contentAlignment = Alignment.Center) {
        Surface(Modifier.padding(24.dp).pointerInput(Unit) { detectTapGestures { } }, shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(name, { name = it }, Modifier.padding(vertical = 16.dp).fillMaxWidth().testTag("name-dialog-input"), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Cancel") }
                    TextButton({ if (name.isNotBlank()) { onSave(name.trim()); onDismiss() } }, Modifier.testTag("name-dialog-save")) { Text(confirm) }
                }
            }
        }
    }
}

private val sortNames = listOf("manual" to "Manual", "date" to "Date", "priority" to "Priority")
private val groupNames = listOf("none" to "No grouping", "date" to "Date", "priority" to "Priority", "label" to "Label")

/** A project: list or board, sections, completed tasks, sorting and grouping, archive. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(project: ProjectDto, env: TaskEnv, actions: TaskActions, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable(project.id) { mutableStateOf("manual") }
    var group by rememberSaveable(project.id) { mutableStateOf("none") }
    var completed by rememberSaveable(project.id) { mutableStateOf(false) }
    var collapsed by rememberSaveable(project.id) { mutableStateOf(listOf<Long>()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var dialogSection by remember { mutableStateOf<SectionDto?>(null) }
    var menu by remember { mutableStateOf<String?>(null) }
    val d = env.data
    val sections = d.sections.filter { it.project_id == project.id }.sortedWith(compareBy({ it.position }, { it.id }))
    val manual = sort == "manual" && group == "none"
    val options = TaskModel.ProjectOptions(sort, group, completed)

    fun sectionMenu(s: SectionDto): @Composable () -> Unit = {
        val i = sections.indexOf(s)
        Box {
            IconButton({ menu = "section${s.id}" }, Modifier.testTag("section-menu-${s.id}")) { Icon(Icons.Outlined.MoreVert, "Section options") }
            DropdownMenu(menu == "section${s.id}", { menu = null }) {
                DropdownMenuItem({ Text(if (s.id in collapsed) "Expand" else "Collapse") }, { collapsed = if (s.id in collapsed) collapsed - s.id else collapsed + s.id; menu = null }, Modifier.testTag("section-collapse-${s.id}"))
                DropdownMenuItem({ Text("Rename") }, { dialogSection = s; dialog = "rename-section"; menu = null }, Modifier.testTag("section-rename-${s.id}"))
                DropdownMenuItem({ Text("Move up") }, { scope.launch { actions.reorderEntities("section", sections.map { it.id }.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }; menu = null }, enabled = i > 0, modifier = Modifier.testTag("section-up-${s.id}"))
                DropdownMenuItem({ Text("Move down") }, { scope.launch { actions.reorderEntities("section", sections.map { it.id }.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }; menu = null }, enabled = i < sections.size - 1, modifier = Modifier.testTag("section-down-${s.id}"))
                DropdownMenuItem({ Text("Delete section") }, { scope.launch { actions.deleteEntity("section", s.id) }; menu = null }, Modifier.testTag("section-delete-${s.id}"))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SingleChoiceSegmentedButtonRow {
                listOf("list" to "List", "board" to "Board").forEachIndexed { i, (v, label) ->
                    SegmentedButton(project.view == v, { scope.launch { actions.updateEntity("project", project.id, buildJsonObject { put("view", v) }) } }, SegmentedButtonDefaults.itemShape(i, 2), Modifier.testTag("project-view-$v")) { Text(label) }
                }
            }
            Box {
                TextButton({ menu = "sort" }, Modifier.testTag("project-sort")) { Text("Sort: ${sortNames.first { it.first == sort }.second} · ${groupNames.first { it.first == group }.second}") }
                DropdownMenu(menu == "sort", { menu = null }) {
                    sortNames.forEach { (k, n) -> DropdownMenuItem({ Text("Sort by $n") }, { sort = k; menu = null }, Modifier.testTag("project-sort-$k")) }
                    HorizontalDivider()
                    groupNames.forEach { (k, n) -> DropdownMenuItem({ Text("Group: $n") }, { group = k; menu = null }, Modifier.testTag("project-group-$k")) }
                }
            }
            FilterChip(completed, { completed = !completed }, { Text("Show completed") }, Modifier.testTag("project-completed"))
            TextButton({ dialog = "section" }, Modifier.testTag("project-add-section")) { Text("Add section") }
            Box {
                IconButton({ menu = "project" }, Modifier.testTag("project-menu")) { Icon(Icons.Outlined.MoreVert, "Project options") }
                DropdownMenu(menu == "project", { menu = null }) {
                    DropdownMenuItem({ Text("Rename") }, { dialog = "rename"; menu = null }, Modifier.testTag("project-rename"))
                    DropdownMenuItem({ Text(if (project.favorite) "Remove from favourites" else "Add to favourites") }, { scope.launch { actions.updateEntity("project", project.id, buildJsonObject { put("favorite", !project.favorite) }) }; menu = null }, Modifier.testTag("project-favourite"))
                    DropdownMenuItem({ Text("Archive") }, { scope.launch { actions.updateEntity("project", project.id, buildJsonObject { put("archived", true) }) }; menu = null; onBack() }, Modifier.testTag("project-archive"))
                    DropdownMenuItem({ Text("Delete project") }, { dialog = "delete"; menu = null }, Modifier.testTag("project-delete"))
                }
            }
        }
        if (project.view == "board") Board(project, sections, env, actions, completed)
        else {
            val entries = TaskModel.projectEntries(d, project.id, options, env.today, collapsed.toSet()).map { e ->
                if (e is HeaderEntry && e.id.startsWith("s") && e.id != "sections") e.id.drop(1).toLongOrNull()?.let { id -> sections.find { it.id == id } }?.let { e.copy(trailing = sectionMenu(it)) } ?: e else e
            }
            TaskLazyList(entries, env, emptyText = "No tasks in this project yet.", onMove = if (manual) { body -> scope.launch { actions.move(body) } } else null, tag = "project-list")
        }
    }
    when (dialog) {
        "section" -> NameDialog("Add section", confirm = "Add", onSave = { n -> scope.launch { actions.createEntity("section", buildJsonObject { put("project_id", project.id); put("name", n) }) } }, onDismiss = { dialog = null })
        "rename" -> NameDialog("Rename project", project.name, onSave = { n -> scope.launch { actions.updateEntity("project", project.id, buildJsonObject { put("name", n) }) } }, onDismiss = { dialog = null })
        "rename-section" -> dialogSection?.let { s -> NameDialog("Rename section", s.name, onSave = { n -> scope.launch { actions.updateEntity("section", s.id, buildJsonObject { put("name", n) }) } }, onDismiss = { dialog = null }) }
        "delete" -> AlertDialog({ dialog = null }, title = { Text("Delete “${project.name}”?") }, text = { Text("Its tasks, sections and subprojects are deleted too.") },
            confirmButton = { TextButton({ scope.launch { actions.deleteEntity("project", project.id) }; dialog = null; onBack() }, Modifier.testTag("project-delete-confirm")) { Text("Delete") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } })
    }
}

/** Sections as columns of cards. Long-press a card and drag it to another column, or up and down in its own. */
@Composable
private fun Board(project: ProjectDto, sections: List<SectionDto>, env: TaskEnv, actions: TaskActions, showCompleted: Boolean) {
    val scope = rememberCoroutineScope()
    val columns = listOf<Pair<Long?, String>>(null to "No section") + sections.map { it.id to it.name }
    val colBounds = remember { mutableStateMapOf<Long?, Rect>() }
    val cardBounds = remember { mutableStateMapOf<Long, Rect>() }
    val drag = remember { DragState() }
    Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(8.dp).testTag("board"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        columns.forEach { (sectionId, name) ->
            val rows = env.data.homework.filter { it.project_id == project.id && it.parent_id == null && it.section_id == sectionId && (!it.done || showCompleted) }.sortedWith(compareBy<HomeworkDto>({ it.done }, { it.position }, { it.id }))
            Column(Modifier.width(280.dp).fillMaxHeight().onGloballyPositioned { colBounds[sectionId] = Rect(it.positionInRoot(), it.size.toSize()) }.testTag("board-col-${sectionId ?: 0}")
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f), RoundedCornerShape(12.dp)).padding(8.dp).verticalScroll(rememberScrollState())) {
                Text("$name ${rows.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                rows.forEach { h ->
                    val dragging = drag.id == h.id
                    Surface(Modifier.padding(bottom = 8.dp).zIndex(if (dragging) 1f else 0f).graphicsLayer { translationX = if (dragging) drag.dx else 0f; translationY = if (dragging) drag.dy else 0f }
                        .onGloballyPositioned { cardBounds[h.id] = Rect(it.positionInRoot(), it.size.toSize()) }.testTag("board-card-${h.id}")
                        .pointerInput(h.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { drag.id = h.id; drag.dx = 0f; drag.dy = 0f },
                                onDrag = { c, a -> c.consume(); drag.dx += a.x; drag.dy += a.y },
                                onDragEnd = {
                                    val me = cardBounds[h.id]
                                    val to = me?.let { TaskModel.boardDrop(colBounds.map { (k, r) -> Triple(k, r.left, r.right) },
                                        rows0(env, project, cardBounds), h.id, it.center.x + drag.dx, it.center.y + drag.dy) }
                                    drag.id = null; drag.dx = 0f; drag.dy = 0f
                                    if (to != null) scope.launch { actions.move(TaskModel.boardMove(env.data, project.id, h.id, to.first, to.second)) }
                                },
                                onDragCancel = { drag.id = null; drag.dx = 0f; drag.dy = 0f })
                        }, shape = RoundedCornerShape(8.dp), tonalElevation = 2.dp, shadowElevation = if (dragging) 6.dp else 0.dp) {
                        TaskRow(h, env, swipe = false)
                    }
                }
            }
        }
    }
}

/** Every card on the board as (id, section, top, height), for working out where a drop lands. */
private fun rows0(env: TaskEnv, project: ProjectDto, bounds: Map<Long, Rect>): List<Triple<Long, Long?, Pair<Float, Float>>> =
    env.data.homework.filter { it.project_id == project.id && it.parent_id == null }.mapNotNull { h -> bounds[h.id]?.let { Triple(h.id, h.section_id, it.top to it.height) } }
