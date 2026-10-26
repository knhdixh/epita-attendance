package com.knhdixh.attendance80;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Two notifications: the running timer (a silent, ongoing countdown that ticks on the lock screen by itself,
 * with Pause, Skip and Stop), and "phase done", which rings when a phase ends.
 */
final class FocusNotifications {
    private static final String CH_TIMER = "focus_timer";
    private static final String CH_DONE = "focus_done";
    private static final int ID_TIMER = 41;
    private static final int ID_DONE = 42;
    static final int COLOR = 0xFF2A6AE0;

    private FocusNotifications() { }

    private static void channels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel timer = new NotificationChannel(CH_TIMER, "Focus timer", NotificationManager.IMPORTANCE_LOW);
        timer.setDescription("The running focus timer, also on the lock screen");
        timer.setShowBadge(false);
        timer.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(timer);
        NotificationChannel done = new NotificationChannel(CH_DONE, "Focus and break ends", NotificationManager.IMPORTANCE_HIGH);
        done.setDescription("When a focus or a break is over");
        done.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(done);
    }

    static PendingIntent openApp(Context c, String hash, int request) {
        Intent i = new Intent(c, LauncherActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("https://" + c.getString(R.string.hostName) + "/#" + hash))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, request, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent action(Context c, String action, int request) {
        Intent i = new Intent(c, FocusReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, request, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static String phaseName(String phase) {
        return "focus".equals(phase) ? "Focus" : "long".equals(phase) ? "Long break" : "Short break";
    }

    static String clock(long ms) {
        long s = (ms + 999) / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s % 60) : String.format("%02d:%02d", m, s % 60);
    }

    private static boolean allowed(Context c) { return NotificationManagerCompat.from(c).areNotificationsEnabled(); }

    @SuppressWarnings("MissingPermission") // checked by allowed()
    static void showTimer(Context c, FocusTimer t) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        if (!t.running()) { nm.cancel(ID_TIMER); return; }
        if (!allowed(c)) return;
        channels(c);
        long now = System.currentTimeMillis();
        String label = t.label();
        String title = phaseName(t.phase) + ("focus".equals(t.phase) && label != null ? ": " + label : "");
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_TIMER)
                .setSmallIcon(R.drawable.ic_stat_focus)
                .setColor(COLOR)
                .setContentTitle(title)
                .setContentIntent(openApp(c, "focus", 10))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (t.paused()) {
            b.setContentText("Paused · " + clock(t.remaining(now)) + " left").setShowWhen(false);
        } else {
            // The system counts down to endsAt by itself: nothing in the app has to stay awake.
            b.setWhen(t.endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                    .setContentText("Ends at " + android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(t.endsAt)));
        }
        b.addAction(0, t.paused() ? "Resume" : "Pause", action(c, FocusController.ACTION_TOGGLE, 20));
        b.addAction(0, "focus".equals(t.phase) ? "Take a break" : "Skip break", action(c, FocusController.ACTION_SKIP, 21));
        b.addAction(0, "Stop", action(c, FocusController.ACTION_STOP, 22));
        nm.notify(ID_TIMER, b.build());
    }

    @SuppressWarnings("MissingPermission")
    static void phaseDone(Context c, String ended, String label, FocusTimer next, FocusTimer.Settings s) {
        if (!allowed(c)) return;
        channels(c);
        String title, text;
        String nextPhase = next.running() ? next.phase : next.next;
        long minutes = s.lengthMs(nextPhase) / FocusTimer.MIN;
        if ("focus".equals(ended)) {
            title = "Focus done" + (label != null ? ": " + label : "") + ".";
            String brk = "long".equals(nextPhase) ? "long" : "short";
            text = next.running() ? minutes + "-minute " + brk + " break started." : "Time for a " + minutes + "-minute " + brk + " break.";
        } else {
            title = "Break over.";
            text = next.running() ? "Focusing for " + minutes + " minutes." : "Ready when you are: tap to start the next focus.";
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_focus)
                .setColor(COLOR)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(c, "focus", 11))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(s.sound ? NotificationCompat.DEFAULT_ALL : NotificationCompat.DEFAULT_VIBRATE)
                .setSilent(!s.sound);
        if (!next.running()) b.addAction(0, "Start " + ("focus".equals(nextPhase) ? "focus" : "break"), action(c, FocusController.ACTION_TOGGLE, 23));
        NotificationManagerCompat.from(c).notify(ID_DONE, b.build());
    }
}
