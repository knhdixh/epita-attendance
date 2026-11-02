package com.knhdixh.attendance80.focus

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.RequiresApi
import com.knhdixh.attendance80.R
import com.knhdixh.attendance80.logic.FocusTimer

/**
 * The running timer as a Live Update (Android 16+): the same ongoing countdown, with a progress bar, which the
 * system may promote to the status bar and lock screen. The caller falls back to the plain notification on any failure.
 */
@RequiresApi(36)
internal object LiveUpdate {
    fun build(context: Context, channel: String, title: String, text: String, t: FocusTimer, percent: Int,
              open: PendingIntent, actions: List<Pair<String, PendingIntent>>): Notification {
        val icon = Icon.createWithResource(context, R.drawable.ic_stat_focus)
        val b = Notification.Builder(context, channel)
            .setSmallIcon(icon)
            .setColor(FocusNotifications.COLOR)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.ProgressStyle()
                    .setProgress(percent)
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(FocusNotifications.COLOR))),
            )
            // Notification.EXTRA_REQUEST_PROMOTED_ONGOING; the setter is not in the compileSdk 36 stubs.
            .addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        if (t.paused()) b.setShowWhen(false) else b.setWhen(t.endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        actions.forEach { (label, pi) -> b.addAction(Notification.Action.Builder(icon, label, pi).build()) }
        return b.build()
    }
}
