package com.knhdixh.attendance80;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.browser.customtabs.CustomTabsCallback;
import androidx.browser.customtabs.CustomTabsService;
import androidx.browser.customtabs.CustomTabsSession;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;

/**
 * A message channel between the page (in Chrome) and the app, over Chrome's Trusted Web Activity postMessage.
 * The page sends its timer, timer settings and a plan snapshot for the widget; the app sends timer changes made
 * from the notification, tile or widget, and focus sessions it finished, which the page records and acknowledges.
 * Messages are JSON strings: { type: 'hello' | 'timer' | 'plan' | 'logs-ack', ... }. See public/focus-ui.js.
 *
 * The channel needs the site to vouch that the app may speak as its origin ("use_as_origin" in
 * /.well-known/assetlinks.json). androidbrowserhelper keeps its Custom Tabs session private, so it is found
 * by field type (kept by proguard-rules.pro).
 */
final class Bridge extends CustomTabsCallback {
    private static final String TAG = "A80Bridge";
    private static CustomTabsSession session;
    private static volatile boolean ready;
    private static CustomTabsSession requested;   // the session the channel was asked for (Chrome re-validates on it)
    private static Context app;
    private static Uri origin;

    private Bridge() { }

    /** The callback the launcher gives Chrome; call {@link #attach} once the TWA is launching. */
    static CustomTabsCallback callback(Context c) {
        app = c.getApplicationContext();
        origin = Uri.parse("https://" + c.getString(R.string.hostName));
        return new Bridge();
    }

    /** Finds the session (it's created asynchronously) and asks Chrome to validate the origin. */
    static void attach(Activity launcher) {
        Log.i(TAG, "attach");
        Handler h = new Handler(Looper.getMainLooper());
        final int[] tries = {0};
        h.post(new Runnable() {
            @Override public void run() {
                CustomTabsSession s = findSession(launcher);
                if (s == null) {
                    if (++tries[0] < 50) h.postDelayed(this, 200);
                    else Log.w(TAG, "no Custom Tabs session after 10 s");
                    return;
                }
                if (s == session) return;
                session = s;
                ready = false;
                boolean asked = s.validateRelationship(CustomTabsService.RELATION_USE_AS_ORIGIN, origin, null);
                Log.i(TAG, "validating origin " + origin + ": " + asked);
            }
        });
    }

    private static CustomTabsSession findSession(Object launcher) {
        try {
            Object twa = fieldOfType(launcher, com.google.androidbrowserhelper.trusted.TwaLauncher.class);
            return twa == null ? null : (CustomTabsSession) fieldOfType(twa, CustomTabsSession.class);
        } catch (Exception e) {
            Log.w(TAG, "no session", e);
            return null;
        }
    }

    private static Object fieldOfType(Object o, Class<?> type) throws IllegalAccessException {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) { f.setAccessible(true); return f.get(o); }
            }
        }
        return null;
    }

    @Override
    public void onRelationshipValidationResult(int relation, Uri requestedOrigin, boolean result, Bundle extras) {
        if (!result || session == null || requested == session) return;
        Log.i(TAG, "origin validated");
        requested = session;
        session.requestPostMessageChannel(origin, origin, new Bundle());
    }

    @Override
    public void onMessageChannelReady(Bundle extras) {
        ready = true;
        Log.i(TAG, "channel ready");
        FocusStore store = new FocusStore(app);
        JSONObject hello = new JSONObject();
        try {
            hello.put("type", "hello");
            hello.put("v", 1);
            hello.put("timer", store.timer().toJson());
            hello.put("logs", store.logs());
            hello.put("addTasks", store.pendingTasks());
        } catch (JSONException ignored) { }
        send(hello);
    }

    @Override
    public void onPostMessage(String message, Bundle extras) {
        if (app == null) return;
        try {
            JSONObject m = new JSONObject(message);
            switch (m.optString("type")) {
                case "timer": FocusController.fromPage(app, m.optJSONObject("timer"), m.optJSONObject("settings")); break;
                case "plan": new FocusStore(app).saveSnapshot(m); FocusWidget.updateAll(app); break;
                case "logs-ack": JSONArray ids = m.optJSONArray("ids"); if (ids != null) new FocusStore(app).ackLogs(ids); break;
                default: break;
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad message", e);
        }
    }

    /** A timer change made in the app, and the session it finished if any. */
    static void sendTimer(Context c, FocusTimer t, JSONObject log) {
        JSONObject m = new JSONObject();
        try {
            m.put("type", "timer");
            m.put("timer", t.toJson());
            if (log != null) m.put("logs", new JSONArray().put(log));
        } catch (JSONException ignored) { }
        send(m);
    }

    /** A task added in the quick-focus sheet, for the page to keep on the account. */
    static void sendTasks(Context c) {
        JSONObject m = new JSONObject();
        try {
            m.put("type", "tasks");
            m.put("addTasks", new FocusStore(c).pendingTasks());
        } catch (JSONException ignored) { }
        send(m);
    }

        private static void send(JSONObject m) {
        CustomTabsSession s = session;
        if (!ready || s == null) return;
        try {
            int r = s.postMessage(m.toString(), null);
            if (r != CustomTabsService.RESULT_SUCCESS) { ready = false; Log.i(TAG, "channel closed: " + r); }
        } catch (RuntimeException e) {
            ready = false;
        }
    }
}
