package com.knhdixh.attendance80.ui.tasks

import com.knhdixh.attendance80.net.CommentDto
import com.knhdixh.attendance80.net.CompletionDto
import kotlinx.serialization.json.JsonObject

/** What the task screens can change. Writes go through the outbox (accepted at once, sent in order); the reads need the network. */
interface TaskActions {
    fun message(text: String)
    /** @return the temporary id of the new task (the real one replaces it once the server answers), or null if it wasn't accepted. */
    suspend fun create(body: JsonObject): Long?
    suspend fun update(id: Long, body: JsonObject): Boolean
    suspend fun move(body: JsonObject): Boolean
    /** [noun]: project, section, label or filter. */
    suspend fun createEntity(noun: String, body: JsonObject): Long?
    suspend fun updateEntity(noun: String, id: Long, body: JsonObject): Boolean
    suspend fun deleteEntity(noun: String, id: Long): Boolean
    suspend fun reorderEntities(noun: String, ids: List<Long>): Boolean
    suspend fun comments(taskId: Long): List<CommentDto>
    suspend fun addComment(taskId: Long, body: String): Boolean
    suspend fun updateComment(id: Long, body: String): Boolean
    suspend fun deleteComment(id: Long, taskId: Long): Boolean
    suspend fun addReminder(taskId: Long, body: JsonObject): Boolean
    suspend fun deleteReminder(id: Long): Boolean
    suspend fun completions(since: Long): List<CompletionDto>
}

object NoTaskActions : TaskActions {
    override fun message(text: String) = Unit
    override suspend fun create(body: JsonObject): Long? = null
    override suspend fun update(id: Long, body: JsonObject) = false
    override suspend fun move(body: JsonObject) = false
    override suspend fun createEntity(noun: String, body: JsonObject): Long? = null
    override suspend fun updateEntity(noun: String, id: Long, body: JsonObject) = false
    override suspend fun deleteEntity(noun: String, id: Long) = false
    override suspend fun reorderEntities(noun: String, ids: List<Long>) = false
    override suspend fun comments(taskId: Long): List<CommentDto> = emptyList()
    override suspend fun addComment(taskId: Long, body: String) = false
    override suspend fun updateComment(id: Long, body: String) = false
    override suspend fun deleteComment(id: Long, taskId: Long) = false
    override suspend fun addReminder(taskId: Long, body: JsonObject) = false
    override suspend fun deleteReminder(id: Long) = false
    override suspend fun completions(since: Long): List<CompletionDto> = emptyList()
}
