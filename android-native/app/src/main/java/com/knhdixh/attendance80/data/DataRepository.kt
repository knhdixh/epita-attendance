package com.knhdixh.attendance80.data

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.net.Api
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AppData
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
                     private val token: () -> String?) {
    private val mutex = Mutex()
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
    private val _lastSynced = MutableStateFlow<Long?>(null)
    val lastSynced = _lastSynced.asStateFlow()

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
                        publish(current, cached)
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
            publish(active, data, synced)
            _lastSynced.value = synced
            true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (token() == active) reportError(e.message ?: "Request failed")
            false
        } finally { _loading.value = false }
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
