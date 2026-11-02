package com.knhdixh.attendance80.ui.breaks

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.*
import com.knhdixh.attendance80.auth.*
import com.knhdixh.attendance80.data.*
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.ui.shell.ShellViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BreaksActionsTest {
    @Test fun saveAndRemoveSendTheWebRequestsReloadAndReport() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("breaks").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tokenStore = object : TokenStore {
                override val token = MutableStateFlow<String?>("a")
                override val isLoaded = MutableStateFlow(true)
                override suspend fun awaitToken() = token.value
                override suspend fun setToken(token: String) { this.token.value = token }
                override suspend fun clearToken() { token.value = null }
            }
            val clock = object : Clock { override fun now() = breaksNow; override fun zone() = breaksZone }
            val api = ApiClient.createApi(server.url("/").toString(), { tokenStore.currentToken() }, {})
            val repo = DataRepository(api, dir, clock) { tokenStore.currentToken() }
            val container = object : AppContainer {
                override val context = context
                override val clock = clock
                override val tokenStore = tokenStore
                override val api = api
                override val dataRepository = repo
                override val authRepository = AuthRepository(api, tokenStore, CredentialManager.create(context))
                override val calendarScope = Scope(PreferenceDataStoreFactory.create(scope = storeScope) { dir.resolve("scope.preferences_pb") })
            }
            val fixture = javaClass.getResource("/api/data.json")!!.readText()
            repo.start(); server.enqueue(MockResponse().setBody(fixture)); assertTrue(repo.refresh()); server.takeRequest()
            val shell = ShellViewModel(container)

            server.enqueue(MockResponse().setBody("""{"break":{"id":9,"label":"Dentist","start_date":"2026-11-04","end_date":"2026-11-05","sessions":2},"sessions":2}"""))
            server.enqueue(MockResponse().setBody(fixture))
            assertTrue(shell.saveBreak("a", "2026-11-04", "2026-11-05", " Dentist "))
            val post = server.takeRequest()
            assertEquals("POST /api/breaks", "${post.method} ${post.path}")
            assertEquals(buildJsonObject { put("from", "2026-11-04"); put("to", "2026-11-05"); put("label", "Dentist"); put("tz", "Europe/Paris") },
                Json.parseToJsonElement(post.body.readUtf8()))
            assertEquals("GET /api/data", server.takeRequest().let { "${it.method} ${it.path}" })
            assertEquals("Break saved: 2 sessions marked as planned absences.", repo.error.value)
            repo.consumeError(repo.error.value!!)

            server.enqueue(MockResponse().setBody("""{"restored":1}"""))
            server.enqueue(MockResponse().setBody(fixture))
            assertTrue(shell.removeBreak("a", 1))
            assertEquals("DELETE /api/breaks/1", server.takeRequest().let { "${it.method} ${it.path}" })
            assertEquals("GET /api/data", server.takeRequest().let { "${it.method} ${it.path}" })
            assertEquals("Break removed, 1 session restored.", repo.error.value)
            repo.consumeError(repo.error.value!!)

            // A server error shows its message and reports nothing else.
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Pick a range inside your calendar."}"""))
            assertFalse(shell.saveBreak("a", "2027-01-01", "2027-01-02", ""))
            server.takeRequest()
            assertEquals("Pick a range inside your calendar.", repo.error.value)
            assertEquals(1, repo.snapshot.value!!.breaks.size)
            // Another account's request is ignored.
            assertFalse(shell.removeBreak("b", 1))
            assertEquals("no request for another account", 6, server.requestCount)
        } finally { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}
