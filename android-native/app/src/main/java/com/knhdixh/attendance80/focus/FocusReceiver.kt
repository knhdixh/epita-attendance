package com.knhdixh.attendance80.focus

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.knhdixh.attendance80.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Notification and tile buttons, the end-of-phase alarm, and re-arming after a reboot, an update, or a change of
 * time or time zone (the alarm is in wall-clock time, so a clock change can move it).
 */
class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val controller = (context.applicationContext as App).container.focusController ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                dispatch(controller, action)
                // Awaited, so a cached process is not frozen before the widget update is enqueued.
                com.knhdixh.attendance80.widget.redrawWidget(context)
            } finally { pending.finish() }
        }
    }

    companion object {
        /** What each broadcast asks of the controller. Apart from `onReceive`, so a test can run it without the app container. */
        suspend fun dispatch(controller: FocusController, action: String) {
            when (action) {
                ACTION_TOGGLE -> controller.toggle()
                ACTION_SKIP -> controller.skip()
                ACTION_STOP -> controller.stop()
                ACTION_PHASE_END -> controller.phaseEnded()
                ACTION_WIDGET_REFRESH -> Unit
                // The exact-alarm permission arrives granted (Android 14+ for a user-granted one, or any grant on 12 to 13);
                // the alarm armed meanwhile was inexact, so arm it again.
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> controller.restore()
            }
        }

        const val ACTION_TOGGLE = "com.knhdixh.attendance80.FOCUS_TOGGLE"
        const val ACTION_SKIP = "com.knhdixh.attendance80.FOCUS_SKIP"
        const val ACTION_STOP = "com.knhdixh.attendance80.FOCUS_STOP"
        const val ACTION_WIDGET_REFRESH = "com.knhdixh.attendance80.WIDGET_REFRESH"
        const val ACTION_PHASE_END = "com.knhdixh.attendance80.FOCUS_PHASE_END"
    }
}
