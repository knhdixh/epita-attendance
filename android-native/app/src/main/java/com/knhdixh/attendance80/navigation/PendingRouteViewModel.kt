package com.knhdixh.attendance80.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /**
     * Reads a calendar file or shared text off the main thread and delivers it. It runs in this view model's scope, so a
     * rotation during a slow provider (a file Drive has not downloaded yet) does not lose the share.
     */
    fun receive(io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO, read: () -> com.knhdixh.attendance80.share.Incoming?) {
        viewModelScope.launch {
            when (val incoming = withContext(io) { read() }) {
                is com.knhdixh.attendance80.share.Incoming.CalendarFile -> shareFile(incoming)
                is com.knhdixh.attendance80.share.Incoming.SharedText -> shareText(incoming.text)
                null -> Unit
            }
        }
    }
    fun clearIfMatches(route: String) { if (pending.value == route) clear() }
}
