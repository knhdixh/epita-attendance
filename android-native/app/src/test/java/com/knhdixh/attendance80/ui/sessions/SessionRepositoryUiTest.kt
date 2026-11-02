package com.knhdixh.attendance80.ui.sessions

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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionRepositoryUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun rejectedStatusShowsErrorAndCanonicalCatchup() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("session-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
            val data = base.copy(courses = listOf(CourseDto(1, "Course")), events = listOf(EventDto(7, 1, "Lecture", start_at = clock.now() - 60000, end_at = clock.now())))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), data)))
            repo.start(); assertTrue(repo.refresh()); server.takeRequest(2, TimeUnit.SECONDS)
            val container = object : AppContainer {
                override val context: Context = ApplicationProvider.getApplicationContext(); override val clock = clock
                override val tokenStore = token; override val api: Api = api; override val dataRepository = repo
                override val authRepository: AuthRepository get() = error("unused")
                override val calendarScope = Scope(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("scope.preferences_pb") })
            }
            val model = ShellViewModel(container)
            ui.setContent { val snapshot by repo.snapshot.collectAsState(); val error by repo.error.collectAsState()
                Attendance80Theme { Shell(ShellState(data = snapshot, error = error, session = "token"), {}, {}, {}, initialRoute = "session/7", now = clock.now(), zone = clock.zone(), onAction = model::dispatch, onErrorConsumed = { model.consumeError(it, "token") }) }
            }
            val canonical = HomeworkDto(88, 1, "Server catch-up", kind = "catchup", event_id = 7)
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Attendance rejected\"}"))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(HomeworkDto.serializer(), canonical)))
            ui.mainClock.autoAdvance = false
            ui.onNodeWithText("Absent").performClick()
            try { ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); ui.mainClock.advanceTimeBy(16); repo.snapshot.value?.homework?.any { it.id == 88L } == true && ui.onAllNodesWithText("Attendance rejected").fetchSemanticsNodes().isNotEmpty() } }
            catch (e: Throwable) { throw AssertionError("requests=${server.requestCount} error=${repo.error.value} homework=${repo.snapshot.value?.homework} shown=${ui.onAllNodesWithText("Attendance rejected").fetchSemanticsNodes().size}", e) }
            ui.onNodeWithText("Attendance rejected").assertIsDisplayed()
            assertNull(repo.snapshot.value!!.events.single().status)
            assertEquals(canonical, repo.snapshot.value!!.homework.single())
            assertEquals("/api/attendance/7", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            assertEquals("/api/catchups", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        } finally { scope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}
