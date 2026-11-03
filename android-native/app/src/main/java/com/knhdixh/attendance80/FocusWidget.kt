package com.knhdixh.attendance80

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.knhdixh.attendance80.widget.FocusGlanceWidget

/**
 * The widget provider. The name is the TWA's (`com.knhdixh.attendance80.FocusWidget`) on purpose: an update over the TWA
 * keeps a placed widget only if its provider component still exists. Receivers can't be aliased, so don't rename or move it.
 */
class FocusWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FocusGlanceWidget()
}
