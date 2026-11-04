package com.knhdixh.attendance80.ui.tasks

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.knhdixh.attendance80.net.HomeworkDto
import kotlinx.serialization.json.JsonObject

/** The row being dragged and how far it has moved. */
class DragState {
    var id by mutableStateOf<Long?>(null)
    var dx by mutableFloatStateOf(0f)
    var dy by mutableFloatStateOf(0f)
}

/**
 * Where a drag ended: the row under the dragged row's centre and which side of it, or a nest / unnest when it moved
 * sideways more than [nestPx]. Pure, so it can be tested without a screen.
 */
fun dropTarget(tops: List<Triple<Long, Float, Float>>, id: Long, dx: Float, dy: Float, nestPx: Float): Triple<Long?, Boolean, Int> {
    if (dx > nestPx && Math.abs(dy) < Math.abs(dx)) return Triple(null, false, 1)
    if (dx < -nestPx && Math.abs(dy) < Math.abs(dx)) return Triple(null, false, -1)
    val me = tops.find { it.first == id } ?: return Triple(null, false, 0)
    val centre = me.second + me.third / 2 + dy
    val hit = tops.find { it.first != id && centre >= it.second && centre < it.second + it.third }
        ?: (if (dy < 0) tops.firstOrNull { it.first != id } else tops.lastOrNull { it.first != id })
    return Triple(hit?.first, dy > 0, 0)
}

/** Bottom padding every screen with the add button needs: the button (56 dp) plus its 16 dp margin plus 16 dp of air. */
val FabInset = 88.dp

/** Task rows with section headings; long-press a row and drag to reorder it, or drag sideways to nest or unnest it. */
@Composable
fun TaskLazyList(
    entries: List<ListEntry>, env: TaskEnv, showProject: Boolean = false, emptyText: String? = null,
    onMove: ((JsonObject) -> Unit)? = null, modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(),
    header: (LazyListScope.() -> Unit)? = null, footer: (LazyListScope.() -> Unit)? = null, tag: String = "task-list",
) {
    val drag = remember { DragState() }
    val rows = remember(entries) { entries.filterIsInstance<TaskEntry>().map { it.h } }
    val density = LocalDensity.current
    LazyColumn(modifier.fillMaxSize().testTag(tag), state, contentPadding = PaddingValues(bottom = FabInset)) {
        header?.invoke(this)
        if (entries.isEmpty() && emptyText != null) item("empty") { Text(emptyText, Modifier.padding(16.dp).testTag("$tag-empty"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        entries.forEach { entry ->
            when (entry) {
                is HeaderEntry -> item(entry.key) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.title + (entry.count?.let { " $it" } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        entry.trailing?.invoke()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                is TaskEntry -> item(entry.key) {
                    val dragging = drag.id == entry.h.id
                    var rowModifier: Modifier = Modifier
                    if (onMove != null) rowModifier = Modifier.pointerInput(entry.h.id, rows) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { drag.id = entry.h.id; drag.dx = 0f; drag.dy = 0f },
                            onDrag = { change, amount -> change.consume(); drag.dx += amount.x; drag.dy += amount.y },
                            onDragEnd = {
                                val tops = state.layoutInfo.visibleItemsInfo.mapNotNull { info -> (info.key as? String)?.takeIf { it.startsWith("t") }?.drop(1)?.toLongOrNull()?.let { Triple(it, info.offset.toFloat(), info.size.toFloat()) } }
                                val (target, below, nest) = dropTarget(tops, entry.h.id, drag.dx, drag.dy, with(density) { 48.dp.toPx() })
                                drag.id = null; drag.dx = 0f; drag.dy = 0f
                                TaskModel.planMove(env.data, rows, entry.h.id, target, below, nest)?.let(onMove)
                            },
                            onDragCancel = { drag.id = null; drag.dx = 0f; drag.dy = 0f },
                        )
                    }
                    Box(rowModifier.zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = if (dragging) drag.dy else 0f; translationX = if (dragging) drag.dx else 0f }
                        .then(if (dragging) Modifier.shadow(4.dp) else Modifier)) {
                        Column {
                            TaskRow(entry.h, env, showProject, entry.depth, swipe = !dragging)
                            HorizontalDivider(Modifier.padding(start = (48 + entry.depth * 24).dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
        footer?.invoke(this)
    }
}
