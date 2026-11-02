package com.knhdixh.attendance80.focus

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.knhdixh.attendance80.App
import com.knhdixh.attendance80.R
import com.knhdixh.attendance80.logic.FocusSettings
import com.knhdixh.attendance80.logic.FocusTimer
import com.knhdixh.attendance80.logic.remaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.knhdixh.attendance80.widget.WidgetStore
import com.knhdixh.attendance80.widget.buildWidgetTimer
import com.knhdixh.attendance80.widget.redrawWidget
import java.text.DateFormat
import java.util.Date

/** What the tile says for a timer: its label, subtitle and whether it is lit. A function so a test can check it. */
data class TileState(val label: String, val subtitle: String, val active: Boolean)

fun tileState(t: FocusTimer, settings: FocusSettings, now: Long, time: (Long) -> String): TileState = when {
    !t.running() -> TileState("Focus", (if (t.next == "focus") "Start " else "Break ") + "${t.custom ?: settings.minutes(t.next)} min", false)
    t.paused() -> TileState(phaseName(t.phase), "Paused · ${clock(remaining(t, now))}", false)
    else -> TileState(phaseName(t.phase), "Until ${time(t.endsAt)}", true)
}

/** Quick-settings tile: starts, pauses or resumes the focus timer without opening the app. Long-press opens the sheet. */
class FocusTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartListening() { draw() }

    override fun onClick() {
        val controller = (application as App).container.focusController ?: return
        scope.launch { controller.toggle(); draw() }
    }

    private fun draw() {
        val controller = (application as App).container.focusController ?: return
        scope.launch {
            controller.load()
            val tile = qsTile ?: return@launch
            val s = tileState(controller.timer.value, controller.settings.value, System.currentTimeMillis()) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
            tile.state = if (s.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = s.label
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = s.subtitle else tile.label = "${s.label} · ${s.subtitle}"
            tile.icon = Icon.createWithResource(this@FocusTileService, R.drawable.ic_stat_focus)
            tile.updateTile()
        }
    }

    companion object {
        /** Asks the system to redraw the tile (it calls onStartListening). */
        fun refresh(context: Context) {
            try { requestListeningState(context, ComponentName(context, FocusTileService::class.java)) } catch (_: RuntimeException) { }
        }
    }
}

class AndroidFocusSurfaces(private val context: Context, private val widget: WidgetStore) : FocusSurfaces {
    override fun timerChanged(timer: FocusTimer, settings: FocusSettings, now: Long) { widget.updateTimer(buildWidgetTimer(timer, settings, now)) }
    override fun refresh() { FocusTileService.refresh(context); redrawWidget(context) }
}
