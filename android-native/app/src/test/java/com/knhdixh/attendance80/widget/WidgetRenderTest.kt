package com.knhdixh.attendance80.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Draws the real Glance content into views and reads the text back: what each size shows. */
@OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)
@Config(sdk = [35])
@RunWith(RobolectricTestRunner::class)
class WidgetRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_793_606_400_000L - 3_600_000 // an hour before the first class
    private val snapshot = WidgetSnapshot(
        signedIn = true, builtAt = now,
        classes = listOf(WidgetClass(1, "Mathématiques", now + 3_600_000, now + 10_800_000, "Amphi 1")),
        todo = listOf(WidgetTodo(1, "Maths Exercise 4", now + 86_400_000), WidgetTodo(2, "Algo Binary Trees")),
        timer = WidgetTimer(running = true, phase = "focus", endsAt = now + 600_000, label = "Revise"),
    )

    private fun texts(size: DpSize, s: WidgetSnapshot): List<String> = texts(size) { WidgetContent(s, now) }

    private fun texts(size: DpSize, content: @androidx.compose.runtime.Composable () -> Unit): List<String> {
        val views = runBlocking {
            GlanceRemoteViews().compose(context, size) { GlanceTheme { content() } }.remoteViews
        }
        val root = views.apply(context, FrameLayout(context))
        val out = mutableListOf<String>()
        fun walk(v: View) { if (v is TextView) out += v.text.toString(); if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        walk(root)
        return out
    }

    @Test fun smallShowsClassAndTimerButNotDoNext() {
        val t = texts(DpSize(110.dp, 110.dp), snapshot)
        assertTrue(t.toString(), "Mathématiques" in t && "Pause" in t)
        assertTrue(t.toString(), t.none { it.startsWith("• ") })
    }

    @Test fun wideAddsDoNext() {
        val t = texts(DpSize(250.dp, 110.dp), snapshot)
        assertTrue(t.toString(), "Mathématiques" in t && "Pause" in t)
        assertEquals(t.toString(), 2, t.count { it.startsWith("• ") })
    }

    @Test fun signedOutAsksToOpenTheApp() {
        assertTrue("Open the app to sign in" in texts(DpSize(110.dp, 110.dp), WidgetSnapshot()))
    }

    @Test fun hostDrawsWhatTheStoreHoldsNowNotWhatItHeldWhenTheSessionStarted() {
        val store = WidgetStore(File(Files.createTempDirectory("widget-host").toFile(), "widget.json"))
        val alarms = mutableListOf<Long>()
        val size = DpSize(110.dp, 110.dp)
        fun host(): @androidx.compose.runtime.Composable () -> Unit { val revision = store.revision.value; return { WidgetFromStore(store, revision, clock = { now }, schedule = { _, at -> alarms += at }) } }
        store.updatePlan(snapshot)
        store.updateTimer(snapshot.timer) // start
        assertTrue("Pause" in texts(size, host()))
        store.updateTimer(WidgetTimer(phase = "focus", running = true, paused = true, leftMs = 300_000, endsAt = 0L)) // pause, seconds later
        val t = texts(size, host())
        assertTrue(t.toString(), "Resume" in t && "Pause" !in t)
        assertTrue("the alarm is set from what is drawn", alarms.isNotEmpty() && alarms.all { it > now })
    }

    @Test fun everyWriteAndTouchMovesTheRevisionALiveSessionCollects() {
        val store = WidgetStore(File(Files.createTempDirectory("widget-rev").toFile(), "widget.json"))
        val r0 = store.revision.value
        store.updateTimer(WidgetTimer())
        val r1 = store.revision.value
        store.touch()
        assertTrue(r1 > r0 && store.revision.value > r1)
    }
}
