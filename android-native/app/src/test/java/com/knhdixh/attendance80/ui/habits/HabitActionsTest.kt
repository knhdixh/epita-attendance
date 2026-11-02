package com.knhdixh.attendance80.ui.habits

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
class HabitActionsTest {
    @Test fun checkInsAndEditsSendTheWebRequests() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("habits").toFile()
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
            val clock = object : Clock { override fun now() = hbNow; override fun zone() = hbZone }
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
            val habits = shell.habitActions("a")
            val habit = """{"id":7,"name":"Read","icon":null,"color":"blue","schedule":{"type":"daily"},"target":1,"unit":null,"remind_at":null,"position":0,"archived":false,"created_at":1}"""

            server.enqueue(MockResponse().setBody(habit))
            assertNotNull(habits.create(buildJsonObject { put("name", "Read") }))
            server.takeRequest().let { assertEquals("POST /api/habits", "${it.method} ${it.path}") }
            assertEquals("Added “Read” · Every day.", repo.error.value); repo.consumeError(repo.error.value!!)
            assertTrue(repo.snapshot.value!!.habits.list.any { it.id == 7L })

            // A check-in shows at once; a refusal takes it back and shows the server's message.
            server.enqueue(MockResponse().setBody("""{"habit_id":7,"day":"2026-11-04","count":1}"""))
            assertTrue(habits.setCount(7, "2026-11-04", 1))
            server.takeRequest().let { r ->
                assertEquals("PUT /api/habits/7/log", "${r.method} ${r.path}")
                assertEquals(buildJsonObject { put("day", "2026-11-04"); put("count", 1) }, Json.parseToJsonElement(r.body.readUtf8()))
            }
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"That day is too far back."}"""))
            assertFalse(habits.setCount(7, "2026-11-04", 0))
            server.takeRequest()
            assertEquals(1, repo.snapshot.value!!.habits.logs.single { it.habit_id == 7L }.count)
            assertEquals("That day is too far back.", repo.error.value); repo.consumeError(repo.error.value!!)

            server.enqueue(MockResponse().setBody(habit.replace("false,\"created_at", "true,\"created_at")))
            assertTrue(habits.update(7, buildJsonObject { put("archived", true) })!!.archived)
            server.takeRequest().let { assertEquals("PATCH /api/habits/7", "${it.method} ${it.path}") }

            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            assertTrue(habits.order(listOf(7)))
            server.takeRequest().let { r -> assertEquals("POST /api/habits/order", "${r.method} ${r.path}"); assertEquals("""{"ids":[7]}""", r.body.readUtf8()) }

            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            assertTrue(habits.delete(7))
            server.takeRequest().let { assertEquals("DELETE /api/habits/7", "${it.method} ${it.path}") }
            assertTrue(repo.snapshot.value!!.habits.let { h -> h.list.none { it.id == 7L } && h.logs.none { it.habit_id == 7L } })

            val before = server.requestCount
            assertFalse(shell.habitActions("b").setCount(1, "2026-11-04", 1))
            assertEquals("no request for another account", before, server.requestCount)
        } finally { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}

