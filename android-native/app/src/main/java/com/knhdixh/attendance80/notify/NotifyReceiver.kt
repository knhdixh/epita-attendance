package com.knhdixh.attendance80.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.knhdixh.attendance80.App
import com.knhdixh.attendance80.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The reminder alarm, re-planning after a reboot, an update or a change of time or time zone, and the buttons on the
 * notifications. The buttons write the change to the device and hand the network call to WorkManager.
 */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val app = context.applicationContext as App
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                app.startData()
                val container = app.container
                when (action) {
                    ACTION_DO -> container.reminderActions?.handle(
                        type = intent.getStringExtra(EXTRA_TYPE).orEmpty(), ref = intent.getLongExtra(EXTRA_REF, -1),
                        value = intent.getStringExtra(EXTRA_VALUE), number = intent.getIntExtra(EXTRA_NUMBER, -1).takeIf { it >= 0 },
                        owner = intent.getStringExtra(EXTRA_OWNER).orEmpty(), key = intent.getStringExtra(EXTRA_KEY).orEmpty(),
                    ).also { container.reminders?.run(container.dataRepository.snapshot.value) } // a snooze sets the next alarm at once
                    else -> {
                        if (action == Intent.ACTION_TIMEZONE_CHANGED || action == Intent.ACTION_TIME_CHANGED) {
                            container.dataRepository.start()
                            container.rebuildWidgetPlan()
                        }
                        replan(container)
                    }
                }
            } finally { pending.finish() }
        }
    }

    private suspend fun replan(container: AppContainer) {
        if (container.tokenStore.awaitToken() == null) return
        container.dataRepository.start()
        // A session marked on the website since the last refresh would still get "Were you at …?": refresh first when the cache is old.
        val repo = container.dataRepository
        if (isStale(repo.lastSynced.value, container.clock.now())) kotlinx.coroutines.withTimeoutOrNull(REFRESH_BUDGET_MS) { repo.refresh() }
        container.reminders?.run(repo.snapshot.value)
    }

    companion object {
        /** A refresh before planning if the cache is older than this. */
        const val STALE_AFTER_MS = 10 * 60_000L
        /** The receiver has about ten seconds: leave the rest for planning and posting. */
        const val REFRESH_BUDGET_MS = 5_000L
        fun isStale(lastSynced: Long?, now: Long) = lastSynced == null || now - lastSynced > STALE_AFTER_MS
        const val ACTION_FIRE = "com.knhdixh.attendance80.REMINDER_FIRE"
        const val ACTION_DO = "com.knhdixh.attendance80.REMINDER_DO"
        const val EXTRA_TYPE = "type"
        const val EXTRA_VALUE = "value"
        const val EXTRA_REF = "ref"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_KEY = "key"
    }
}
