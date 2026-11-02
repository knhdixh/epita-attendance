package com.knhdixh.attendance80.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.knhdixh.attendance80.App
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        app.startData()
        val container = app.container
        if (container.tokenStore.awaitToken() == null) return Result.success()
        container.dataRepository.start()
        container.focusOutbox?.flush()
        return if (container.dataRepository.refresh()) Result.success() else Result.retry()
    }
}

fun refreshRequest(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<RefreshWorker>(60, TimeUnit.MINUTES)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .build()

fun scheduleRefresh(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "snapshot-refresh", ExistingPeriodicWorkPolicy.KEEP, refreshRequest(),
    )
}
