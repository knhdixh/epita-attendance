package com.knhdixh.attendance80.notify

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.net.AppData
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One inexact alarm, always for the next reminder (and the next midnight, to look a day further). */
interface ReminderAlarm { fun schedule(at: Long?) }

interface ReminderNotifier {
    /** False while the notification permission is missing: nothing is posted, and nothing is marked as posted. */
    fun canPost(): Boolean
    fun post(reminder: Reminder, owner: String)
    fun cancel(key: String)
    fun cancelAll()
}

/**
 * Posts what is due and sets the alarm for what comes next. Run it whenever the snapshot or a switch changes, when the
 * alarm fires, and after a reboot or a change of time or time zone. It is safe to run twice: a posted reminder is
 * remembered (by account and key), so it never goes out again.
 */
class ReminderEngine(
    private val clock: Clock, private val store: NotifyStore, private val alarm: ReminderAlarm, private val notifier: ReminderNotifier,
) {
    private val mutex = Mutex()

    suspend fun run(data: AppData?) = mutex.withLock {
        if (data == null) { alarm.schedule(null); return@withLock }
        val owner = data.user.email?.lowercase().orEmpty()
        val now = clock.now()
        val zone = clock.zone()
        val sent = store.sent(now)
        val plan = planReminders(data, store.choices.first(), now, zone, store.snoozes(owner, now)).filter { "$owner|${it.key}" !in sent }
        val due = plan.filter { it.at <= now }
        if (due.isNotEmpty() && notifier.canPost()) {
            // A newer snapshot can cancel this run (collectLatest): finish posting and recording together, or the next run would alert again.
            withContext(NonCancellable) {
                due.forEach { notifier.post(it, owner) }
                store.markSent(due.map { "$owner|${it.key}" }, now)
            }
        }
        val rollover = startOfNextDay(now, zone)
        alarm.schedule(minOf(plan.filter { it.at > now }.minOfOrNull { it.at } ?: rollover, rollover))
    }

    /**
     * What the snapshot collector calls. A null snapshot is the cache not loaded yet on a cold start, not a sign-out:
     * it only cancels the alarm once [isSignedOut] (the token store, after it has loaded) says so.
     */
    suspend fun onSnapshot(data: AppData?, isSignedOut: suspend () -> Boolean) {
        if (data != null) run(data) else if (isSignedOut()) run(null)
    }

    /** Signed out, however it happened: no alarm, and nothing of the account's left in the shade. */
    suspend fun signedOut() = mutex.withLock {
        alarm.schedule(null)
        notifier.cancelAll()
    }
}
