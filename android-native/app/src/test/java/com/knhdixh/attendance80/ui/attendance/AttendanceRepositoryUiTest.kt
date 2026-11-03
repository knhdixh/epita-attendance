package com.knhdixh.attendance80.ui.attendance

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.*
import com.knhdixh.attendance80.auth.*
import com.knhdixh.attendance80.data.*
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class AttendanceRepositoryUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun buttonUsesRepositoryAndShowsRollbackError() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("attendance-ui").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val clock = object : Clock { override fun now() = 1794000000000L; override fun zone() = ZoneId.of("Europe/Paris") }
            val token = object : TokenStore {
                override val token = MutableStateFlow<String?>("token"); override val isLoaded = MutableStateFlow(true)
                override suspend fun awaitToken() = token.value
                override suspend fun setToken(token: String) { this.token.value = token }
                override suspend fun clearToken() { token.value = null }
            }
            val api = ApiClient.createApi(server.url("/").toString(), { token.currentToken() }, {})
            val repo = DataRepository(api, dir, clock) { token.currentToken() }
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data.json")!!.readText())
            val data = base.copy(events = base.events.mapIndexed { i, e -> e.copy(status = if (i == 0) null else "present") })
            val fixture = ApiClient.json.encodeToString(AppData.serializer(), data)
            val updated = ApiClient.json.encodeToString(AppData.serializer(), data.copy(events = data.events.map { it.copy(status = "present") }))
            repo.start(); server.enqueue(MockResponse().setBody(fixture)); assertTrue(repo.refresh()); server.takeRequest(2, TimeUnit.SECONDS)
            val store = PreferenceDataStoreFactory.create(scope = storeScope) { dir.resolve("scope.preferences_pb") }
            val container = object : AppContainer {
                override val context: Context = ApplicationProvider.getApplicationContext(); override val clock = clock
                override val tokenStore = token; override val api: Api = api; override val dataRepository = repo
                override val authRepository: AuthRepository get() = error("unused"); override val calendarScope = Scope(store)
            }
            val vm = ShellViewModel(container)
            ui.setContent {
                val snapshot by repo.snapshot.collectAsState(); val error by repo.error.collectAsState()
                Attendance80Theme { Shell(ShellState(data = snapshot, error = error), {}, {}, {}, initialRoute = "attendance", now = clock.now(), zone = clock.zone(), onAction = vm::dispatch) }
            }
            ui.waitUntil(10_000) { ui.onAllNodesWithText("Mark 1 as attended").fetchSemanticsNodes().isNotEmpty() }
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Cannot mark attendance\"}"))
            ui.onNodeWithText("Mark 1 as attended").performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText("Cannot mark attendance").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(null, repo.snapshot.value!!.events.first().status)
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8().contains("\"ids\":[${data.events.first().id}]"))
            ui.mainClock.advanceTimeBy(6000)
            server.enqueue(MockResponse().setBody("{\"updated\":1}")); server.enqueue(MockResponse().setBody(updated))
            ui.onNodeWithText("Mark 1 as attended").performClick()
            try { ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value!!.events.first().status == "present" && server.requestCount >= 4 } } catch (e: Throwable) { throw AssertionError("requests=${server.requestCount}, status=${repo.snapshot.value!!.events.first().status}, error=${repo.error.value}", e) }
            assertEquals("/api/attendance/bulk", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            assertEquals("/api/data?tasks=1", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        } finally { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}
