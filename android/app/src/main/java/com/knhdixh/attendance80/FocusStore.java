package com.knhdixh.attendance80;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What the app keeps between launches: the timer, its settings, focus sessions it finished while the page wasn't
 * connected (sent to the page next time, which records them), and the plan snapshot the widget shows.
 */
final class FocusStore {
    private static final String PREFS = "focus";
    private final SharedPreferences prefs;

    FocusStore(Context c) { prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    FocusTimer timer() { return FocusTimer.fromJson(json("timer")); }
    void saveTimer(FocusTimer t) { prefs.edit().putString("timer", t.toJson().toString()).apply(); }

    FocusTimer.Settings settings() { return FocusTimer.Settings.fromJson(json("settings")); }
    void saveSettings(JSONObject s) { if (s != null) prefs.edit().putString("settings", s.toString()).apply(); }

    JSONObject snapshot() { return json("snapshot"); }
    void saveSnapshot(JSONObject s) { prefs.edit().putString("snapshot", s.toString()).apply(); }

    JSONArray logs() {
        try { return new JSONArray(prefs.getString("logs", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    void addLog(JSONObject log) {
        JSONArray list = logs();
        list.put(log);
        // Keep at most a few weeks of sessions if the page is never opened.
        while (list.length() > 200) list.remove(0);
        prefs.edit().putString("logs", list.toString()).apply();
    }

    /** The page recorded these: forget them. */
    void ackLogs(JSONArray ids) {
        JSONArray list = logs(), keep = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject l = list.optJSONObject(i);
            boolean acked = false;
            for (int j = 0; j < ids.length() && l != null; j++) if (ids.optString(j).equals(l.optString("client_id"))) acked = true;
            if (l != null && !acked) keep.put(l);
        }
        prefs.edit().putString("logs", keep.toString()).apply();
    }

    private JSONObject json(String key) {
        String v = prefs.getString(key, null);
        if (v == null) return null;
        try { return new JSONObject(v); } catch (JSONException e) { return null; }
    }
}
