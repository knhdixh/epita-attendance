package com.knhdixh.attendance80;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;

/**
 * The focus (Pomodoro) timer: a port of the timer half of public/focus.js, with the same JSON shape, so the
 * web page and the app can hand the same timer back and forth. Instants, not countdowns: a phase ends at
 * {@link #endsAt} whatever happens to the process in between.
 */
final class FocusTimer {
    static final long MIN = 60_000L;
    private static final long STALE_MS = 5 * MIN;      // a phase that ended longer ago doesn't start the next one
    private static final long MIN_LOGGED_MS = MIN;     // a shorter focus isn't recorded
    static final int MAX_PHASE_MIN = 180;

    String phase = "idle";   // idle | focus | short | long
    String next = "focus";   // what Start begins when idle
    Integer custom;          // idle: minutes for that next phase instead of the setting (null = the setting)
    String run;              // random id: the server records a session once per run
    long startedAt, endsAt, pausedMs;
    Long pausedAt;           // null when not paused
    int done;                // focus sessions since the last long break
    JSONObject target;       // { homework_id, course_id, label, tags }, or null
    long updatedAt;          // last change, by the page or the app: the newer one wins

    /** Timer settings, from the page (DEFAULT_SETTINGS in public/focus.js until it has sent them). */
    static final class Settings {
        int focus = 25, shortBreak = 5, longBreak = 15, longEvery = 4;
        boolean autoBreaks = true, autoFocus = false, sound = true;

        static Settings fromJson(JSONObject o) {
            Settings s = new Settings();
            if (o == null) return s;
            s.focus = o.optInt("focus", s.focus);
            s.shortBreak = o.optInt("short", s.shortBreak);
            s.longBreak = o.optInt("long", s.longBreak);
            s.longEvery = Math.max(2, o.optInt("longEvery", s.longEvery));
            s.autoBreaks = o.optBoolean("autoBreaks", s.autoBreaks);
            s.autoFocus = o.optBoolean("autoFocus", s.autoFocus);
            s.sound = o.optBoolean("sound", s.sound);
            return s;
        }

        long lengthMs(String phase) { return minutes(phase) * MIN; }
        int minutes(String phase) { return "focus".equals(phase) ? focus : "long".equals(phase) ? longBreak : shortBreak; }
    }

    /** What a change produced: the session to record (or null) and the timer that follows. */
    static final class Result {
        final JSONObject log;
        final FocusTimer next;
        Result(JSONObject log, FocusTimer next) { this.log = log; this.next = next; }
    }

    boolean running() { return !"idle".equals(phase); }
    boolean paused() { return pausedAt != null; }
    long remaining(long now) { return running() ? Math.max(0, endsAt - (pausedAt != null ? pausedAt : now)) : 0; }
    long length() { return running() ? endsAt - startedAt - pausedMs : 0; }
    boolean isDue(long now) { return running() && pausedAt == null && now >= endsAt; }
    String label() { return target == null ? null : target.optString("label", null); }

    static FocusTimer idle(int done, String next, JSONObject target) {
        FocusTimer t = new FocusTimer();
        t.next = next;
        t.done = done;
        t.target = target;
        return t;
    }

    FocusTimer start(Settings s, long now) {
        FocusTimer t = new FocusTimer();
        t.phase = running() ? phase : next;
        t.next = t.phase;
        t.run = newRun();
        t.startedAt = now;
        t.endsAt = now + (!running() && custom != null ? custom * MIN : s.lengthMs(t.phase));
        t.done = done;
        t.target = target;
        return t;
    }

    /** Minutes of the next phase (idle) or of this one (running). */
    int nextLength(Settings s) { return running() ? (int) Math.round(length() / (double) MIN) : custom != null ? custom : s.minutes(next); }

    /** Idle only: the length of the next phase. */
    FocusTimer setLength(Settings s, int minutes) {
        if (running()) return this;
        int m = Math.max(1, Math.min(MAX_PHASE_MIN, minutes));
        FocusTimer t = copy();
        t.custom = m == s.minutes(next) ? null : m;
        return t;
    }

    /** Idle: the next length; running: moves the end, keeping at least a minute left and MAX_PHASE_MIN in all. */
    FocusTimer adjust(Settings s, int deltaMin, long now) {
        if (!running()) return setLength(s, nextLength(s) + deltaMin);
        FocusTimer t = copy();
        long floor = (pausedAt != null ? pausedAt : now) + MIN;
        long ceiling = startedAt + pausedMs + MAX_PHASE_MIN * MIN;
        t.endsAt = Math.max(floor, Math.min(ceiling, endsAt + deltaMin * MIN));
        return t;
    }

    FocusTimer withTarget(JSONObject target) {
        FocusTimer t = copy();
        t.target = target;
        return t;
    }

    FocusTimer pause(long now) {
        if (!running() || pausedAt != null) return this;
        FocusTimer t = copy();
        t.pausedAt = now;
        return t;
    }

    FocusTimer resume(long now) {
        if (!running() || pausedAt == null) return this;
        FocusTimer t = copy();
        long gap = now - pausedAt;
        t.endsAt += gap;
        t.pausedMs += gap;
        t.pausedAt = null;
        return t;
    }

    /** The time actually focused, without pauses. Null for breaks and very short focus. */
    private JSONObject entry(long now, boolean completed) {
        if (!"focus".equals(phase)) return null;
        long end = Math.min(pausedAt != null ? pausedAt : now, endsAt);
        long focusMs = end - startedAt - pausedMs;
        if (focusMs < MIN_LOGGED_MS) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("client_id", run);
            o.put("started_at", startedAt);
            o.put("ended_at", Math.min(now, endsAt));
            o.put("planned_seconds", Math.round(length() / 1000.0));
            o.put("focus_seconds", Math.round(focusMs / 1000.0));
            o.put("completed", completed);
            o.put("homework_id", target != null && !target.isNull("homework_id") ? target.opt("homework_id") : JSONObject.NULL);
            o.put("course_id", target != null && !target.isNull("course_id") ? target.opt("course_id") : JSONObject.NULL);
            o.put("label", target != null && !target.isNull("label") ? target.opt("label") : JSONObject.NULL);
            o.put("tags", target != null && target.optJSONArray("tags") != null ? target.optJSONArray("tags") : new JSONArray());
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    /** The phase ran out, or Skip (early): every longEvery-th focus is followed by a long break. */
    Result finish(Settings s, long now, boolean early) {
        if (!running()) return new Result(null, this);
        boolean fresh = now - endsAt < STALE_MS;
        if ("focus".equals(phase)) {
            JSONObject log = entry(now, !early);
            int d = done + (early ? 0 : 1);
            FocusTimer after = idle(d, !early && d >= s.longEvery ? "long" : "short", target);
            return new Result(log, s.autoBreaks && fresh ? after.start(s, now) : after);
        }
        FocusTimer after = idle("long".equals(phase) ? 0 : done, "focus", target);
        return new Result(null, s.autoFocus && fresh ? after.start(s, now) : after);
    }

    Result stop(long now) { return new Result(entry(now, false), idle(done, "focus", target)); }

    /** Skip while idle drops the break that was next. */
    FocusTimer skipIdle() { return "focus".equals(next) ? this : idle("long".equals(next) ? 0 : done, "focus", target); }

    private FocusTimer copy() {
        FocusTimer t = new FocusTimer();
        t.phase = phase; t.next = next; t.run = run; t.startedAt = startedAt; t.endsAt = endsAt;
        t.pausedMs = pausedMs; t.pausedAt = pausedAt; t.done = done; t.target = target; t.updatedAt = updatedAt;
        t.custom = custom;
        return t;
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static String newRun() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ------------------------------------------------------------------ JSON (same keys as the page)

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("phase", phase);
            o.put("next", next);
            o.put("done", done);
            o.put("target", target != null ? target : JSONObject.NULL);
            o.put("updatedAt", updatedAt);
            if (!running() && custom != null) o.put("custom", custom);
            if (running()) {
                o.put("run", run);
                o.put("startedAt", startedAt);
                o.put("endsAt", endsAt);
                o.put("pausedAt", pausedAt != null ? pausedAt : JSONObject.NULL);
                o.put("pausedMs", pausedMs);
            }
        } catch (JSONException ignored) { }
        return o;
    }

    static FocusTimer fromJson(JSONObject o) {
        FocusTimer t = new FocusTimer();
        if (o == null) return t;
        String p = o.optString("phase", "idle");
        boolean known = p.equals("idle") || p.equals("focus") || p.equals("short") || p.equals("long");
        if (!known || (!p.equals("idle") && (!o.has("endsAt") || !o.has("startedAt")))) return t;
        t.phase = p;
        t.next = o.optString("next", "focus");
        t.done = o.optInt("done", 0);
        t.target = o.optJSONObject("target");
        t.updatedAt = o.optLong("updatedAt", 0);
        if (!t.running() && o.optInt("custom", 0) > 0) t.custom = o.optInt("custom");
        if (t.running()) {
            t.run = o.optString("run", newRun());
            t.startedAt = o.optLong("startedAt");
            t.endsAt = o.optLong("endsAt");
            t.pausedMs = o.optLong("pausedMs", 0);
            t.pausedAt = o.isNull("pausedAt") ? null : o.optLong("pausedAt");
        }
        return t;
    }
}
