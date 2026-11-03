package com.knhdixh.attendance80.notify

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.net.AppData

/**
 * What a notification button does. The change is written to the device first ([NotifyStore.addAction]) and sent by
 * [ReminderActionWorker] when there is a network, so a tap in a tunnel is not lost.
 */
class ReminderActions(
    private val clock: Clock, private val store: NotifyStore, private val notifier: ReminderNotifier,
    private val flush: () -> Unit, private val snapshot: () -> AppData?,
    /** "Focus on this": starts the timer on that homework (the controller lives in the app container). */
    private val focusOn: suspend (homeworkId: Long) -> Unit = {},
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
            "homework-focus" -> { focusOn(ref); return }
            else -> return
        }
        flush()
    }

    /** Absent on a session that has ended also offers a catch-up, as the web does. */
    private fun offerCatchup(eventId: Long, owner: String) {
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
