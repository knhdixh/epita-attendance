package com.knhdixh.attendance80.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/**
 * One change made on the device, waiting to reach the server. It is applied to the snapshot at once ([LocalEffects]) and
 * sent later, in order, with its [clientId] (the server returns the existing row for a repeated one, so a retry is safe).
 *
 * A thing created offline has a negative temporary id ([temp]) until the server answers; paths and bodies of later changes
 * name it by that id and are rewritten when the real one is known ([MutationQueue.remap]).
 */
@Serializable
data class QueuedMutation(
    val clientId: String,
    /** What it is, for the local effect: `task.create`, `task.complete`, `project.delete`, `habit.log` ... */
    val kind: String,
    val method: String,
    /** Relative to the API base, e.g. `api/homework/-3/complete`. */
    val path: String,
    val body: JsonObject = JsonObject(emptyMap()),
    /** The temporary id a create gives its row. Set when queued. */
    val temp: Long? = null,
    /** The task a comment or reminder belongs to (the server's answer doesn't say). */
    val about: Long? = null,
    /** The change touches many rows (a parent with subtasks, a project's tasks): reload when the queue has been sent. */
    val reload: Boolean = false,
    val at: Long = 0,
) {
    val creates get() = kind.endsWith(".create")

    /** Does this change name the temporary id [id] (in its path or in an id field of its body)? */
    fun mentions(id: Long): Boolean = path.split('/').any { it == id.toString() } || ID_FIELDS.any { k -> body[k]?.let { idIn(it, id) } == true } || about == id

    /** The same change with the temporary id [from] replaced by the real [to]. */
    fun remapped(from: Long, to: Long): QueuedMutation {
        if (!mentions(from)) return this
        fun swap(e: JsonElement): JsonElement = when {
            e is JsonArray -> JsonArray(e.map(::swap))
            e is JsonPrimitive && !e.isString && e.longOrNull == from -> JsonPrimitive(to)
            else -> e
        }
        return copy(
            path = path.split('/').joinToString("/") { if (it == from.toString()) to.toString() else it },
            body = JsonObject(body.mapValues { (k, v) -> if (k in ID_FIELDS) swap(v) else v }),
            about = if (about == from) to else about,
        )
    }

    private fun idIn(e: JsonElement, id: Long): Boolean = when (e) {
        is JsonArray -> e.any { idIn(it, id) }
        is JsonPrimitive -> !e.isString && e.longOrNull == id
        else -> false
    }

    companion object {
        val ID_FIELDS = setOf("project_id", "section_id", "parent_id", "homework_id", "ids", "label_ids")
    }
}

@Serializable
data class QueueState(val account: String = "", val nextTemp: Long = -1, val items: List<QueuedMutation> = emptyList())

/**
 * The changes waiting to be sent, per account, in a file each (so a restart keeps them and one account never sends
 * another's). Writes are atomic. Everything is synchronised: the flusher and the screens use it together.
 */
class MutationQueue(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val states = HashMap<String, QueueState>()

    private fun file(account: String) = File(directory, "outbox-" + MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + ".json")

    @Synchronized private fun state(account: String): QueueState = states.getOrPut(account) {
        runCatching { json.decodeFromString<QueueState>(file(account).readText()) }.getOrNull()?.copy(account = account) ?: QueueState(account)
    }

    @Synchronized private fun save(s: QueueState) {
        states[s.account] = s
        directory.mkdirs()
        val target = file(s.account)
        if (s.items.isEmpty() && s.nextTemp == -1L) { target.delete(); return }
        val tmp = File.createTempFile("outbox-", ".tmp", directory)
        try { tmp.writeText(json.encodeToString(s)); if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "Could not save the outbox" } } } finally { tmp.delete() }
    }

    @Synchronized fun items(account: String): List<QueuedMutation> = state(account).items
    @Synchronized fun isEmpty(account: String) = state(account).items.isEmpty()

    /** Queues [m]. A create gets the next temporary id, which is returned (null for any other change). */
    @Synchronized fun add(account: String, m: QueuedMutation): Pair<QueuedMutation, Long?> {
        val s = state(account)
        val temp = if (m.creates) s.nextTemp else null
        val queued = if (temp != null) m.copy(temp = temp) else m
        save(s.copy(nextTemp = if (temp != null) s.nextTemp - 1 else s.nextTemp, items = s.items + queued))
        return queued to temp
    }

    @Synchronized fun remove(account: String, clientId: String) {
        val s = state(account)
        save(s.copy(items = s.items.filterNot { it.clientId == clientId }))
    }

    /** A temporary id became [to]: every queued change that named it now names the real one. */
    @Synchronized fun remap(account: String, from: Long, to: Long) {
        val s = state(account)
        save(s.copy(items = s.items.map { it.remapped(from, to) }))
    }

    /** Drops every change that names a temporary id that will never exist (its create was refused). */
    @Synchronized fun dropMentioning(account: String, id: Long): List<QueuedMutation> {
        val s = state(account)
        val (gone, kept) = s.items.partition { it.mentions(id) }
        if (gone.isNotEmpty()) save(s.copy(items = kept))
        return gone
    }

    /** Another account signed in: what the others queued can never be sent, so it goes. */
    @Synchronized fun purgeExcept(account: String) {
        val mine = file(account).name
        states.keys.removeAll { it != account }
        directory.listFiles()?.forEach { if (it.name.startsWith("outbox-") && it.name != mine) it.delete() }
    }

    /** Sign-out: nothing of the account stays on the device. */
    @Synchronized fun purgeAll() {
        states.clear()
        directory.listFiles()?.forEach { if (it.name.startsWith("outbox-")) it.delete() }
    }
}
