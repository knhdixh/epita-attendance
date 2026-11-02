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

/** Serialises reads and writes so a refresh cannot overwrite an in-flight mutation. */
class DataRepository(private val api: Api, private val directory: File, private val clock: Clock,
                     private val rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
                     private val token: () -> String?) {
    private val mutex = Mutex()
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

    suspend fun start() {
        if (session != token()) {
            publish(null, null)
            _lastSynced.value = null
            clearErrors()
        }
        mutex.withLock {
            val current = token()
            if (session != current) {
                session = current
                publish(null, null)
                _lastSynced.value = null
                clearErrors()
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
            if (token() != active) return false
            val synced = clock.now()
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
        val before = _snapshot.value ?: return@withLock false
        if (token() != active) return@withLock false
        if (!preserveError) clearErrors()
        publish(active, optimistic(before))
        try {
            val result = request()
            if (token() != active) return@withLock false
            publish(active, apply(_snapshot.value ?: before, result))
            if (reload) refreshLocked() else true
        } catch (e: CancellationException) {
            if (token() == active) publish(active, before)
            throw e
        } catch (e: Exception) {
            if (token() == active) {
                publish(active, before)
                reportError(e.message ?: "Request failed", preserveError)
            }
            false
        }
    }
}
