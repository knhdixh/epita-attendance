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
                    )
                    else -> replan(container)
                }
            } finally { pending.finish() }
        }
    }

    private suspend fun replan(container: AppContainer) {
        if (container.tokenStore.awaitToken() == null) return
        container.dataRepository.start()
        container.reminders?.run(container.dataRepository.snapshot.value)
    }

    companion object {
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
