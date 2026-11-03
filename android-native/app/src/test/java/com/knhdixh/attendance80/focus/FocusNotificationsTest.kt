package com.knhdixh.attendance80.focus

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.logic.FocusSettings
import com.knhdixh.attendance80.logic.FocusTarget
import com.knhdixh.attendance80.logic.FocusTimer
import com.knhdixh.attendance80.logic.idle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FocusNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val now = 1_800_000_000_000L
    private val running = FocusTimer(phase = "focus", next = "focus", run = "r", startedAt = now, endsAt = now + 25 * 60_000L, target = FocusTarget(label = "Essay"))

    @Test fun runningTimerIsAnOngoingCountdownWithThreeActions() {
        FocusNotifications(context).showTimer(running, FocusSettings(), now)
        val n = shadowOf(nm).getNotification(FocusNotifications.ID_TIMER)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Focus: Essay", n.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(running.endsAt, n.`when`)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals(listOf("Pause", "Take a break", "Stop"), n.actions.map { it.title.toString() })
    }

    @Test fun pausedTimerShowsTheTimeLeftAndResume() {
        FocusNotifications(context).showTimer(running.copy(pausedAt = now + 5 * 60_000L), FocusSettings(), now + 5 * 60_000L)
        val n = shadowOf(nm).getNotification(FocusNotifications.ID_TIMER)
        assertEquals("Paused · 20:00 left", n.extras.getString(Notification.EXTRA_TEXT))
        assertFalse(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("Resume", n.actions.first().title.toString())
    }

    @Test fun idleCancelsTheTimerNotification() {
        val n = FocusNotifications(context)
        n.showTimer(running, FocusSettings(), now)
        n.showTimer(idle(), FocusSettings(), now)
        assertNull(shadowOf(nm).getNotification(FocusNotifications.ID_TIMER))
    }

    @Test fun phaseDoneUsesTheChimeChannelOrTheQuietOne() {
        val breakStarted = FocusTimer(phase = "short", next = "short", run = "b", startedAt = now, endsAt = now + 5 * 60_000L)
        val n = FocusNotifications(context)
        n.phaseDone("focus", "Essay", breakStarted, FocusSettings(sound = true))
        val loud = shadowOf(nm).getNotification(FocusNotifications.ID_DONE)
        assertEquals(FocusNotifications.CH_DONE, loud.channelId)
        assertEquals("Focus done: Essay.", loud.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("5-minute short break started.", loud.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(nm.getNotificationChannel(FocusNotifications.CH_DONE).sound)
        n.phaseDone("short", null, idle(), FocusSettings(sound = false))
        val quiet = shadowOf(nm).getNotification(FocusNotifications.ID_DONE)
        assertEquals(FocusNotifications.CH_DONE_QUIET, quiet.channelId)
        assertEquals("Break over.", quiet.extras.getString(Notification.EXTRA_TITLE))
        assertNull(nm.getNotificationChannel(FocusNotifications.CH_DONE_QUIET).sound)
        assertEquals(listOf("Start focus"), quiet.actions.map { it.title.toString() })
    }

    @Test fun phaseDoneStaysWhenTheRunningTimerIsRedrawnAndGoesOnClearDone() {
        val breakStarted = FocusTimer(phase = "short", next = "short", run = "b", startedAt = now, endsAt = now + 5 * 60_000L)
        val n = FocusNotifications(context)
        n.phaseDone("focus", "Essay", breakStarted, FocusSettings(sound = true))
        n.showTimer(breakStarted, FocusSettings(), now) // restore(), onResume, a settings change
        assertNotNull(shadowOf(nm).getNotification(FocusNotifications.ID_DONE))
        assertNotNull(shadowOf(nm).getNotification(FocusNotifications.ID_TIMER))
        n.clearDone()
        assertNull(shadowOf(nm).getNotification(FocusNotifications.ID_DONE))
    }
}
