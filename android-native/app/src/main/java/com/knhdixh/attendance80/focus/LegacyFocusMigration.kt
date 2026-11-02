package com.knhdixh.attendance80.focus

import android.content.SharedPreferences
import com.knhdixh.attendance80.logic.FocusLogEntry
import com.knhdixh.attendance80.logic.FocusTimer
import com.knhdixh.attendance80.logic.cleanTasks
import com.knhdixh.attendance80.logic.MAX_TASKS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * First launch after the update over the TWA: the old app kept its timer and unsent sessions in
 * `SharedPreferences("focus")` (see FocusStore.java in `android/`). A running timer carries on, sessions it hadn't
 * handed to the page go to the [Outbox], tasks it hadn't saved go to the pending list, then the old file is cleared.
 * Safe to repeat: sessions are deduplicated by `client_id` and a timer is only adopted when none is running.
 */
class LegacyFocusMigration(
    private val prefs: SharedPreferences,
    private val store: FocusStore,
    private val outbox: OutboxScheduler,
) {
    data class Result(val timer: Boolean, val logs: Int, val tasks: Int)

    suspend fun run(): Result {
        if (store.migrated()) return Result(false, 0, 0)
        val timer = parseTimer(prefs.getString("timer", null))
        val logs = parseLogs(prefs.getString("logs", null))
        val tasks = parseTasks(prefs.getString("pendingTasks", null))
        // Everything is in our store before the old file is cleared, so a crash in between loses nothing.
        store.addLegacy(logs, tasks.take(MAX_TASKS))
        // Straight into the store, before the controller's first read: it needs no controller, so any entry point can run it.
        // The caller renders (alarm, notification) once it has loaded.
        val adopted = timer != null && !store.timer().running()
        if (adopted) { store.saveTimer(timer!!); store.setLegacyRun(timer.run) }
        store.markMigrated()
        prefs.edit().clear().apply()
        if (logs.isNotEmpty() || tasks.isNotEmpty()) outbox.requestFlush()
        return Result(adopted, logs.size, tasks.size)
    }

    private fun parseTimer(text: String?): FocusTimer? {
        val o = parse(text) as? JsonObject ?: return null
        val phase = o["phase"]?.jsonPrimitive?.content
        // The old reader's rule: a running timer needs both ends, or it is ignored.
        if (phase !in setOf("focus", "short", "long") || "startedAt" !in o || "endsAt" !in o) return null
        return runCatching { json.decodeFromJsonElement(FocusTimer.serializer(), o) }.getOrNull()?.takeIf { it.endsAt > 0 && it.run != null }
    }

    private fun parseLogs(text: String?): List<FocusLogEntry> =
        ((parse(text) as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { runCatching { json.decodeFromJsonElement(FocusLogEntry.serializer(), it) }.getOrNull() }

    private fun parseTasks(text: String?): List<String> =
        cleanTasks(((parse(text) as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }, MAX_TASKS)

    private fun parse(text: String?) = text?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

    companion object { private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true } }
}
