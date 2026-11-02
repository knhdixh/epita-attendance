package com.knhdixh.attendance80.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Exact and allowed in Doze, so the lock screen and the "phase done" notification are never late. */
class AndroidPhaseAlarm(private val context: Context) : PhaseAlarm {
    override fun schedule(endsAt: Long?) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = intent(context)
        am.cancel(pi)
        if (endsAt == null) return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pi)
    }

    companion object {
        fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1, Intent(context, FocusReceiver::class.java).setAction(FocusReceiver.ACTION_PHASE_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
