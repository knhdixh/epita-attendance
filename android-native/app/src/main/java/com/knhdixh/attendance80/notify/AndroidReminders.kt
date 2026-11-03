package com.knhdixh.attendance80.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.knhdixh.attendance80.MainActivity
import com.knhdixh.attendance80.R

/** Inexact on purpose (a reminder a few minutes late is fine): no exact-alarm permission, and kinder to the battery. */
class AndroidReminderAlarm(private val context: Context) : ReminderAlarm {
    override fun schedule(at: Long?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 2, Intent(context, NotifyReceiver::class.java).setAction(NotifyReceiver.ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
        if (at != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class AndroidReminderNotifier(private val context: Context) : ReminderNotifier {
    private val manager get() = NotificationManagerCompat.from(context)

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Check-in after class, homework, classes, habits and exam days"
            })
    }

    override fun canPost() = manager.areNotificationsEnabled()

    @android.annotation.SuppressLint("MissingPermission") // areNotificationsEnabled() is false without it, and the engine checks that first.
    override fun post(reminder: Reminder, owner: String) {
        channel()
        val open = PendingIntent.getActivity(
            context, reminder.key.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(reminder.route), context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(reminder.title).setContentText(reminder.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open).setAutoCancel(true)
        for (a in reminder.actions) builder.addAction(0, a.label, actionIntent(reminder, a, owner))
        manager.notify(reminder.key.hashCode(), builder.build())
    }

    private fun actionIntent(r: Reminder, a: ReminderAction, owner: String): PendingIntent {
        val intent = Intent(context, NotifyReceiver::class.java).setAction(NotifyReceiver.ACTION_DO)
            // The data makes each button its own PendingIntent (extras don't count when Android compares them).
            .setData(Uri.parse("a80-reminder://${a.type}/${Uri.encode(r.key)}/${Uri.encode(a.value.orEmpty())}"))
            .putExtra(NotifyReceiver.EXTRA_TYPE, a.type).putExtra(NotifyReceiver.EXTRA_VALUE, a.value)
            .putExtra(NotifyReceiver.EXTRA_REF, r.ref ?: -1L).putExtra(NotifyReceiver.EXTRA_OWNER, owner)
            .putExtra(NotifyReceiver.EXTRA_KEY, r.key).putExtra(NotifyReceiver.EXTRA_NUMBER, a.number ?: -1)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun cancel(key: String) = manager.cancel(key.hashCode())

    override fun cancelAll() {
        // Only ours that are showing: the focus timer's notifications are not touched.
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { nm.cancel(it.id) }
    }

    companion object { const val CHANNEL = "reminders" }
}
