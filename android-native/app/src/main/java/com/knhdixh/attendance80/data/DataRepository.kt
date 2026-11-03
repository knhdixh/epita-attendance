package com.knhdixh.attendance80.data

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.net.Api
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.ApiError
import com.knhdixh.attendance80.net.AppData
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class FocusHistoryStatus(val owner: String? = null, val since: Long? = null, val loading: Boolean = false, val failed: Boolean = false, val attempted: Boolean = false, val refreshVersion: Long = 0)

/** Serialises reads and writes so a refresh cannot overwrite an in-flight mutation. */
class DataRepository(private val api: Api, private val directory: File, private val clock: Clock,
                     private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
                     private val queue: MutationQueue = MutationQueue(File(directory, "outbox")),
                     /** Called after a change is queued: the app tries to send it at once and asks WorkManager to retry. */
                     private val onQueued: () -> Unit = {},
                     private val token: () -> String?) {
    private val mutex = Mutex()
    private val flushMutex = Mutex()
    private val generation = AtomicLong()
    private val _focusHistory = MutableStateFlow(FocusHistoryStatus())
    val focusHistory = _focusHistory.asStateFlow()
    @Volatile private var session: String? = null
    private val _snapshot = MutableStateFlow<AppData?>(null)
    val snapshot = _snapshot.asStateFlow()
    private val _sessionSnapshot = MutableStateFlow<Triple<String?, AppData?, Long?>>(Triple(null, null, null))
    val sessionSnapshot = _sessionSnapshot.asStateFlow()
    private fun publish(owner: String?, data: AppData?, synced: Long? =
        if (owner == _sessionSnapshot.value.first) _sessionSnapshot.value.third else null) {
        _snapshot.value = data
        _sessionSnapshot.value = Triple(owner, data, synced)
    }
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val deferredErrors = ArrayDeque<String>()
    @Synchronized fun consumeError(message: String) {
        if (_error.value == message) _error.value = if (deferredErrors.isEmpty()) null else deferredErrors.removeFirst()
    }
    @Synchronized private fun clearErrors() { deferredErrors.clear(); _error.value = null }
    @Synchronized private fun reportError(message: String, preserve: Boolean = false) {
        if (preserve && _error.value != null) deferredErrors.addLast(message) else { deferredErrors.clear(); _error.value = message }
    }
    fun reportMessage(owner: String, message: String) {
        if (session == owner && token() == owner) reportError(message, preserve = true)
    }
    private val _pending = MutableStateFlow(0)
    /** How many changes made on this device haven't reached the server yet ("Not synced yet"). */
    val pending = _pending.asStateFlow()
    private fun accountOf(data: AppData?) = data?.user?.email?.lowercase() ?: "unknown"
    private fun syncPending() { _pending.value = _snapshot.value?.let { queue.items(accountOf(it)).size } ?: 0 }

    private val _lastSynced = MutableStateFlow<Long?>(null)
    val lastSynced = _lastSynced.asStateFlow()

    /** The id the server already gave a create that was sent but not settled (its answer was lost, or a refresh got there first). */
    private fun settledId(data: AppData, m: QueuedMutation): Long? = when (m.kind) {
        "task.create" -> data.homework.find { it.client_id == m.clientId }?.id
        "project.create" -> data.projects.find { it.client_id == m.clientId }?.id
        "section.create" -> data.sections.find { it.client_id == m.clientId }?.id
        "label.create" -> data.labels.find { it.client_id == m.clientId }?.id
        "filter.create" -> data.filters.find { it.client_id == m.clientId }?.id
        else -> null
    }

    /** What the server sent, with the changes it hasn't seen yet applied on top. */
    private fun withPending(data: AppData): AppData {
        val account = accountOf(data)
        queue.purgeExcept(account)
        for (m in queue.items(account)) {
            val real = if (m.creates) settledId(data, m) else null
            if (real != null) { queue.remap(account, m.temp!!, real); queue.remove(account, m.clientId) }
        }
        return queue.items(account).fold(data) { d, m -> LocalEffects.apply(d, m, clock.zone()) }
    }

    private fun cacheFile(session: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(session.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "data-$hash.json")
    }

    /** Deletes every cached snapshot (and half-written temporary file) except the one for [keep]. */
    private fun purgeCaches(keep: String?) {
        val keepName = keep?.let { cacheFile(it).name }
        directory.listFiles()?.forEach { f ->
            val stale = (f.name.startsWith("data-") && f.name.endsWith(".json") && f.name != keepName) ||
                (f.name.startsWith("snapshot-") && f.name.endsWith(".tmp"))
            if (stale) f.delete()
        }
    }

    suspend fun start() {
        if (session != token()) {
            generation.incrementAndGet()
            publish(null, null)
            _lastSynced.value = null
            clearErrors()
            _focusHistory.value = FocusHistoryStatus()
        }
        mutex.withLock {
            val current = token()
            if (session != current) {
                session = current
                publish(null, null)
                _lastSynced.value = null
                clearErrors()
                // Sign-out, account deletion, a 401 or a new sign-in: nothing of another session stays on disk.
                purgeCaches(current)
                if (current != null) {
                    val cached = runCatching {
                        ApiClient.json.decodeFromString<AppData>(cacheFile(current).readText())
                    }.getOrNull()
                    if (token() == current) {
                        publish(current, cached?.let(::withPending))
                        syncPending()
                    }
                }
            }
        }
    }

    suspend fun refresh(): Boolean = mutex.withLock { refreshLocked() }

    /** The data endpoint only includes recent history; fetch the year independently. */
    suspend fun ensureFocusHistory(owner: String, since: Long) = mutex.withLock {
        val data = _snapshot.value ?: return@withLock
        if (session != owner || token() != owner) return@withLock
        val version = generation.get()
        if (data.focus.since <= since) return@withLock
        val status = _focusHistory.value
        if (status.owner == owner && status.since == since && (status.loading || status.attempted)) return@withLock
        val refreshVersion = status.refreshVersion
        _focusHistory.value = FocusHistoryStatus(owner, since, loading = true, refreshVersion = refreshVersion)
        try {
            val result = api.listFocus(since)
            if (session != owner || token() != owner || generation.get() != version) return@withLock
            val current = _snapshot.value ?: return@withLock
            val known = result.sessions.mapTo(HashSet()) { it.client_id }
            val merged = (result.sessions + current.focus.sessions.filter { it.client_id !in known && it.started_at >= result.since }).sortedBy { it.started_at }
            publish(owner, current.copy(focus = current.focus.copy(sessions = merged, since = result.since)))
            _focusHistory.value = FocusHistoryStatus(owner, since, failed = result.since > since, attempted = true, refreshVersion = refreshVersion)
        } catch (e: CancellationException) {
            if (session == owner && token() == owner && generation.get() == version) _focusHistory.value = FocusHistoryStatus(refreshVersion = refreshVersion)
            throw e
        } catch (e: Exception) {
            if (session == owner && token() == owner && generation.get() == version) {
                _focusHistory.value = FocusHistoryStatus(owner, since, failed = true, attempted = true, refreshVersion = refreshVersion)
                reportError(e.message ?: "Request failed")
            }
        }
    }

    suspend fun saveFocusSettingsCanonical(owner: String, body: kotlinx.serialization.json.JsonObject): com.knhdixh.attendance80.net.FocusSettingsDto? {
        var saved: com.knhdixh.attendance80.net.FocusSettingsDto? = null
        val success = mutate({ it }, { api.saveFocusSettings(body).also { saved = it.settings } },
            { data, result -> data.copy(focus = data.focus.copy(settings = result.settings)) }, expectedOwner = owner)
        return if (success) saved else null
    }

    suspend fun saveFocusSettings(owner: String, body: kotlinx.serialization.json.JsonObject): Boolean = saveFocusSettingsCanonical(owner, body) != null

    /** A session the server has just recorded (or already had): in the snapshot once, by `client_id`. */
    suspend fun recordFocus(session: com.knhdixh.attendance80.net.FocusSessionDto): Boolean =
        mutate({ it }, { session }, { data, row ->
            val sessions = (data.focus.sessions.filterNot { it.client_id == row.client_id } + row).sortedBy { it.started_at }
            data.copy(focus = data.focus.copy(sessions = sessions))
        })

    suspend fun deleteFocus(owner: String, id: Long): Boolean =
        mutate({ it }, { api.deleteFocus(id) },
            { data, _ -> data.copy(focus = data.focus.copy(sessions = data.focus.sessions.filterNot { it.id == id })) }, expectedOwner = owner)

    private suspend fun refreshLocked(): Boolean {
        val active = session ?: return false
        if (token() != active) return false
        _loading.value = true
        clearErrors()
        return try {
            val raw = api.rawData().use { it.string() }
            val data = ApiClient.json.decodeFromString<AppData>(raw)
            if (token() != active) return false
            directory.mkdirs()
            val target = cacheFile(active)
            val temporary = File.createTempFile("snapshot-", ".tmp", directory)
            try {
                temporary.writeText(raw)
                check(rename(temporary, target)) { "Could not save snapshot" }
            } finally { temporary.delete() }
            if (token() != active) { target.delete(); return false }
            val synced = clock.now()
            _focusHistory.value = FocusHistoryStatus(refreshVersion = _focusHistory.value.refreshVersion + 1)
            publish(active, withPending(data), synced)
            syncPending()
            _lastSynced.value = synced
            true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (token() == active) reportError(e.message ?: "Request failed")
            false
        } finally { _loading.value = false }
    }

    /**
     * Queues a change: applied to the snapshot now, sent later in order. Works without a network. A create gets a negative
     * temporary id until the server answers (returned in [Queued.temp]). Null when there is no snapshot of that session.
     */
    suspend fun enqueue(m: QueuedMutation, expectedOwner: String? = null): Queued? = mutex.withLock {
        val active = session ?: return@withLock null
        if (expectedOwner != null && expectedOwner != active) return@withLock null
        if (token() != active) return@withLock null
        val data = _snapshot.value ?: return@withLock null
        clearErrors()
        val (queued, temp) = queue.add(accountOf(data), m.copy(at = clock.now()))
        publish(active, LocalEffects.apply(data, queued, clock.zone()))
        syncPending()
        onQueued()
        Queued(temp)
    }

    data class Queued(val temp: Long?)

    /** Sign-out: the changes that never got sent are dropped with the rest of the account's data. */
    fun signedOut() { queue.purgeAll(); _pending.value = 0 }

    /**
     * Sends the queue, oldest first. A change leaves it after a 2xx; after a 4xx it is dropped (it would never succeed: the
     * server's message is shown, with what depends on it) and the data is reloaded; no network, 401 or 5xx stop here for the
     * next try. @return true when nothing is left to send.
     */
    suspend fun flushQueue(): Boolean = flushMutex.withLock {
        var reload = false
        val notes = mutableListOf<String>()   // shown after a reload, which clears errors
        while (true) {
            val step = mutex.withLock {
                val active = session ?: return@withLock null
                if (token() != active) return@withLock null
                val account = accountOf(_snapshot.value ?: return@withLock null)
                queue.items(account).firstOrNull()?.let { Triple(active, account, it) }
            } ?: break
            val (active, account, head) = step
            val outcome: Any = try { sendQueued(head) } catch (e: CancellationException) { throw e
            } catch (e: ApiError) { if (e.status in 400..499 && e.status !in setOf(401, 408, 429)) e else return@withLock false
            } catch (e: Exception) { return@withLock false }
            mutex.withLock {
                if (session != active || token() != active) return@withLock
                if (outcome is ApiError) {
                    queue.remove(account, head.clientId)
                    if (head.creates) queue.dropMentioning(account, head.temp!!).forEach { queue.remove(account, it.clientId) }
                    notes += outcome.message
                    reload = true
                } else if (queue.items(account).any { it.clientId == head.clientId }) {
                    settle(active, account, head, outcome as String)?.let { notes += it }
                    if (head.reload) reload = true
                }
                syncPending()
            }
        }
        if (reload) refresh()
        notes.forEach { reportError(it, preserve = true) }
        mutex.withLock { syncPending() }
        _snapshot.value?.let { queue.isEmpty(accountOf(it)) } ?: true
    }

    private suspend fun sendQueued(m: QueuedMutation): String {
        val body = if (m.creates) JsonObject(m.body + ("client_id" to JsonPrimitive(m.clientId))) else m.body
        val response = when (m.method) {
            "POST" -> api.sendPost(m.path, body)
            "PATCH" -> api.sendPatch(m.path, body)
            "PUT" -> api.sendPut(m.path, body)
            else -> api.sendDelete(m.path)
        }
        return response.use { it.string() }
    }

    /** The server accepted [m]: temporary ids become real, and the server's row replaces ours unless a later change still touches it. */
    private fun settle(active: String, account: String, m: QueuedMutation, text: String): String? {
        var data = _snapshot.value ?: return null
        val answer = runCatching { ApiClient.json.parseToJsonElement(text) }.getOrNull()
        queue.remove(account, m.clientId)
        val obj = answer as? JsonObject
        if (m.creates && m.temp != null) {
            val real = (obj?.get("id") as? JsonPrimitive)?.longOrNull
            if (real != null) { queue.remap(account, m.temp, real); data = LocalEffects.remap(data, m.temp, real) }
        }
        val pendingIds = queue.items(account)
        fun settled(id: Long) = pendingIds.none { it.mentions(id) || it.kind.startsWith("task.") && it.path.endsWith("/$id") }
        data = runCatching { LocalEffects.reconcile(data, m, obj, ::settled) }.getOrDefault(data)
        publish(active, data)
        return data.homework.find { it.client_id == m.clientId || (it.id == (obj?.get("id") as? JsonPrimitive)?.longOrNull) }?.sync_error
            ?.takeIf { m.kind.startsWith("task.") }?.let { "Saved, but Google Calendar sync failed: $it" }
    }

    suspend fun <T> mutate(optimistic: (AppData) -> AppData, request: suspend () -> T,
                           apply: (AppData, T) -> AppData, reload: Boolean = false,
                           expectedOwner: String? = null, preserveError: Boolean = false): Boolean = mutex.withLock {
        val active = session ?: return@withLock false
        if (expectedOwner != null && expectedOwner != active) return@withLock false
        val version = generation.get()
        val before = _snapshot.value ?: return@withLock false
        if (token() != active) return@withLock false
        if (!preserveError) clearErrors()
        publish(active, optimistic(before))
        try {
            val result = request()
            if (token() != active || generation.get() != version) return@withLock false
            publish(active, apply(_snapshot.value ?: before, result))
            if (reload) refreshLocked() else true
        } catch (e: CancellationException) {
            if (token() == active && generation.get() == version) publish(active, before)
            throw e
        } catch (e: Exception) {
            if (token() == active && generation.get() == version) {
                publish(active, before)
                reportError(e.message ?: "Request failed", preserveError)
            }
            false
        }
    }
}
