package com.knhdixh.attendance80.notify

import android.app.AlarmManager
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
class AndroidReminderAlarmTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val at = 1_800_000_000_000L

    @Test fun theAlarmIsAWakeupAtThePlannedTimeAndExactWhileIdle() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AndroidReminderAlarm(context).schedule(at)
        val alarm = shadowOf(am).scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(at, alarm.triggerAtMs)
        assertEquals(0L, alarm.windowLengthMs) // exact (the shadow does not record "while idle" for the exact call: not asserted)
    }

    @Test fun withoutExactAlarmsTheDelayIsStillBoundedByAWindow() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AndroidReminderAlarm(context).schedule(at)
        val alarm = shadowOf(am).scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(at, alarm.triggerAtMs)
        assertEquals(AndroidReminderAlarm.WINDOW, alarm.windowLengthMs)
    }

    @Test fun schedulingAgainReplacesAndNullCancels() {
        val alarm = AndroidReminderAlarm(context)
        alarm.schedule(at); alarm.schedule(at + 1000)
        assertEquals(at + 1000, shadowOf(am).scheduledAlarms.single().triggerAtMs)
        alarm.schedule(null)
        assertTrue(shadowOf(am).scheduledAlarms.isEmpty())
    }
}
