package com.knhdixh.attendance80.focus

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.knhdixh.attendance80.App
import com.knhdixh.attendance80.logic.FocusLogEntry
import com.knhdixh.attendance80.net.Api
import com.knhdixh.attendance80.net.ApiError
import com.knhdixh.attendance80.net.FocusLogRequest
import com.knhdixh.attendance80.net.FocusSessionDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

fun FocusLogEntry.toRequest() = FocusLogRequest(
    client_id = client_id, homework_id = homework_id, course_id = course_id, label = label,
    started_at = started_at, ended_at = ended_at, planned_seconds = planned_seconds, focus_seconds = focus_seconds,
    completed = completed, tags = tags,
)

/**
 * Finished focus sessions waiting to be recorded, oldest first. The server ignores a repeated `client_id`, so
 * sending one twice is safe. An entry leaves the queue after a 2xx, or after a 400 (it will never succeed, so it is
 * logged and dropped). Anything else (no network, 401, 5xx) leaves it for the next try.
 */
class Outbox(
    private val store: FocusStore,
    private val api: Api,
    private val recorded: suspend (FocusSessionDto) -> Unit = {},
    private val dropped: (FocusLogEntry, String) -> Unit = { e, why -> Log.w("FocusOutbox", "Dropped ${e.client_id}: $why") },
) {
    private val mutex = Mutex()

    /**
     * Sends the signed-in account's sessions only. Another account's stay queued for when it signs in again, and
     * nothing is sent while signed out (the account is read again for every entry, so a sign-out stops the loop).
     * @return true when nothing is left to send.
     */
    suspend fun flush(): Boolean = mutex.withLock {
        while (true) {
            val account = store.account() ?: return@withLock true
            val next = store.outbox(account).firstOrNull() ?: break
            try {
                recorded(api.logFocus(next.toRequest()))
                store.removeLogs(setOf(next.client_id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status != 400) return@withLock false
                dropped(next, e.message)
                store.removeLogs(setOf(next.client_id))
            } catch (e: Exception) {
                return@withLock false
            }
        }
        true
    }
}

class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        app.startData()
        val container = app.container
        if (container.tokenStore.awaitToken() == null) return Result.success()
        container.dataRepository.start()
        val outbox = container.focusOutbox ?: return Result.success()
        val logsDone = outbox.flush()
        val tasksDone = (container as? com.knhdixh.attendance80.DefaultAppContainer)?.let { syncPendingTasks(it) } ?: true
        val changesDone = container.dataRepository.flushQueue()
        return if (logsDone && tasksDone && changesDone) Result.success() else Result.retry()
    }
}

/** Network required, exponential backoff, one queue. A request made while a worker is running (past its flush loop) runs again after it, so nothing waits for the next hourly refresh. */
class WorkOutboxScheduler(private val context: Context) : OutboxScheduler {
    override fun requestFlush() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("focus-outbox", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/** Tasks added while offline go to the account's focus settings, newest first as on the web. @return true when none are left. */
internal suspend fun syncPendingTasks(container: com.knhdixh.attendance80.DefaultAppContainer): Boolean =
    syncPendingTasks(container.focusStore, container.dataRepository) { container.tokenStore.currentToken() }

internal suspend fun syncPendingTasks(store: FocusStore, repo: com.knhdixh.attendance80.data.DataRepository, token: () -> String?): Boolean {
    val account = store.account() ?: return true
    val pending = store.pendingTasks()
    if (pending.isEmpty()) return true
    val owner = token() ?: return true
    val data = repo.snapshot.value ?: return false
    // The snapshot must be this account's, or its tasks would be merged into someone else's list.
    if (data.user.email?.lowercase() != account) return false
    val merged = com.knhdixh.attendance80.logic.cleanTasks(pending + data.focus.settings.tasks, com.knhdixh.attendance80.logic.MAX_TASKS)
    val body = kotlinx.serialization.json.buildJsonObject {
        put("tasks", kotlinx.serialization.json.JsonArray(merged.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
    val saved = repo.saveFocusSettingsCanonical(owner, body) ?: return false
    if (store.account() != account) return true // signed out meanwhile: nothing to tidy
    // Only what we sent: a task typed in the meantime stays pending.
    store.savePendingTasks(store.pendingTasks().filter { t -> saved.tasks.none { it.equals(t, ignoreCase = true) } })
    return true
}
