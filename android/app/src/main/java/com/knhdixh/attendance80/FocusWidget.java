package com.knhdixh.attendance80;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Date;

/**
 * Home-screen widget: the class you're in or the next one, the top of "Do next", and the focus timer with a
 * Start/Pause button. The plan comes from the page (a snapshot sent over the Bridge each time it loads), so it
 * works offline; the widget moves on to the following class by itself when one ends.
 */
public class FocusWidget extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.knhdixh.attendance80.WIDGET_REFRESH";
    private static final int[] TODO = {R.id.todo1, R.id.todo2, R.id.todo3};

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { updateAll(c); }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, FocusWidget.class));
        if (ids.length == 0) return;
        RemoteViews v = build(c);
        for (int id : ids) m.updateAppWidget(id, v);
    }

    private static RemoteViews build(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.focus_widget);
        FocusStore store = new FocusStore(c);
        long now = System.currentTimeMillis();
        JSONObject snap = store.snapshot();

        // --- class: the one you're in, else the next one
        JSONObject cls = null;
        JSONArray classes = snap != null ? snap.optJSONArray("classes") : null;
        long boundary = Long.MAX_VALUE;
        for (int i = 0; classes != null && i < classes.length(); i++) {
            JSONObject e = classes.optJSONObject(i);
            if (e == null || e.optLong("end") <= now) continue;
            cls = e;
            boundary = e.optLong("start") > now ? e.optLong("start") : e.optLong("end");
            break;
        }
        if (cls != null) {
            long start = cls.optLong("start"), end = cls.optLong("end");
            boolean inIt = start <= now;
            v.setTextViewText(R.id.class_head, inIt ? "Now" : isToday(start) ? "Next class" : "Next class · " + day(start));
            v.setTextViewText(R.id.class_name, cls.optString("title"));
            v.setTextViewText(R.id.class_time, inIt ? "until " + time(c, end) : time(c, start));
            String where = cls.optString("where", "");
            String rel = inIt ? "" : relative(start, now);
            v.setTextViewText(R.id.class_where, where.isEmpty() ? rel : rel.isEmpty() ? where : where + " · " + rel);
        } else {
            v.setTextViewText(R.id.class_head, "Next class");
            v.setTextViewText(R.id.class_name, snap == null ? "Open the app once to load your day" : "No more classes this week");
            v.setTextViewText(R.id.class_time, "");
            v.setTextViewText(R.id.class_where, "");
        }

        // --- do next
        JSONArray todo = snap != null ? snap.optJSONArray("todo") : null;
        int n = todo == null ? 0 : Math.min(todo.length(), TODO.length);
        for (int i = 0; i < TODO.length; i++) {
            if (i < n) {
                JSONObject h = todo.optJSONObject(i);
                String due = h.optLong("due") > 0 ? "  ·  " + (h.optLong("due") < now ? "overdue" : day(h.optLong("due"))) : "";
                v.setTextViewText(TODO[i], "• " + h.optString("title") + due);
                v.setViewVisibility(TODO[i], View.VISIBLE);
            } else {
                v.setViewVisibility(TODO[i], i == 0 && snap != null ? View.VISIBLE : View.GONE);
                if (i == 0) v.setTextViewText(TODO[0], "Nothing due soon");
            }
        }

        // --- timer: the system counts the chronometer down by itself
        FocusTimer t = store.timer();
        boolean brk = t.running() ? !"focus".equals(t.phase) : !"focus".equals(t.next);
        v.setInt(R.id.timer_icon, "setColorFilter", c.getColor(brk ? R.color.widget_break : R.color.widget_accent));
        if (t.running() && !t.paused()) {
            v.setTextViewText(R.id.timer_phase, FocusNotifications.phaseName(t.phase));
            v.setChronometer(R.id.timer_clock, SystemClock.elapsedRealtime() + t.remaining(now), null, true);
            v.setChronometerCountDown(R.id.timer_clock, true);
            v.setViewVisibility(R.id.timer_clock, View.VISIBLE);
            String label = t.label();
            v.setTextViewText(R.id.timer_text, "focus".equals(t.phase) && label != null ? label : "");
            v.setTextViewText(R.id.timer_button, "Pause");
        } else if (t.running()) {
            v.setTextViewText(R.id.timer_phase, FocusNotifications.phaseName(t.phase));
            v.setChronometer(R.id.timer_clock, SystemClock.elapsedRealtime(), null, false);
            v.setViewVisibility(R.id.timer_clock, View.GONE);
            v.setTextViewText(R.id.timer_text, "Paused · " + FocusNotifications.clock(t.remaining(now)) + " left");
            v.setTextViewText(R.id.timer_button, "Resume");
        } else {
            v.setTextViewText(R.id.timer_phase, "focus".equals(t.next) ? "Focus" : FocusNotifications.phaseName(t.next));
            v.setViewVisibility(R.id.timer_clock, View.GONE);
            v.setTextViewText(R.id.timer_text, store.settings().lengthMs(t.next) / FocusTimer.MIN + " min");
            v.setTextViewText(R.id.timer_button, "Start");
        }

        v.setOnClickPendingIntent(R.id.timer_button, FocusNotifications.action(c, FocusController.ACTION_TOGGLE, 30));
        v.setOnClickPendingIntent(R.id.timer_box, FocusNotifications.openApp(c, "focus", 31));
        v.setOnClickPendingIntent(R.id.class_box, FocusNotifications.openApp(c, "upcoming", 32));
        v.setOnClickPendingIntent(R.id.todo_box, FocusNotifications.openApp(c, "today", 33));

        scheduleRefresh(c, boundary);
        return v;
    }

    /** Redraw when the current class ends or the next one starts (and at midnight, for "Next class · Tue"). */
    private static void scheduleRefresh(Context c, long boundary) {
        Calendar mid = Calendar.getInstance();
        mid.add(Calendar.DAY_OF_YEAR, 1);
        mid.set(Calendar.HOUR_OF_DAY, 0); mid.set(Calendar.MINUTE, 0); mid.set(Calendar.SECOND, 5);
        long at = Math.min(boundary, mid.getTimeInMillis());
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, FocusReceiver.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(c, 2, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.set(AlarmManager.RTC, at + 1000, pi);
    }

    private static String time(Context c, long ms) { return DateFormat.getTimeFormat(c).format(new Date(ms)); }
    private static boolean isToday(long ms) { return DateUtils.isToday(ms); }
    private static String day(long ms) {
        if (DateUtils.isToday(ms)) return "today";
        if (DateUtils.isToday(ms - DateUtils.DAY_IN_MILLIS)) return "tomorrow";
        return android.text.format.DateFormat.format("EEE d MMM", ms).toString();
    }
    private static String relative(long start, long now) {
        long min = (start - now + 59_999) / 60_000;
        if (min < 60) return "in " + min + " min";
        if (min < 6 * 60) return "in " + (min / 60) + " h" + (min % 60 > 0 ? " " + (min % 60) + " min" : "");
        return "";
    }
}
