package com.knhdixh.attendance80.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.knhdixh.attendance80.App
import com.knhdixh.attendance80.MainActivity
import com.knhdixh.attendance80.R
import com.knhdixh.attendance80.focus.FocusReceiver
import com.knhdixh.attendance80.focus.clock
import com.knhdixh.attendance80.focus.phaseName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Date

private val SMALL = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(250.dp, 110.dp)

/** What the widget says, as plain strings, so a test can check it without drawing anything. */
data class WidgetText(
    val classHead: String, val className: String, val classTime: String, val classWhere: String,
    val todo: List<String>, val timerHead: String, val timerLine: String, val button: String,
)

fun widgetText(s: WidgetSnapshot, now: Long, zone: ZoneId, time: (Long) -> String, day: (Long) -> String, today: (Long) -> Boolean): WidgetText {
    val c = s.currentClass(now)
    val inIt = c != null && c.start <= now
    val head = when {
        c == null -> "Next class"
        inIt -> "Now"
        today(c.start) -> "Next class"
        else -> "Next class · ${day(c.start)}"
    }
    val name = c?.title ?: if (!s.signedIn) "Open the app to sign in" else "No more classes this week"
    val ctime = if (c == null) "" else if (inIt) "until ${time(c.end)}" else time(c.start)
    val relative = if (c != null && !inIt) relativeStart(c.start, now) else ""
    val where = listOf(c?.where.orEmpty(), relative).filter { it.isNotBlank() }.joinToString(" · ")
    val todo = if (!s.signedIn) emptyList() else if (s.todo.isEmpty()) listOf("Nothing due in the next 7 days.") else s.todo.map { t ->
        "• " + t.title + if (t.dueAt > 0) "  ·  " + (if (t.dueAt < now) "overdue" else day(t.dueAt)) else ""
    }
    val t = s.timer
    val (timerHead, line, button) = when {
        t.running && t.paused -> Triple(phaseName(t.phase), "Paused · ${clock(t.leftMs)} left", "Resume")
        t.running -> Triple(phaseName(t.phase), t.label.takeIf { t.phase == "focus" }.orEmpty(), "Pause")
        else -> Triple(if (t.next == "focus") "Focus" else phaseName(t.next), "${t.nextMinutes} min" + (t.label?.let { " · $it" } ?: ""), "Start")
    }
    return WidgetText(head, name, ctime, where, todo, timerHead, line, button)
}

private fun relativeStart(start: Long, now: Long): String {
    val min = (start - now + 59_999) / 60_000
    return when {
        min < 60 -> "in $min min"
        min < 6 * 60 -> "in ${min / 60} h" + if (min % 60 > 0) " ${min % 60} min" else ""
        else -> ""
    }
}

/** The home-screen widget. It reads [WidgetSnapshot] from disk and makes no network calls. */
class FocusGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStores.get(context).read()
        val now = System.currentTimeMillis()
        nextWidgetRefresh(snapshot, now, ZoneId.systemDefault())?.let { scheduleWidgetRefresh(context, it) }
        provideContent { GlanceTheme { WidgetContent(snapshot, now) } }
    }
}

class FocusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FocusGlanceWidget()
}

/** Start, pause or resume, without opening the app (the same toggle as the tile and the notification). */
class ToggleTimerCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        (context.applicationContext as App).container.focusController?.toggle()
    }
}

private fun open(context: Context, uri: String) =
    actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java))

@Composable
internal fun WidgetContent(s: WidgetSnapshot, now: Long) {
    val context = LocalContext.current
    val wide = LocalSize.current.width >= WIDE.width
    val zone = ZoneId.systemDefault()
    val text = widgetText(s, now, zone,
        time = { DateFormat.getTimeFormat(context).format(Date(it)) },
        day = { dayLabel(it, now) },
        today = { DateUtils.isToday(it) })
    val current = s.currentClass(now)
    val primary = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val secondary = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)

    val classBlock: @Composable () -> Unit = {
        Column(GlanceModifier.fillMaxWidth().clickable(open(context, if (current != null) "a80://session/${current.id}" else "a80://today"))) {
            Text(text.classHead, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(text.className, style = primary, maxLines = 1)
            if (text.classTime.isNotEmpty()) Text(text.classTime, style = secondary, maxLines = 1)
            if (text.classWhere.isNotEmpty()) Text(text.classWhere, style = secondary, maxLines = 1)
        }
    }
    val timerBlock: @Composable () -> Unit = {
        Column(GlanceModifier.fillMaxWidth()) {
            Column(GlanceModifier.fillMaxWidth().clickable(open(context, "a80://focus"))) {
                Text(text.timerHead, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                if (s.timer.running && !s.timer.paused) {
                    val onSurface = GlanceTheme.colors.onSurface.getColor(context).toArgb()
                    AndroidRemoteViews(RemoteViews(context.packageName, R.layout.widget_chronometer).apply {
                        setChronometer(R.id.widget_clock, SystemClock.elapsedRealtime() + (s.timer.endsAt - now).coerceAtLeast(0), null, true)
                        setChronometerCountDown(R.id.widget_clock, true)
                        setTextColor(R.id.widget_clock, onSurface)
                    })
                }
                if (text.timerLine.isNotEmpty()) Text(text.timerLine, style = secondary, maxLines = 1)
            }
            Spacer(GlanceModifier.height(4.dp))
            Button(text.button, onClick = actionRunCallback<ToggleTimerCallback>(), modifier = GlanceModifier.fillMaxWidth())
        }
    }

    val root = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(12.dp)
    if (!wide) {
        Column(root) {
            classBlock()
            Spacer(GlanceModifier.height(6.dp))
            timerBlock()
        }
    } else {
        Row(root, verticalAlignment = Alignment.Top) {
            Column(GlanceModifier.defaultWeight()) {
                classBlock()
                Spacer(GlanceModifier.height(6.dp))
                text.todo.forEachIndexed { i, line ->
                    val item = s.todo.getOrNull(i)
                    val m = GlanceModifier.fillMaxWidth().let { if (item != null) it.clickable(open(context, "a80://homework/${item.id}")) else it }
                    Text(line, style = secondary, maxLines = 1, modifier = m)
                }
            }
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.width(104.dp)) { timerBlock() }
        }
    }
}

private fun dayLabel(ms: Long, now: Long): String = when {
    DateUtils.isToday(ms) -> "today"
    DateUtils.isToday(ms - DateUtils.DAY_IN_MILLIS) && DateUtils.isToday(now) -> "tomorrow"
    else -> DateFormat.format("EEE d MMM", ms).toString()
}

/** Redraws every placed widget from the stored snapshot. Safe to call from anywhere; it does nothing if none is placed. */
fun redrawWidget(context: Context) {
    val app = context.applicationContext
    CoroutineScope(Dispatchers.Default).launch { runCatching { FocusGlanceWidget().updateAll(app) } }
}

/** One inexact alarm for the next time the widget changes by itself (a class starts or ends, midnight, a due time). */
fun scheduleWidgetRefresh(context: Context, at: Long) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, FocusReceiver::class.java).setAction(FocusReceiver.ACTION_WIDGET_REFRESH)
    val pi = PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    am.set(AlarmManager.RTC, at, pi)
}
