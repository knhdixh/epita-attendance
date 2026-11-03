package com.knhdixh.attendance80.notify

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.knhdixh.attendance80.App
import com.knhdixh.attendance80.net.Api
import com.knhdixh.attendance80.net.ApiError
import com.knhdixh.attendance80.net.CatchupRequest
import com.knhdixh.attendance80.net.HabitLogRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

/**
 * Sends the changes made from notification buttons, oldest first, for the signed-in account only. An item leaves the
 * queue after a 2xx or a 4xx (it would never succeed); no network or a 5xx leaves it for the next try.
 * @return true when nothing is left to retry.
 */
class ActionSender(private val store: NotifyStore, private val api: Api) {
    private val mutex = Mutex()

    suspend fun flush(account: String?, onSent: () -> Unit = {}): Boolean = mutex.withLock {
        val me = account?.lowercase() ?: return@withLock true
        // Another account's items can't be sent from this one, and it is gone: dropped.
        val all = store.pendingActions()
        store.removeActions(all.filter { it.owner != me })
        for (action in all.filter { it.owner == me }) {
            try {
                send(action)
                store.removeActions(listOf(action))
                onSent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.status in 400..499) store.removeActions(listOf(action)) else return@withLock false
            } catch (e: Exception) {
                return@withLock false
            }
        }
        true
    }

    private suspend fun send(a: PendingAction) {
        when (a.type) {
            "attendance" -> api.updateAttendance(a.ref, buildJsonObject { put("status", a.value?.let(::JsonPrimitive) ?: JsonNull) })
            "catchup" -> api.createCatchup(CatchupRequest(a.ref, a.tz ?: java.time.ZoneId.systemDefault().id))
            "homework-done" -> api.updateHomework(a.ref, buildJsonObject { put("done", true) })
            "habit-done" -> api.logHabit(a.ref, HabitLogRequest(a.value.orEmpty(), a.number ?: 1))
            else -> Unit
        }
    }
}

class ReminderActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        app.startData()
        val container = app.container
        val store = container.notifyStore ?: return Result.success()
        if (container.tokenStore.awaitToken() == null) return Result.success()
        val repo = container.dataRepository
        repo.start()
        if (repo.snapshot.value == null) repo.refresh()
        val account = repo.snapshot.value?.user?.email ?: return Result.retry()
        var sentAny = false
        val done = ActionSender(store, container.api).flush(account) { sentAny = true }
        if (sentAny) repo.refresh()
        return if (done) Result.success() else Result.retry()
    }
}

/** Network required, exponential backoff, one queue: asking again while one is waiting changes nothing. */
class WorkActionScheduler(private val context: Context) {
    fun requestFlush() {
        val request = OneTimeWorkRequestBuilder<ReminderActionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("reminder-actions", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
