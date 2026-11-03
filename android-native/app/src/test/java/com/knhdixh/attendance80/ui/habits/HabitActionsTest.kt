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
import java.util.concurrent.TimeUnit
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.HabitDto
import com.knhdixh.attendance80.net.HabitLogDto
import kotlinx.serialization.json.jsonObject

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

            // The actions queue the change (it shows at once, works offline) and the shell sends it; spin the main looper until it has gone.
            fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            fun settle() { repeat(1000) { idle(); if (repo.pending.value == 0) { idle(); return }; Thread.sleep(5) }; fail("the queue did not empty: ${repo.pending.value} left") }
            fun next(): okhttp3.mockwebserver.RecordedRequest {
                repeat(500) { idle(); server.takeRequest(10, TimeUnit.MILLISECONDS)?.let { return it } }
                fail("no request"); throw IllegalStateException()
            }

            server.enqueue(MockResponse().setBody(habit))
            val local = habits.create(buildJsonObject { put("name", "Read"); put("schedule", buildJsonObject { put("type", "daily") }) })
            assertTrue("shown at once under a temporary id", local != null && local.id < 0 && local.name == "Read")
            assertEquals("Added “Read” · Every day.", repo.error.value); repo.consumeError(repo.error.value!!)
            settle()
            next().let { r -> assertEquals("POST /api/habits", "${r.method} ${r.path}"); assertTrue(Json.parseToJsonElement(r.body.readUtf8()).jsonObject.containsKey("client_id")) }
            assertTrue(repo.snapshot.value!!.habits.list.let { l -> l.any { it.id == 7L } && l.none { it.id < 0 } })

            // A check-in shows at once.
            server.enqueue(MockResponse().setBody("""{"habit_id":7,"day":"2026-11-04","count":1}"""))
            assertTrue(habits.setCount(7, "2026-11-04", 1))
            assertEquals(1, repo.snapshot.value!!.habits.logs.single { it.habit_id == 7L }.count)
            settle()
            next().let { r ->
                assertEquals("PUT /api/habits/7/log", "${r.method} ${r.path}")
                assertEquals(buildJsonObject { put("day", "2026-11-04"); put("count", 1) }, Json.parseToJsonElement(r.body.readUtf8()))
            }
            // A refusal is dropped, the data reloaded (the server still has the check-in) and its message shown.
            val base = ApiClient.json.decodeFromString<AppData>(fixture)
            val serverHas = base.habits.copy(list = base.habits.list + ApiClient.json.decodeFromString<HabitDto>(habit), logs = base.habits.logs + HabitLogDto(7, "2026-11-04", 1))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"That day is too far back."}"""))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), base.copy(habits = serverHas))))
            assertTrue(habits.setCount(7, "2026-11-04", 0))
            assertTrue("shown at once", repo.snapshot.value!!.habits.logs.none { it.habit_id == 7L })
            settle()
            next(); assertEquals("GET /api/data?tasks=1", next().let { "${it.method} ${it.path}" })
            repeat(1000) { idle(); if (repo.error.value != null) return@repeat; Thread.sleep(5) }   // the message comes after the reload
            assertEquals("That day is too far back.", repo.error.value); repo.consumeError(repo.error.value!!)
            assertEquals(1, repo.snapshot.value!!.habits.logs.single { it.habit_id == 7L }.count)

            server.enqueue(MockResponse().setBody(habit.replace("false,\"created_at", "true,\"created_at")))
            assertTrue(habits.update(7, buildJsonObject { put("archived", true) })!!.archived)
            settle()
            next().let { assertEquals("PATCH /api/habits/7", "${it.method} ${it.path}") }

            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            assertTrue(habits.order(listOf(7)))
            settle()
            next().let { r -> assertEquals("POST /api/habits/order", "${r.method} ${r.path}"); assertEquals("""{"ids":[7]}""", r.body.readUtf8()) }

            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            assertTrue(habits.delete(7))
            assertTrue(repo.snapshot.value!!.habits.let { h -> h.list.none { it.id == 7L } && h.logs.none { it.habit_id == 7L } })
            settle()
            next().let { assertEquals("DELETE /api/habits/7", "${it.method} ${it.path}") }

            val before = server.requestCount
            assertFalse(shell.habitActions("b").setCount(1, "2026-11-04", 1))
            assertEquals("no request for another account", before, server.requestCount)
        } finally { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}

