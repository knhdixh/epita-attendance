package com.knhdixh.attendance80.widget

import com.knhdixh.attendance80.net.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The widget's data on disk, in the same excluded-from-backup folder as the account snapshot. Two writers (the data
 * repository's plan part and the focus controller's timer part) each replace only their own part.
 */
class WidgetStore(private val file: File) {
    private val _revision = MutableStateFlow(0L)

    /** Goes up on every write and on [touch]. The widget collects it inside its composition, so a Glance session that is still alive redraws. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Asks a live widget to redraw although nothing was written (the alarm: time has moved on). */
    fun touch() { _revision.value++ }

    @Synchronized fun read(): WidgetSnapshot =
        runCatching { ApiClient.json.decodeFromString<WidgetSnapshot>(file.readText()) }.getOrDefault(WidgetSnapshot())

    @Synchronized fun updatePlan(plan: WidgetSnapshot) = write(read().copy(
        signedIn = plan.signedIn, classes = plan.classes, todo = plan.todo, builtAt = plan.builtAt, owner = plan.owner))

    @Synchronized fun updateTimer(timer: WidgetTimer) = write(read().copy(timer = timer))

    private fun write(s: WidgetSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ApiClient.json.encodeToString(WidgetSnapshot.serializer(), s))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        touch()
    }
}

/** One store per process, so the two writers and the widget share its lock. */
object WidgetStores {
    @Volatile private var store: WidgetStore? = null
    fun get(context: android.content.Context): WidgetStore = store ?: synchronized(this) {
        store ?: WidgetStore(File(context.applicationContext.filesDir, "snapshots/widget.json")).also { store = it }
    }
}
