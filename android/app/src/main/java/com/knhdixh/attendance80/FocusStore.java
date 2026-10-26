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
    /** Settings from the page; tasks it has now saved stop being pending. */
    void saveSettings(JSONObject s) {
        if (s == null) return;
        prefs.edit().putString("settings", s.toString()).apply();
        JSONArray saved = s.optJSONArray("tasks"), pending = pendingTasks(), keep = new JSONArray();
        java.util.List<String> savedList = new java.util.ArrayList<>();
        for (int i = 0; saved != null && i < saved.length(); i++) savedList.add(saved.optString(i));
        for (int i = 0; i < pending.length(); i++) if (!containsIgnoreCase(savedList, pending.optString(i))) keep.put(pending.optString(i));
        prefs.edit().putString("pendingTasks", keep.toString()).apply();
    }

    JSONObject snapshot() { return json("snapshot"); }
    void saveSnapshot(JSONObject s) { prefs.edit().putString("snapshot", s.toString()).apply(); }

    /** The raw settings from the page, with your tasks and tags. */
    JSONObject settingsJson() { JSONObject o = json("settings"); return o != null ? o : new JSONObject(); }

    /** Tasks added in the quick-focus sheet that the page hasn't saved yet. */
    JSONArray pendingTasks() {
        try { return new JSONArray(prefs.getString("pendingTasks", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    void addPendingTask(String label) {
        JSONArray list = pendingTasks();
        for (int i = 0; i < list.length(); i++) if (list.optString(i).equalsIgnoreCase(label)) return;
        list.put(label);
        prefs.edit().putString("pendingTasks", list.toString()).apply();
    }

    /** Your tasks: the saved ones, then any still waiting for the page. */
    java.util.List<String> tasks() {
        java.util.List<String> out = new java.util.ArrayList<>();
        JSONArray saved = settingsJson().optJSONArray("tasks"), pending = pendingTasks();
        for (int i = 0; pending != null && i < pending.length(); i++) out.add(pending.optString(i));
        for (int i = 0; saved != null && i < saved.length(); i++) if (!containsIgnoreCase(out, saved.optString(i))) out.add(saved.optString(i));
        return out;
    }

    java.util.List<String> tags() {
        java.util.List<String> out = new java.util.ArrayList<>();
        JSONArray saved = settingsJson().optJSONArray("tags");
        for (int i = 0; saved != null && i < saved.length(); i++) out.add(saved.optString(i));
        return out;
    }

    private static boolean containsIgnoreCase(java.util.List<String> list, String x) {
        for (String y : list) if (y.equalsIgnoreCase(x)) return true;
        return false;
    }

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
