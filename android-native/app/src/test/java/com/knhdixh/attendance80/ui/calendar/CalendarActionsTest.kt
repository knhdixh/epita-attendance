package com.knhdixh.attendance80.ui.calendar

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.*
import com.knhdixh.attendance80.auth.*
import com.knhdixh.attendance80.data.*
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.HomeworkDto
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
class CalendarActionsTest {
    @Test fun planUnplanAndKeepSendTheWebRequests() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("calendar").toFile()
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
            val clock = object : Clock { override fun now() = calNow; override fun zone() = calZone }
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
            val calendar = shell.calendarActions("a")
            val hw = repo.snapshot.value!!.homework.first()

            // Plan: the exact PATCH body, applied at once and replaced by the server's row.
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(HomeworkDto.serializer(), hw.copy(planned_at = 1000, planned_minutes = 45, title = "From server"))))
            assertTrue(calendar.setPlan(hw, 1000, 45))
            server.takeRequest().let { r ->
                assertEquals("PATCH /api/homework/${hw.id}", "${r.method} ${r.path}")
                assertEquals(buildJsonObject { put("planned_at", 1000L); put("planned_minutes", 45) }, Json.parseToJsonElement(r.body.readUtf8()))
            }
            assertEquals("From server", repo.snapshot.value!!.homework.first { it.id == hw.id }.title)

            // Unplan sends nulls; a refusal puts the block back and shows the server's message.
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"That time is in the past."}"""))
            assertFalse(calendar.setPlan(repo.snapshot.value!!.homework.first { it.id == hw.id }, null, null))
            server.takeRequest().let { r -> assertEquals(buildJsonObject { put("planned_at", JsonNull); put("planned_minutes", JsonNull) }, Json.parseToJsonElement(r.body.readUtf8())) }
            assertEquals(1000L, repo.snapshot.value!!.homework.first { it.id == hw.id }.planned_at)
            assertEquals("That time is in the past.", repo.error.value)
            repo.consumeError(repo.error.value!!)

            // Keep: one POST with every proposal; the rows that come back are applied.
            server.enqueue(MockResponse().setBody("""{"homework":[${ApiClient.json.encodeToString(HomeworkDto.serializer(), hw.copy(planned_at = 5000, planned_minutes = 30))}]}"""))
            assertTrue(calendar.keep(listOf(Proposal(hw.id, 5000, 30))))
            server.takeRequest().let { r ->
                assertEquals("POST /api/homework/plan", "${r.method} ${r.path}")
                assertEquals("""{"items":[{"id":${hw.id},"planned_at":5000,"planned_minutes":30}]}""", r.body.readUtf8())
            }
            assertEquals(5000L, repo.snapshot.value!!.homework.first { it.id == hw.id }.planned_at)

            // Another account's calendar sends nothing.
            val before = server.requestCount
            assertFalse(shell.calendarActions("b").setPlan(hw, 1, 15))
            assertEquals(before, server.requestCount)
        } finally { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}

