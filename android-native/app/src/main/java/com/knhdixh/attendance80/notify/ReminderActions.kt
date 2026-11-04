package com.knhdixh.attendance80.notify

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.logic.FocusTarget
import com.knhdixh.attendance80.data.Mutations
import com.knhdixh.attendance80.data.QueuedMutation
import com.knhdixh.attendance80.net.AppData

/**
 * What a notification button does. The change goes into the outbox (the same one as every other change: it is applied to the
 * snapshot, kept over a restart and sent in order when there is a network), so a tap in a tunnel is not lost.
 */
class ReminderActions(
    private val clock: Clock, private val notifier: ReminderNotifier,
    /** The loaded snapshot: waits for the token and the cache, so a tap in a cold process sees the data. Null when signed out. */
    private val snapshot: suspend () -> AppData?,
    /** Queues a change for the account [String] (an email); false when that account isn't the signed-in one. */
    private val enqueue: suspend (QueuedMutation, String) -> Boolean,
    /** "Focus on this": starts the timer on that homework, with the loaded snapshot's focus settings and account. */
    private val focusOn: suspend (target: FocusTarget, data: AppData) -> Unit = { _, _ -> },
    /** Remembers that the reminders of task [Long] go off again as [TaskSnooze] (this device only). */
    private val snooze: suspend (owner: String, taskId: Long, snooze: TaskSnooze) -> Unit = { _, _, _ -> },
) {
    suspend fun handle(type: String, ref: Long, value: String?, number: Int?, owner: String, key: String) {
        notifier.cancel(key)
        when (type) {
            "checkin" -> {
                val status = value?.takeIf { it in setOf("present", "absent", "excused") } ?: return
                enqueue(Mutations.setAttendance(ref, status), owner)
                if (status == "absent") offerCatchup(ref, owner)
            }
            "catchup" -> enqueue(Mutations.addCatchup(ref, clock.zone().id), owner)
            "habit-done" -> enqueue(Mutations.logHabit(ref, value.orEmpty(), number ?: 1), owner)
            "homework-done" -> complete(ref, value, owner)
            "homework-focus" -> { focus(ref, owner); return }
            "snooze-menu" -> { offerSnooze(ref, owner); return }
            "snooze" -> {
                val until = Snooze.until(value, clock.now(), clock.zone()) ?: return
                snooze(owner, ref, TaskSnooze(until, Snooze.dueOf(value).orEmpty()))
            }
            else -> return
        }
    }

    /** The three snooze lengths, which don't fit beside Done on one notification (Android shows three buttons). */
    /**
     * Ticks a task off, from a notification button or a widget. [occurrence] is the due date the surface drew: a repeating task
     * whose date has moved on since is left alone (the server checks it too). A task with subtasks or a repeat reloads afterwards.
     */
    suspend fun complete(ref: Long, occurrence: String?, owner: String): Boolean {
        val rows = snapshot()?.homework.orEmpty()
        val row = rows.find { it.id == ref }
        val moves = row?.recurrence != null || rows.any { it.parent_id == ref }
        return enqueue(Mutations.completeTask(ref, occurrence?.takeIf { it.isNotEmpty() }, reload = moves), owner)
    }

    private suspend fun offerSnooze(taskId: Long, owner: String) {
        val data = snapshot() ?: return
        if (data.user.email?.lowercase().orEmpty() != owner) return
        val hw = data.homework.find { it.id == taskId && !it.done } ?: return
        val due = hw.due_date
        notifier.post(Reminder(
            key = "snooze-menu:$taskId", kind = Kinds.TASK, at = clock.now(), title = hw.title, body = "Remind me again in …", route = "a80://homework/$taskId", ref = taskId,
            actions = listOf(ReminderAction("10 min", "snooze", Snooze.value(Snooze.TEN_MIN, due)), ReminderAction("1 hour", "snooze", Snooze.value(Snooze.HOUR, due)), ReminderAction("Tomorrow", "snooze", Snooze.value(Snooze.TOMORROW, due))),
        ), owner)
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
