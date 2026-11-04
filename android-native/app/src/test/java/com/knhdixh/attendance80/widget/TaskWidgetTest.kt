package com.knhdixh.attendance80.widget

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.net.FilterDto
import com.knhdixh.attendance80.net.ProjectDto
import com.knhdixh.attendance80.net.SectionDto
import com.knhdixh.attendance80.ui.tasks.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/** Phase 13, task 2: the task list widget (what it lists, what each placed widget remembers) and the quick-add widget's registration. */
@OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class TaskWidgetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val data = taskData(
        task(1, "Inbox one", position = 1), task(2, "Inbox two", position = 0, due = "2026-11-03", priority = 4),
        task(3, "Home chore", project = 10, section = 21, position = 0, due = TODAY), task(4, "Sub project job", project = 11, position = 0),
        task(5, "Other project", project = 12), task(6, "Section first", project = 10, section = 20, position = 3), task(7, "Done", project = 10, done = true),
        task(8, "A subtask", project = 10, parent = 3), task(9, "Daily", recurrence = "every day", due = TODAY), task(10, "Later", due = "2026-11-20"),
        projects = listOf(ProjectDto(10, name = "Home"), ProjectDto(11, parent_id = 10, name = "Garden"), ProjectDto(12, name = "Work"), ProjectDto(13, name = "Old", archived = true)),
        sections = listOf(SectionDto(20, 10, "First", 0), SectionDto(21, 10, "Second", 1)),
        filters = listOf(FilterDto(30, "Urgent", "p1", position = 1), FilterDto(31, "Broken", "((", position = 0)),
    )
    private fun list(source: TaskSource, d: com.knhdixh.attendance80.net.AppData? = data) = taskWidgetList(d, source, taskNow, taskZone)

    @Test fun todayIsOverdueThenToday() {
        val l = list(TaskSource.TODAY)
        assertEquals("Today", l.title); assertEquals(listOf(2L, 3L, 9L), l.rows.map { it.id })
        assertTrue(l.rows[0].overdue); assertEquals("Yesterday", l.rows[0].meta); assertEquals("Today · repeats", l.rows[2].meta)
        assertEquals("the date goes back with a tick", "2026-11-04", l.rows[1].dueDate)
    }

    @Test fun theInboxHasTopLevelOpenTasksWithoutAProjectInManualOrder() {
        val l = list(TaskSource.INBOX)
        assertEquals("Inbox", l.title)
        assertEquals(setOf(1L, 2L, 9L, 10L), l.rows.map { it.id }.toSet()) // not the project tasks, the done one or the subtask
        assertEquals("manual order: position 0 before position 1", listOf(2L, 1L), l.rows.map { it.id }.filter { it in setOf(1L, 2L) })
    }

    @Test fun aProjectIncludesSubprojectsAndKeepsSectionOrderWithoutDoneOrSubtasks() {
        val l = list(TaskSource.project(10))
        assertEquals("Home", l.title)
        assertEquals("no section, then First, then Second; no done task, no subtask", listOf(4L, 6L, 3L), l.rows.map { it.id })
        assertEquals(listOf(5L), list(TaskSource.project(12)).rows.map { it.id })
    }

    @Test fun aFilterRunsItsQueryAndABrokenOrDeletedOneShowsNothing() {
        assertEquals(listOf(2L), list(TaskSource.filter(30)).rows.map { it.id }); assertEquals("Urgent", list(TaskSource.filter(30)).title)
        assertTrue(list(TaskSource.filter(31)).rows.isEmpty()); assertFalse(list(TaskSource.filter(31)).missing)
        assertTrue(list(TaskSource.filter(99)).missing); assertTrue(list(TaskSource.project(99)).missing)
    }

    @Test fun theListIsCutAtTheLimitButCountsEverything() {
        val many = taskData(*(1L..60L).map { task(it, due = TODAY) }.toTypedArray())
        val l = taskWidgetList(many, TaskSource.TODAY, taskNow, taskZone)
        assertEquals(WIDGET_TASK_LIMIT, l.rows.size); assertEquals(60, l.total)
        assertTrue(taskWidgetList(null, TaskSource.TODAY, taskNow, taskZone).rows.isEmpty())
    }

    @Test fun theConfigurationOffersTodayInboxNestedProjectsAndFilters() {
        assertEquals(listOf("Today", "Inbox", "Home", "  Garden", "Work", "Broken", "Urgent"), taskSourceChoices(data).map { it.first })
        assertEquals(TaskSource.project(11), taskSourceChoices(data).first { it.first.trim() == "Garden" }.second)
    }

    @Test fun eachPlacedWidgetRemembersItsListAndForgetsItWhenRemoved() {
        val file = Files.createTempDirectory("tw").toFile().resolve("w.json")
        val p = TaskWidgetPrefs(file)
        assertEquals(TaskSource.TODAY, p.source(5))
        p.set(5, TaskSource.project(10)); p.set(6, TaskSource.filter(30))
        assertEquals(TaskSource.project(10), TaskWidgetPrefs(file).source(5)) // survives a new process
        p.remove(intArrayOf(5)); assertEquals(TaskSource.TODAY, p.source(5)); assertEquals(TaskSource.filter(30), p.source(6))
        p.clear(); assertEquals(TaskSource.TODAY, p.source(6))
    }

    @Test fun aSourceOpensItsOwnListInTheApp() {
        assertEquals(listOf("a80://today", "a80://inbox", "a80://project/10", "a80://filter/30"),
            listOf(TaskSource.TODAY, TaskSource.INBOX, TaskSource.project(10), TaskSource.filter(30)).map { it.route })
        TaskSource.INBOX.route.let { assertEquals("inbox", com.knhdixh.attendance80.navigation.internalRoute(it)) }
        assertEquals("project/10", com.knhdixh.attendance80.navigation.internalRoute("a80://project/10"))
        assertEquals("filter/30", com.knhdixh.attendance80.navigation.internalRoute("a80://filter/30"))
        assertNull(com.knhdixh.attendance80.navigation.internalRoute("a80://project/0"))
    }

    private fun texts(content: @androidx.compose.runtime.Composable () -> Unit): List<String> {
        val views = runBlocking { GlanceRemoteViews().compose(context, DpSize(250.dp, 250.dp)) { GlanceTheme { content() } }.remoteViews }
        val out = mutableListOf<String>()
        fun walk(v: View) { if (v is TextView) out += v.text.toString(); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(views.apply(context, FrameLayout(context)))
        return out
    }

    @Test fun theWidgetDrawsItsTitleCountAndTheStatesInsteadOfAList() {
        fun draw(source: TaskSource, d: com.knhdixh.attendance80.net.AppData?, signedIn: Boolean = true) = texts { TasksWidgetContent(context, source, d, signedIn, taskNow, taskZone) }
        val t = draw(TaskSource.project(10), data)
        assertTrue(t.toString(), "Home" in t && "3" in t && "+" in t)
        assertTrue(draw(TaskSource.project(12), taskData(projects = listOf(ProjectDto(12, name = "Work")))).toString(), "Nothing to do here." in draw(TaskSource.project(12), taskData(projects = listOf(ProjectDto(12, name = "Work")))))
        assertTrue(draw(TaskSource.project(99), data).toString(), draw(TaskSource.project(99), data).any { it.startsWith("This list was deleted") })
        assertTrue(draw(TaskSource.TODAY, null, signedIn = false).toString(), "Open the app to sign in." in draw(TaskSource.TODAY, null, signedIn = false))
        assertTrue(draw(TaskSource.TODAY, null).toString(), "Loading…" in draw(TaskSource.TODAY, null))
    }

    @Test fun theQuickAddAndTaskListWidgetsAreRegisteredWithTheirMetadataAndTheConfigScreen() {
        fun receiver(name: String) = context.packageManager.getReceiverInfo(ComponentName("com.knhdixh.attendance80", name), PackageManager.GET_META_DATA)
        assertNotNull(receiver("com.knhdixh.attendance80.widget.TasksWidgetReceiver").metaData?.get("android.appwidget.provider"))
        assertNotNull(receiver("com.knhdixh.attendance80.widget.QuickAddWidgetReceiver").metaData?.get("android.appwidget.provider"))
        val config = context.packageManager.getActivityInfo(ComponentName("com.knhdixh.attendance80", "com.knhdixh.attendance80.widget.TasksWidgetConfigActivity"), 0)
        assertTrue(config.exported)
    }
}
