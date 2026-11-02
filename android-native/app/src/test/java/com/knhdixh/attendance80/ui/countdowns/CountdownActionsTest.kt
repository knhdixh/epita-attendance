package com.knhdixh.attendance80.ui.countdowns

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
class CountdownActionsTest {
    @Test fun createUpdateAndDeleteSendTheWebRequests() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("countdowns").toFile()
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
            val clock = object : Clock { override fun now() = cdNow; override fun zone() = cdZone }
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
            val cds = shell.countdownActions("a")
            val row = """{"id":7,"title":"Trip","at":1800000000000,"all_day":1,"repeat":"none","units":"auto","after":"up","start_at":null,"color":"teal","pinned":0,"note":null,"event_id":null,"homework_id":null,"created_at":1}"""

            server.enqueue(MockResponse().setBody(row))
            assertEquals(7L, cds.create(buildJsonObject { put("title", "Trip") })!!.id)
            server.takeRequest().let { assertEquals("POST /api/countdowns", "${it.method} ${it.path}") }

            // Pinning shows at once; a refusal puts it back with the server's message.
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Too many pinned."}"""))
            assertFalse(cds.update(7, buildJsonObject { put("pinned", true) }))
            server.takeRequest().let { r -> assertEquals("PATCH /api/countdowns/7", "${r.method} ${r.path}"); assertEquals("""{"pinned":true}""", r.body.readUtf8()) }
            assertFalse(repo.snapshot.value!!.countdowns.single { it.id == 7L }.pinned)
            assertEquals("Too many pinned.", repo.error.value); repo.consumeError(repo.error.value!!)

            server.enqueue(MockResponse().setBody(row.replace("\"pinned\":0", "\"pinned\":1")))
            assertTrue(cds.update(7, buildJsonObject { put("pinned", true) }))
            server.takeRequest()
            assertTrue(repo.snapshot.value!!.countdowns.single { it.id == 7L }.pinned)

            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            assertTrue(cds.delete(7))
            server.takeRequest().let { assertEquals("DELETE /api/countdowns/7", "${it.method} ${it.path}") }
            assertTrue(repo.snapshot.value!!.countdowns.none { it.id == 7L })

            val before = server.requestCount
            assertFalse(shell.countdownActions("b").delete(1))
            assertEquals("no request for another account", before, server.requestCount)
        } finally { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}

