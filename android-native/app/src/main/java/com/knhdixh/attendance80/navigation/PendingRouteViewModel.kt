package com.knhdixh.attendance80.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

class PendingRouteViewModel(private val saved: SavedStateHandle) : ViewModel() {
    val pending = saved.getStateFlow<String?>("pendingRoute", null)
    fun accept(uri: String) { internalRoute(uri)?.let { saved["pendingRoute"] = it } }
    /** An internal route the app itself asks for (not from an outside link). */
    fun go(route: String) { saved["pendingRoute"] = route }
    fun clear() { saved["pendingRoute"] = null }
    fun clearIfMatches(route: String) { if (pending.value == route) clear() }
}
