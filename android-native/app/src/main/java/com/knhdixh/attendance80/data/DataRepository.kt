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
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _lastSynced = MutableStateFlow<Long?>(null)
    val lastSynced = _lastSynced.asStateFlow()

    private fun cacheFile(session: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(session.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "data-$hash.json")
    }

    suspend fun start() {
        if (session != token()) {
            _snapshot.value = null
            _lastSynced.value = null
            _error.value = null
        }
        mutex.withLock {
            val current = token()
            if (session != current) {
                session = current
                _snapshot.value = null
                _lastSynced.value = null
                _error.value = null
                if (current != null) {
                    val cached = runCatching {
                        ApiClient.json.decodeFromString<AppData>(cacheFile(current).readText())
                    }.getOrNull()
                    if (token() == current) _snapshot.value = cached
                }
            }
        }
    }

    suspend fun refresh(): Boolean = mutex.withLock { refreshLocked() }

    private suspend fun refreshLocked(): Boolean {
        val active = session ?: return false
        if (token() != active) return false
        _loading.value = true
        _error.value = null
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
            _snapshot.value = data
            _lastSynced.value = clock.now()
            true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (token() == active) _error.value = e.message ?: "Request failed"
            false
        } finally { _loading.value = false }
    }

    suspend fun <T> mutate(optimistic: (AppData) -> AppData, request: suspend () -> T,
                           apply: (AppData, T) -> AppData, reload: Boolean = false): Boolean = mutex.withLock {
        val active = session ?: return@withLock false
        val before = _snapshot.value ?: return@withLock false
        if (token() != active) return@withLock false
        _error.value = null
        _snapshot.value = optimistic(before)
        try {
            val result = request()
            if (token() != active) return@withLock false
            _snapshot.value = apply(_snapshot.value ?: before, result)
            if (reload) refreshLocked() else true
        } catch (e: CancellationException) {
            if (token() == active) _snapshot.value = before
            throw e
        } catch (e: Exception) {
            if (token() == active) {
                _snapshot.value = before
                _error.value = e.message ?: "Request failed"
            }
            false
        }
    }
}
