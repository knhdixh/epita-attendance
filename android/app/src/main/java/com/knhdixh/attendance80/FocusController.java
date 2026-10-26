package com.knhdixh.attendance80;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/**
 * Every change to the timer goes through here, whoever makes it (the page, the notification, the tile, the
 * widget or the alarm): it is saved, the end of the phase is scheduled as an exact alarm, and the notification,
 * widget and tile are redrawn. Changes made in the app are sent to the page when it's connected.
 */
final class FocusController {
    static final String ACTION_TOGGLE = "com.knhdixh.attendance80.FOCUS_TOGGLE";
    static final String ACTION_SKIP = "com.knhdixh.attendance80.FOCUS_SKIP";
    static final String ACTION_STOP = "com.knhdixh.attendance80.FOCUS_STOP";
    static final String ACTION_PHASE_END = "com.knhdixh.attendance80.FOCUS_PHASE_END";

    private FocusController() { }

    static synchronized void apply(Context c, FocusTimer t, JSONObject log, boolean fromPage) {
        FocusStore store = new FocusStore(c);
        if (!fromPage) t.updatedAt = System.currentTimeMillis();
        store.saveTimer(t);
        if (log != null) store.addLog(log);
        schedule(c, t);
        FocusNotifications.showTimer(c, t);
        FocusWidget.updateAll(c);
        FocusTileService.refresh(c);
        if (!fromPage) Bridge.sendTimer(c, t, log);
    }

    /** A timer from the page: taken when it is newer than ours. */
    static void fromPage(Context c, JSONObject timer, JSONObject settings) {
        FocusStore store = new FocusStore(c);
        store.saveSettings(settings);
        FocusTimer t = FocusTimer.fromJson(timer);
        if (t.updatedAt >= store.timer().updatedAt) {
            boolean started = t.running() && !store.timer().running();
            apply(c, t, null, true);
            if (started) PermissionActivity.askOnce(c);
        }
    }

    static void toggle(Context c) {
        FocusStore store = new FocusStore(c);
        FocusTimer t = store.timer();
        long now = System.currentTimeMillis();
        apply(c, !t.running() ? t.start(store.settings(), now) : t.paused() ? t.resume(now) : t.pause(now), null, false);
    }

    static void skip(Context c) {
        FocusStore store = new FocusStore(c);
        FocusTimer t = store.timer();
        if (!t.running()) { apply(c, t.skipIdle(), null, false); return; }
        FocusTimer.Result r = t.finish(store.settings(), System.currentTimeMillis(), true);
        apply(c, r.next, r.log, false);
    }

    static void stop(Context c) {
        FocusTimer t = new FocusStore(c).timer();
        if (!t.running()) return;
        FocusTimer.Result r = t.stop(System.currentTimeMillis());
        apply(c, r.next, r.log, false);
    }

    /** The exact alarm: end the phase, start the next one if the settings say so, and say so. */
    static void phaseEnded(Context c) {
        FocusStore store = new FocusStore(c);
        FocusTimer t = store.timer();
        long now = System.currentTimeMillis();
        if (!t.isDue(now)) { schedule(c, t); return; }
        FocusTimer.Settings s = store.settings();
        String ended = t.phase;
        String label = t.label();
        FocusTimer.Result r = t.finish(s, now, false);
        apply(c, r.next, r.log, false);
        FocusNotifications.phaseDone(c, ended, label, r.next, s);
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, FocusReceiver.class).setAction(ACTION_PHASE_END);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Exact, and allowed in Doze: the lock screen and the "done" notification can't be late. */
    static void schedule(Context c, FocusTimer t) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent(c);
        am.cancel(pi);
        if (!t.running() || t.paused()) return;
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endsAt, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endsAt, pi);
    }
}
