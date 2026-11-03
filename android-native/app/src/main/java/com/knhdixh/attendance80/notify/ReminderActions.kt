package com.knhdixh.attendance80.notify

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.logic.FocusTarget
import com.knhdixh.attendance80.net.AppData

/**
 * What a notification button does. The change is written to the device first ([NotifyStore.addAction]) and sent by
 * [ReminderActionWorker] when there is a network, so a tap in a tunnel is not lost.
 */
class ReminderActions(
    private val clock: Clock, private val store: NotifyStore, private val notifier: ReminderNotifier,
    private val flush: () -> Unit,
    /** The loaded snapshot: waits for the token and the cache, so a tap in a cold process sees the data. Null when signed out. */
    private val snapshot: suspend () -> AppData?,
    /** "Focus on this": starts the timer on that homework, with the loaded snapshot's focus settings and account. */
    private val focusOn: suspend (target: FocusTarget, data: AppData) -> Unit = { _, _ -> },
) {
    suspend fun handle(type: String, ref: Long, value: String?, number: Int?, owner: String, key: String) {
        notifier.cancel(key)
        val now = clock.now()
        when (type) {
            "checkin" -> {
                val status = value?.takeIf { it in setOf("present", "absent", "excused") } ?: return
                store.addAction(PendingAction(owner, "attendance", ref, status, at = now))
                if (status == "absent") offerCatchup(ref, owner)
            }
            "catchup" -> store.addAction(PendingAction(owner, "catchup", ref, tz = clock.zone().id, at = now))
            "habit-done" -> store.addAction(PendingAction(owner, "habit-done", ref, value, number, at = now))
            "homework-done" -> store.addAction(PendingAction(owner, "homework-done", ref, at = now))
            "homework-focus" -> { focus(ref, owner); return }
            else -> return
        }
        flush()
    }

    private suspend fun focus(homeworkId: Long, owner: String) {
        val data = snapshot() ?: return
        if (data.user.email?.lowercase().orEmpty() != owner) return
        val hw = data.homework.find { it.id == homeworkId } ?: return
        focusOn(FocusTarget(homework_id = hw.id, course_id = hw.course_id, label = hw.title), data)
    }

    /** Absent on a session that has ended also offers a catch-up, as the web does. */
    private suspend fun offerCatchup(eventId: Long, owner: String) {
        val data = snapshot()
        if (data?.user?.email?.lowercase().orEmpty() != owner) return
        val event = data?.events?.find { it.id == eventId } ?: return
        if (data.homework.any { it.kind == "catchup" && it.event_id == eventId }) return
        val name = data.courses.find { it.id == event.course_id }?.name ?: event.summary
        notifier.post(Reminder(
            key = "catchup:$eventId", kind = "catchup", at = clock.now(), title = "Marked absent: $name",
            body = "Add a catch-up to watch the recording or read the notes later.", route = "a80://session/$eventId", ref = eventId,
            actions = listOf(ReminderAction("Add catch-up", "catchup")),
        ), owner)
    }
}
