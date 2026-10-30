package com.knhdixh.attendance80.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

class PendingRouteViewModel(private val saved: SavedStateHandle) : ViewModel() {
    val pending = saved.getStateFlow<String?>("pendingRoute", null)
    fun accept(uri: String) { internalRoute(uri)?.let { saved["pendingRoute"] = it } }
    fun clear() { saved["pendingRoute"] = null }
}
