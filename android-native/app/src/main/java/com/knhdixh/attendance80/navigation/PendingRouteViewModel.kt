package com.knhdixh.attendance80.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.asStateFlow

class PendingRouteViewModel(private val saved: SavedStateHandle) : ViewModel() {
    val pending = saved.getStateFlow<String?>("pendingRoute", null)
    fun accept(uri: String) { internalRoute(uri)?.let { saved["pendingRoute"] = it } }
    /** An internal route the app itself asks for (not from an outside link). */
    fun go(route: String) { saved["pendingRoute"] = route }
    fun clear() { saved["pendingRoute"] = null }

    /** A calendar file another app opened or shared. Kept in memory (it can be megabytes): if the process dies, the user shares it again. */
    private val file = kotlinx.coroutines.flow.MutableStateFlow<com.knhdixh.attendance80.share.Incoming.CalendarFile?>(null)
    val sharedFile = file.asStateFlow()
    fun shareFile(f: com.knhdixh.attendance80.share.Incoming.CalendarFile) { file.value = f; go("settings") }
    fun fileHandled() { file.value = null }

    /** Text shared into the app, for the add-homework form. */
    val sharedText = saved.getStateFlow<String?>("sharedText", null)
    fun shareText(text: String) { saved["sharedText"] = text; go("homework/new") }
    fun textHandled() { saved["sharedText"] = null }
    fun clearIfMatches(route: String) { if (pending.value == route) clear() }
}
