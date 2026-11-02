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

    /** @return true when nothing is left to send. */
    suspend fun flush(): Boolean = mutex.withLock {
        while (true) {
            val next = store.outbox().firstOrNull() ?: break
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
        return if (outbox.flush()) Result.success() else Result.retry()
    }
}

/** Network required, exponential backoff, one queue: asking again while one is waiting changes nothing. */
class WorkOutboxScheduler(private val context: Context) : OutboxScheduler {
    override fun requestFlush() {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("focus-outbox", ExistingWorkPolicy.KEEP, request)
    }
}
