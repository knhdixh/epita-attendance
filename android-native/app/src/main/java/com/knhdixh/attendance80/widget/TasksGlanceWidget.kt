package com.knhdixh.attendance80.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.knhdixh.attendance80.App
import com.knhdixh.attendance80.MainActivity
import com.knhdixh.attendance80.ui.tasks.TaskModel

private fun open(context: Context, uri: String) = actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java))

val TickTask = ActionParameters.Key<Long>("tick_task")
val TickDue = ActionParameters.Key<String>("tick_due")

internal fun taskWidgetPrefs(context: Context) = TaskWidgetPrefs(java.io.File(context.applicationContext.filesDir, "snapshots/task_widgets.json"))

/**
 * A list of tasks for one project, filter, the Inbox or Today (chosen when the widget is placed). Reads the app's snapshot, so
 * it works offline; a tick goes through the outbox like every other change and the row disappears at once.
 */
class TasksGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as App
        app.startData()
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val source = taskWidgetPrefs(context).source(widgetId)
        provideContent {
            val data by app.container.dataRepository.snapshot.collectAsState()
            val signedIn by app.container.tokenStore.token.collectAsState()
            GlanceTheme { TasksWidgetContent(context, source, if (signedIn == null) null else data, signedIn != null, app.container.clock.now(), app.container.clock.zone()) }
        }
    }
}

@Composable
internal fun TasksWidgetContent(context: Context, source: TaskSource, data: com.knhdixh.attendance80.net.AppData?, signedIn: Boolean, now: Long, zone: java.time.ZoneId) {
    val list = taskWidgetList(data, source, now, zone)
    val root = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(8.dp)
    val title = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val muted = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
    Column(root) {
        Row(GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(list.title.ifEmpty { "Tasks" }, style = title, maxLines = 1,
                modifier = GlanceModifier.defaultWeight().clickable(open(context, if (signedIn) source.route else "a80://today")))
            if (data != null && !list.missing) Text("${list.total}", style = muted, modifier = GlanceModifier.padding(end = 8.dp))
            Box(GlanceModifier.size(32.dp).clickable(open(context, "a80://add")), contentAlignment = Alignment.Center) {
                Text("+", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }
        }
        when {
            !signedIn -> Text("Open the app to sign in.", style = muted, modifier = GlanceModifier.padding(8.dp))
            data == null -> Text("Loading…", style = muted, modifier = GlanceModifier.padding(8.dp))
            list.missing -> Text("This list was deleted. Remove the widget and add it again.", style = muted, modifier = GlanceModifier.padding(8.dp))
            list.rows.isEmpty() -> Text("Nothing to do here.", style = muted, modifier = GlanceModifier.padding(8.dp))
            else -> LazyColumn(GlanceModifier.fillMaxSize()) {
                items(list.rows, itemId = { it.id }) { r -> TaskLine(context, r) }
            }
        }
    }
}

@Composable
private fun TaskLine(context: Context, r: WidgetTaskRow) {
    val ring = TaskModel.priorityColor(r.priority)
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        // The box ticks the task off without opening the app; the text opens it.
        CheckBox(checked = false, onCheckedChange = actionRunCallback<TickTaskCallback>(actionParametersOf(TickTask to r.id, TickDue to r.dueDate.orEmpty())),
            colors = CheckboxDefaults.colors(checkedColor = ColorProvider(ring, ring), uncheckedColor = ColorProvider(ring, ring)),
            modifier = GlanceModifier.size(36.dp))
        Column(GlanceModifier.defaultWeight().clickable(open(context, "a80://homework/${r.id}"))) {
            Text(r.title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
            if (r.meta.isNotEmpty()) Text(r.meta, maxLines = 1, style = TextStyle(
                color = if (r.overdue) ColorProvider(Color(0xFFD1453B), Color(0xFFFF8A80)) else GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
        }
    }
}

/** Ticks a task from the widget: the same path as the notification's Done button (outbox, then WorkManager sends it). */
class TickTaskCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.applicationContext as App
        app.startData()
        val id = parameters[TickTask] ?: return
        val owner = app.container.tokenStore.awaitToken()?.let { app.container.dataRepository.snapshot.value?.user?.email?.lowercase() } ?: return
        app.container.reminderActions?.complete(id, parameters[TickDue], owner)
        redrawTaskWidgets(context)
    }
}

/** Every placed task list redraws from the snapshot (a live session also follows it by itself). */
suspend fun redrawTaskWidgets(context: Context) { runCatching { TasksGlanceWidget().updateAll(context.applicationContext) } }

class TasksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TasksGlanceWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        taskWidgetPrefs(context).remove(appWidgetIds)
    }
}

/** One row: tap it to add a task with quick add. */
class QuickAddGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent {
        GlanceTheme {
            Row(GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).cornerRadius(28.dp).padding(horizontal = 16.dp)
                .clickable(open(context, "a80://add")), verticalAlignment = Alignment.CenterVertically) {
                Text("+", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 24.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.padding(end = 12.dp))
                Text("Add a task", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 15.sp), maxLines = 1)
            }
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddGlanceWidget()
}
