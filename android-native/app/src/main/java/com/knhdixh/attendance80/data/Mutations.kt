package com.knhdixh.attendance80.data

import kotlinx.serialization.json.*
import java.util.UUID

/**
 * The changes the app can queue ([DataRepository.enqueue]). One function per server call, so a screen says what it
 * means and the path, body and kind (what [LocalEffects] does with it) stay together. Ids may be temporary (negative).
 */
object Mutations {
    private fun cid() = "a" + UUID.randomUUID().toString().replace("-", "").take(28)
    private fun m(kind: String, method: String, path: String, body: JsonObject = JsonObject(emptyMap()), about: Long? = null, reload: Boolean = false, holdMs: Long = 0) =
        QueuedMutation(cid(), kind, method, path, body, about = about, reload = reload, holdMs = holdMs)

    // tasks (homework rows)
    fun createTask(body: JsonObject) = m("task.create", "POST", "api/homework", body)
    fun updateTask(id: Long, body: JsonObject) = m("task.update", "PATCH", "api/homework/$id", body)
    /** [holdMs]: how long an Undo can still take it back before it is sent. */
    fun deleteTask(id: Long, holdMs: Long = 0) = m("task.delete", "DELETE", "api/homework/$id", holdMs = holdMs)
    /** [occurrence]: the due date the screen saw. A repeating task is never `done`, so this is what makes a retry move it on once. */
    fun completeTask(id: Long, occurrence: String?, reload: Boolean = false) =
        m("task.complete", "POST", "api/homework/$id/complete", buildJsonObject { occurrence?.let { put("occurrence", it) } }, reload = reload)
    /** A repeating task goes back to the date it was completed for, which only the server knows, so it reloads. */
    fun uncompleteTask(id: Long, reload: Boolean = false) = m("task.uncomplete", "POST", "api/homework/$id/uncomplete", reload = reload)
    fun moveTasks(body: JsonObject) = m("task.move", "POST", "api/homework/move", body, reload = true)

    // projects, sections, labels, filters
    private val paths = mapOf("project" to "api/projects", "section" to "api/sections", "label" to "api/labels", "filter" to "api/filters")
    fun create(noun: String, body: JsonObject) = m("$noun.create", "POST", paths.getValue(noun), body)
    fun update(noun: String, id: Long, body: JsonObject) = m("$noun.update", "PATCH", "${paths.getValue(noun)}/$id", body)
    /** Deleting a project, a section or a label changes many tasks: reload afterwards. */
    fun delete(noun: String, id: Long) = m("$noun.delete", "DELETE", "${paths.getValue(noun)}/$id", reload = noun != "filter")
    fun reorder(noun: String, ids: List<Long>) = m("$noun.reorder", "POST", "${paths.getValue(noun)}/reorder", buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) })

    // comments and reminders
    fun createComment(taskId: Long, body: String) = m("comment.create", "POST", "api/homework/$taskId/comments", buildJsonObject { put("body", body) }, about = taskId)
    fun updateComment(id: Long, body: String) = m("comment.update", "PATCH", "api/comments/$id", buildJsonObject { put("body", body) })
    fun deleteComment(id: Long, taskId: Long) = m("comment.delete", "DELETE", "api/comments/$id", about = taskId)
    fun createReminder(taskId: Long, body: JsonObject) = m("reminder.create", "POST", "api/homework/$taskId/reminders", body, about = taskId)
    fun updateReminder(id: Long, body: JsonObject) = m("reminder.update", "PATCH", "api/reminders/$id", body)
    fun deleteReminder(id: Long) = m("reminder.delete", "DELETE", "api/reminders/$id")

    // habits
    fun createHabit(body: JsonObject) = m("habit.create", "POST", "api/habits", body)
    fun updateHabit(id: Long, body: JsonObject) = m("habit.update", "PATCH", "api/habits/$id", body)
    fun deleteHabit(id: Long) = m("habit.delete", "DELETE", "api/habits/$id")
    fun logHabit(id: Long, day: String, count: Int) = m("habit.log", "PUT", "api/habits/$id/log", buildJsonObject { put("day", day); put("count", count) })
    fun orderHabits(ids: List<Long>) = m("habit.order", "POST", "api/habits/order", buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) })

    // what notification buttons do
    fun setAttendance(eventId: Long, status: String?) = m("attendance.set", "PATCH", "api/attendance/$eventId", buildJsonObject { put("status", status?.let(::JsonPrimitive) ?: JsonNull) })
    fun addCatchup(eventId: Long, tz: String) = m("catchup.add", "POST", "api/catchups", buildJsonObject { put("event_id", eventId); put("tz", tz) }, reload = true)
}
