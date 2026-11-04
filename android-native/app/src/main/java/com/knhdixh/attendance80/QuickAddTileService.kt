package com.knhdixh.attendance80

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The link the tile, the widget and the launcher shortcut open: quick add over whatever the app was showing. */
fun quickAddIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("a80://add"), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Quick-settings tile: tap to add a task (opens quick add, collapsing the shade). It holds no state, so it is never lit. */
class QuickAddTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = "Add task"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_add)
        tile.updateTile()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = quickAddIntent(this)
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
