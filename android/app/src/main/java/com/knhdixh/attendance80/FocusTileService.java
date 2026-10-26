package com.knhdixh.attendance80;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick-settings tile: starts, pauses or resumes the focus timer without opening the app. */
public class FocusTileService extends TileService {

    /** Asks the system to redraw the tile (it calls onStartListening). */
    static void refresh(Context c) {
        try { requestListeningState(c, new ComponentName(c, FocusTileService.class)); } catch (RuntimeException ignored) { }
    }

    @Override
    public void onStartListening() { draw(); }

    @Override
    public void onClick() {
        FocusController.toggle(this);
        draw();
    }

    private void draw() {
        Tile tile = getQsTile();
        if (tile == null) return;
        FocusStore store = new FocusStore(this);
        FocusTimer t = store.timer();
        long now = System.currentTimeMillis();
        String sub;
        if (!t.running()) {
            String phase = t.next;
            sub = ("focus".equals(phase) ? "Start " : "Break ") + store.settings().lengthMs(phase) / FocusTimer.MIN + " min";
            tile.setState(Tile.STATE_INACTIVE);
        } else if (t.paused()) {
            sub = "Paused · " + FocusNotifications.clock(t.remaining(now));
            tile.setState(Tile.STATE_INACTIVE);
        } else {
            sub = "Until " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(t.endsAt));
            tile.setState(Tile.STATE_ACTIVE);
        }
        tile.setLabel(t.running() ? FocusNotifications.phaseName(t.phase) : "Focus");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.setSubtitle(sub);
        else tile.setLabel(tile.getLabel() + " · " + sub);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_focus));
        tile.updateTile();
    }
}
