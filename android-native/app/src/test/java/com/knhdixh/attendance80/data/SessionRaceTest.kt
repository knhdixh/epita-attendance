package com.knhdixh.attendance80.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.auth.EncryptedTokenStore
import com.knhdixh.attendance80.auth.TokenCipher
import kotlinx.coroutines.*
import com.knhdixh.attendance80.net.ApiClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class SessionRaceTest {
    @Test fun oldUnauthorizedCannotClearNewSession() = runBlocking {
        val dir = Files.createTempDirectory("session").toFile()
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val store = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("auth.preferences_pb") }
        val cipher = object : TokenCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(ciphertext: String) = ciphertext
        }
        try {
            val tokens = EncryptedTokenStore(store, cipher, scope)
            tokens.awaitToken()
            tokens.setToken("old")
            tokens.setToken("new")
            tokens.clearTokenIfMatches("old")
            assertEquals("new", tokens.currentToken())
            tokens.clearTokenIfMatches("new")
            assertEquals(null, tokens.currentToken())
        } finally { job.cancel(); dir.deleteRecursively() }
    }
    @Test fun delayedUnauthorizedFromOldRequestDoesNotClearNewToken() = runBlocking {
        val dir = Files.createTempDirectory("session").toFile()
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val store = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("auth.preferences_pb") }
        val cipher = object : TokenCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(ciphertext: String) = ciphertext
        }
        val server = MockWebServer()
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                release.await(5, TimeUnit.SECONDS)
                return MockResponse().setResponseCode(401).setBody("{\"error\":\"Not signed in.\"}")
            }
        }
        server.start()
        try {
            val tokens = EncryptedTokenStore(store, cipher, scope)
            tokens.awaitToken(); tokens.setToken("old")
            val api = ApiClient.createApi(server.url("/").toString(), { tokens.currentToken() }, { rejected ->
                if (rejected != null) runBlocking { tokens.clearTokenIfMatches(rejected) }
            })
            val request = async(Dispatchers.IO) { runCatching { api.data() } }
            val received = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bearer old", received.getHeader("Authorization"))
            tokens.setToken("new")
            release.countDown()
            assertTrue(request.await().isFailure)
            assertEquals("new", tokens.currentToken())
        } finally { release.countDown(); server.shutdown(); job.cancel(); dir.deleteRecursively() }
    }
}
