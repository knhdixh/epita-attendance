package com.knhdixh.attendance80.focus

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
                when (action) {
                    ACTION_TOGGLE -> controller.toggle()
                    ACTION_SKIP -> controller.skip()
                    ACTION_STOP -> controller.stop()
                    ACTION_PHASE_END -> controller.phaseEnded()
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> controller.restore()
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.knhdixh.attendance80.FOCUS_TOGGLE"
        const val ACTION_SKIP = "com.knhdixh.attendance80.FOCUS_SKIP"
        const val ACTION_STOP = "com.knhdixh.attendance80.FOCUS_STOP"
        const val ACTION_PHASE_END = "com.knhdixh.attendance80.FOCUS_PHASE_END"
    }
}
