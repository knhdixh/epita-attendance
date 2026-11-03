package com.knhdixh.attendance80.notify

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidReminderNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val checkIn = Reminder("checkin:10:1", Kinds.CHECKIN, 0, "Were you at Maths?", "08:00–10:00", "a80://session/10", 10,
        listOf(ReminderAction("Present", "checkin", "present"), ReminderAction("Absent", "checkin", "absent")))

    @Test fun postsTheTextAndOneButtonPerAction() {
        val notifier = AndroidReminderNotifier(context)
        notifier.post(checkIn, "me@example.com")
        val n = shadowOf(manager).allNotifications.single()
        assertEquals("Were you at Maths?", shadowOf(n).contentTitle)
        assertEquals("08:00–10:00", shadowOf(n).contentText)
        assertEquals(listOf("Present", "Absent"), n.actions.map { it.title.toString() })
        assertEquals(AndroidReminderNotifier.CHANNEL, n.channelId)
        assertTrue(n.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test fun eachButtonCarriesWhatTheReceiverNeedsAndIsItsOwnPendingIntent() {
        AndroidReminderNotifier(context).post(checkIn, "me@example.com")
        val n = shadowOf(manager).allNotifications.single()
        val intents = n.actions.map { shadowOf(it.actionIntent).savedIntent }
        assertEquals(listOf("present", "absent"), intents.map { it.getStringExtra(NotifyReceiver.EXTRA_VALUE) })
        assertEquals(NotifyReceiver.ACTION_DO, intents[0].action)
        assertEquals("checkin", intents[0].getStringExtra(NotifyReceiver.EXTRA_TYPE))
        assertEquals(10L, intents[0].getLongExtra(NotifyReceiver.EXTRA_REF, -1))
        assertEquals("me@example.com", intents[0].getStringExtra(NotifyReceiver.EXTRA_OWNER))
        assertEquals("checkin:10:1", intents[0].getStringExtra(NotifyReceiver.EXTRA_KEY))
        assertNotEquals(intents[0].data, intents[1].data)
        assertEquals("a80://session/10", shadowOf(n.contentIntent).savedIntent.dataString)
    }

    @Test fun cancelRemovesIt() {
        val notifier = AndroidReminderNotifier(context)
        notifier.post(checkIn, "me@example.com")
        notifier.cancel(checkIn.key)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun cancelAllLeavesOtherChannelsAlone() {
        val notifier = AndroidReminderNotifier(context)
        notifier.post(checkIn, "me@example.com")
        androidx.core.app.NotificationManagerCompat.from(context).notify(77, androidx.core.app.NotificationCompat.Builder(context, "other").setSmallIcon(com.knhdixh.attendance80.R.drawable.ic_stat_focus).build())
        notifier.cancelAll()
        assertEquals(listOf("other"), shadowOf(manager).allNotifications.map { it.channelId })
    }
}
