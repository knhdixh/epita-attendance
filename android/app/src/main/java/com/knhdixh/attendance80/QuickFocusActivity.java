package com.knhdixh.attendance80;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The quick-focus sheet, opened from the widget (like Todoist's quick add) and by long-pressing the tile: pick what
 * you're working on or add a new task, toggle tags, set the time (or move the end of a running phase), and start.
 * Homework comes from the plan the page last sent; tasks added here are sent to the page, which keeps them on
 * the account.
 */
public class QuickFocusActivity extends Activity {
    private static final int[] PRESETS = {15, 20, 25, 30, 45, 50, 60, 90};
    private static final int TAGS_PER_SESSION = 5;

    private FocusStore store;
    private FocusTimer timer;
    private FocusTimer.Settings settings;
    private final List<JSONObject> targets = new ArrayList<>();   // one per radio button, null = nothing
    private final Set<String> tags = new HashSet<>();
    private int minutes;      // idle: the length to start with
    private int delta;        // running: minutes added to (or taken from) the end

    private RadioGroup group;
    private TextView minutesView;
    private LinearLayout presetsRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.quick_focus);
        getWindow().setGravity(Gravity.BOTTOM);
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        store = new FocusStore(this);
        timer = store.timer();
        settings = store.settings();
        minutes = timer.nextLength(settings);
        JSONArray current = timer.target != null ? timer.target.optJSONArray("tags") : null;
        for (int i = 0; current != null && i < current.length(); i++) tags.add(current.optString(i));

        group = findViewById(R.id.qf_targets);
        minutesView = findViewById(R.id.qf_minutes);
        presetsRow = findViewById(R.id.qf_presets);

        fillTargets();
        fillTags();
        fillTime();
        wireButtons();
    }

    // ------------------------------------------------------------------ what you're working on

    private void fillTargets() {
        group.removeAllViews();
        targets.clear();
        addTarget("Nothing in particular", null);
        JSONObject snap = store.snapshot();
        JSONArray homework = snap != null ? snap.optJSONArray("homework") : null;
        for (int i = 0; homework != null && i < homework.length(); i++) {
            JSONObject h = homework.optJSONObject(i);
            if (h == null) continue;
            String course = h.optString("course", "");
            addTarget(h.optString("title") + (course.isEmpty() ? "" : "  ·  " + course),
                    target(h.opt("id"), h.isNull("course_id") ? null : h.opt("course_id"), h.optString("title")));
        }
        for (String task : store.tasks()) addTarget(task, target(null, null, task));
        selectCurrent();
        // Long lists scroll instead of pushing the buttons off screen.
        ScrollView scroll = findViewById(R.id.qf_scroll);
        int max = (int) (getResources().getDisplayMetrics().density * 220);
        scroll.post(() -> {
            if (scroll.getHeight() > max) { scroll.getLayoutParams().height = max; scroll.requestLayout(); }
        });
    }

    private void addTarget(String label, JSONObject target) {
        RadioButton b = new RadioButton(this);
        b.setId(View.generateViewId());
        b.setText(label);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        group.addView(b);
        targets.add(target);
    }

    private static JSONObject target(Object homeworkId, Object courseId, String label) {
        JSONObject t = new JSONObject();
        try {
            t.put("homework_id", homeworkId != null ? homeworkId : JSONObject.NULL);
            t.put("course_id", courseId != null ? courseId : JSONObject.NULL);
            t.put("label", label != null ? label : JSONObject.NULL);
        } catch (JSONException ignored) { }
        return t;
    }

    private void selectCurrent() {
        JSONObject cur = timer.target;
        int pick = 0;
        for (int i = 1; cur != null && i < targets.size(); i++) {
            JSONObject t = targets.get(i);
            boolean sameHw = !cur.isNull("homework_id") && cur.opt("homework_id").toString().equals(String.valueOf(t.opt("homework_id")));
            boolean sameTask = cur.isNull("homework_id") && t.isNull("homework_id") && cur.optString("label").equals(t.optString("label"));
            if (sameHw || sameTask) { pick = i; break; }
        }
        group.check(group.getChildAt(pick).getId());
    }

    private void addTask() {
        EditText input = findViewById(R.id.qf_new_task);
        String label = input.getText().toString().trim().replaceAll("\\s+", " ");
        if (label.isEmpty()) return;
        if (label.length() > 80) label = label.substring(0, 80);
        store.addPendingTask(label);
        Bridge.sendTasks(this);
        input.setText("");
        // Select it: rebuild with the timer pointing at it.
        timer = timer.withTarget(target(null, null, label));
        fillTargets();
    }

    private JSONObject chosenTarget() {
        int index = group.indexOfChild(group.findViewById(group.getCheckedRadioButtonId()));
        JSONObject base = index > 0 ? targets.get(index) : null;
        if (base == null && tags.isEmpty()) return null;
        JSONObject t = base != null ? base : target(null, null, null);
        try { t.put("tags", new JSONArray(new ArrayList<>(tags))); } catch (JSONException ignored) { }
        return t;
    }

    // ------------------------------------------------------------------ tags

    private void fillTags() {
        LinearLayout row = findViewById(R.id.qf_tags);
        List<String> all = store.tags();
        for (String t : tags) if (!all.contains(t)) all.add(t);
        int vis = all.isEmpty() ? View.GONE : View.VISIBLE;
        findViewById(R.id.qf_tags_label).setVisibility(vis);
        findViewById(R.id.qf_tags_scroll).setVisibility(vis);
        for (String tag : all) {
            CheckBox c = new CheckBox(this);
            c.setText("#" + tag);
            c.setChecked(tags.contains(tag));
            c.setPadding(c.getPaddingLeft(), 0, (int) (getResources().getDisplayMetrics().density * 12), 0);
            c.setOnCheckedChangeListener((v, on) -> {
                if (on && tags.size() >= TAGS_PER_SESSION) { v.setChecked(false); return; }
                if (on) tags.add(tag); else tags.remove(tag);
            });
            row.addView(c);
        }
    }

    // ------------------------------------------------------------------ time

    private void fillTime() {
        boolean on = timer.running();
        ((TextView) findViewById(R.id.qf_time_label)).setText(on ? "Time left" : "Time");
        for (int m : PRESETS) {
            if (on) break;
            Button b = new Button(this, null, android.R.attr.buttonBarButtonStyle);
            b.setText(m + " min");
            b.setTag(m);
            b.setOnClickListener(v -> { minutes = (int) v.getTag(); showTime(); });
            presetsRow.addView(b);
        }
        findViewById(R.id.qf_minus).setOnClickListener(v -> step(-5));
        findViewById(R.id.qf_minus1).setOnClickListener(v -> step(-1));
        findViewById(R.id.qf_plus1).setOnClickListener(v -> step(1));
        findViewById(R.id.qf_plus).setOnClickListener(v -> step(5));
        findViewById(R.id.qf_presets_scroll).setVisibility(on ? View.GONE : View.VISIBLE);
        showTime();
    }

    private void step(int d) {
        if (timer.running()) {
            long now = System.currentTimeMillis();
            long left = timer.remaining(now) + (delta + d) * FocusTimer.MIN;
            if (left < FocusTimer.MIN && d < 0) return;
            delta += d;
        } else {
            minutes = Math.max(1, Math.min(FocusTimer.MAX_PHASE_MIN, minutes + d));
        }
        showTime();
    }

    private void showTime() {
        long now = System.currentTimeMillis();
        if (timer.running()) {
            long left = Math.max(FocusTimer.MIN, timer.remaining(now) + delta * FocusTimer.MIN);
            minutesView.setText(FocusNotifications.clock(left) + (delta != 0 ? (delta > 0 ? "  (+" : "  (") + delta + ")" : ""));
        } else {
            minutesView.setText(minutes + " min");
        }
        for (int i = 0; i < presetsRow.getChildCount(); i++) {
            View b = presetsRow.getChildAt(i);
            b.setAlpha(((int) b.getTag()) == minutes ? 1f : 0.6f);
        }
    }

    // ------------------------------------------------------------------ buttons

    private void wireButtons() {
        boolean on = timer.running();
        String phase = on ? timer.phase : timer.next;
        ((TextView) findViewById(R.id.qf_title)).setText(FocusNotifications.phaseName(phase));
        TextView status = findViewById(R.id.qf_status);
        status.setText(!on ? "Ready" : timer.paused() ? "Paused" : FocusNotifications.clock(timer.remaining(System.currentTimeMillis())) + " left");

        Button go = findViewById(R.id.qf_go), pause = findViewById(R.id.qf_pause), stop = findViewById(R.id.qf_stop);
        go.setText(on ? "Save" : "focus".equals(phase) ? "Start focus" : "Start break");
        pause.setVisibility(on ? View.VISIBLE : View.GONE);
        stop.setVisibility(on ? View.VISIBLE : View.GONE);
        pause.setText(timer.paused() ? "Resume" : "Pause");

        findViewById(R.id.qf_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.qf_add).setOnClickListener(v -> addTask());
        ((EditText) findViewById(R.id.qf_new_task)).setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_DONE) { addTask(); return true; }
            return false;
        });
        pause.setOnClickListener(v -> { save(false); FocusController.toggle(this); finish(); });
        stop.setOnClickListener(v -> { FocusController.stop(this); finish(); });
        go.setOnClickListener(v -> { save(!on); finish(); });
    }

    /** Applies the chosen target, tags and time; starts the timer when {@code start}. */
    private void save(boolean start) {
        long now = System.currentTimeMillis();
        FocusTimer t = store.timer().withTarget(chosenTarget());
        if (t.running()) {
            if (delta != 0) t = t.adjust(settings, delta, now);
        } else {
            t = t.setLength(settings, minutes);
            if (start) t = t.start(settings, now);
        }
        FocusController.apply(this, t, null, false);
        if (start) PermissionActivity.askOnce(this);
    }
}
