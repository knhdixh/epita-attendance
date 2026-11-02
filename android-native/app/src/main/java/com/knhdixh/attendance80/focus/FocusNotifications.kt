package com.knhdixh.attendance80.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.knhdixh.attendance80.MainActivity
import com.knhdixh.attendance80.R
import com.knhdixh.attendance80.logic.FocusSettings
import com.knhdixh.attendance80.logic.FocusTimer
import com.knhdixh.attendance80.logic.PHASES
import com.knhdixh.attendance80.logic.progress
import com.knhdixh.attendance80.logic.remaining
import java.text.DateFormat
import java.util.Date

internal fun phaseName(phase: String): String = PHASES[phase] ?: "Short break"

internal fun clock(ms: Long): String {
    val s = (ms + 999) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%02d:%02d".format(m, s % 60)
}

/**
 * Two notifications: the running timer (an ongoing countdown that ticks on the lock screen by itself, with Pause,
 * Skip and Stop; a Live Update on Android 16+), and "phase done", which rings when a phase ends.
 */
class FocusNotifications(private val context: Context) : FocusNotifier {
    private val manager get() = NotificationManagerCompat.from(context)

    private fun channels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TIMER, "Focus timer", NotificationManager.IMPORTANCE_LOW).apply {
            description = "The running focus timer, also on the lock screen"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val chime = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.chime}")
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Focus and break ends", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When a focus or a break is over"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(chime, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
        // The "Play a sound" setting is per account, a channel's sound is per device: the quiet one is for when it is off.
        nm.createNotificationChannel(NotificationChannel(CH_DONE_QUIET, "Focus and break ends (silent)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Used when “Play a sound when a phase ends” is off"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    private fun openApp(request: Int): PendingIntent = PendingIntent.getActivity(
        context, request,
        Intent(Intent.ACTION_VIEW, Uri.parse("a80://focus")).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(action: String, request: Int): PendingIntent = PendingIntent.getBroadcast(
        context, request, Intent(context, FocusReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun allowed() = manager.areNotificationsEnabled()

    @Suppress("MissingPermission") // checked by allowed()
    override fun showTimer(t: FocusTimer, settings: FocusSettings, now: Long) {
        if (!t.running()) { manager.cancel(ID_TIMER); return }
        if (!allowed()) return
        channels()
        val label = t.target?.label
        val title = phaseName(t.phase) + if (t.phase == "focus" && label != null) ": $label" else ""
        val text = if (t.paused()) "Paused · ${clock(remaining(t, now))} left"
            else "Ends at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.endsAt))}"
        val percent = (progress(t, now) * 100).toInt().coerceIn(0, 100)
        val toggle = if (t.paused()) "Resume" else "Pause"
        val skip = if (t.phase == "focus") "Take a break" else "Skip break"
        val promoted = if (Build.VERSION.SDK_INT >= 36) runCatching {
            LiveUpdate.build(context, CH_TIMER, title, text, t, percent, openApp(10),
                listOf(toggle to action(FocusReceiver.ACTION_TOGGLE, 20), skip to action(FocusReceiver.ACTION_SKIP, 21), "Stop" to action(FocusReceiver.ACTION_STOP, 22)))
        }.getOrNull() else null
        val notification = promoted ?: NotificationCompat.Builder(context, CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setColor(COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(10))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent, false)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                // The system counts down to endsAt by itself: nothing in the app has to stay awake.
                if (t.paused()) setShowWhen(false) else setWhen(t.endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                addAction(0, toggle, action(FocusReceiver.ACTION_TOGGLE, 20))
                addAction(0, skip, action(FocusReceiver.ACTION_SKIP, 21))
                addAction(0, "Stop", action(FocusReceiver.ACTION_STOP, 22))
            }.build()
        manager.notify(ID_TIMER, notification)
    }

    @Suppress("MissingPermission")
    override fun phaseDone(ended: String, label: String?, next: FocusTimer, settings: FocusSettings) {
        if (!allowed()) return
        channels()
        val nextPhase = if (next.running()) next.phase else next.next
        val minutes = settings.minutes(nextPhase)
        val (title, text) = if (ended == "focus") {
            val kind = if (nextPhase == "long") "long" else "short"
            "Focus done" + (if (label != null) ": $label" else "") + "." to
                if (next.running()) "$minutes-minute $kind break started." else "Time for a $minutes-minute $kind break."
        } else {
            "Break over." to if (next.running()) "Focusing for $minutes minutes." else "Ready when you are: tap to start the next focus."
        }
        val b = NotificationCompat.Builder(context, if (settings.sound) CH_DONE else CH_DONE_QUIET)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setColor(COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(11))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (!settings.sound) b.setSilent(true)
        if (!next.running()) b.addAction(0, "Start " + if (nextPhase == "focus") "focus" else "break", action(FocusReceiver.ACTION_TOGGLE, 23))
        manager.notify(ID_DONE, b.build())
    }

    companion object {
        const val CH_TIMER = "focus_timer"
        const val CH_DONE = "focus_done"
        const val CH_DONE_QUIET = "focus_done_quiet"
        const val ID_TIMER = 41
        const val ID_DONE = 42
        const val COLOR = 0xFF2A6AE0.toInt()
    }
}
