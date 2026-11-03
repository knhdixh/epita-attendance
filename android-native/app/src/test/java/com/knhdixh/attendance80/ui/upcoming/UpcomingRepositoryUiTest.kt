package com.knhdixh.attendance80.ui.upcoming

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
class UpcomingRepositoryUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun upcomingAttendanceUsesRepository() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("upcoming-ui").toFile()
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
                Attendance80Theme { Shell(ShellState(data = snapshot, error = error, session = "token"), {}, {}, {}, initialRoute = "upcoming", now = clock.now(), zone = clock.zone(), onAction = model::dispatch, onErrorConsumed = { model.consumeError(it, "token") }) }
            }
            server.enqueue(MockResponse().setBody("{}"))
            ui.waitUntil(10000) { ui.onAllNodesWithTag("upcoming-list").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("upcoming-list").performScrollToNode(hasText("Lecture"))
            ui.onNodeWithText("Attended").performClick()
            val request = server.takeRequest(10, TimeUnit.SECONDS)
            assertEquals("/api/attendance/7", request!!.path)
            assertEquals("PATCH", request.method)
            assertTrue(request.body.readUtf8().contains("present"))
        } finally { scope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
    @Test fun laterDayRemainsVisibleThroughNullMutationAndCanonicalRefresh() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("upcoming-ui").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val clock = object : Clock { override fun now() = 1793198400000L; override fun zone() = ZoneId.of("Europe/Paris") }
            val token = object : TokenStore {
                override val token = MutableStateFlow<String?>("token"); override val isLoaded = MutableStateFlow(true)
                override suspend fun awaitToken() = token.value
                override suspend fun setToken(token: String) { this.token.value = token }
                override suspend fun clearToken() { token.value = null }
            }
            val api = ApiClient.createApi(server.url("/").toString(), { token.currentToken() }, {})
            val repo = DataRepository(api, dir, clock) { token.currentToken() }
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
            val friday = java.time.LocalDate.parse("2026-10-30").atStartOfDay(clock.zone()).toInstant().toEpochMilli()
            val data = base.copy(courses = listOf(CourseDto(1, "Course")), events = (1L..8L).map { EventDto(it, 1, "Earlier $it", start_at = clock.now() + it * 60000, end_at = clock.now() + (it + 1) * 60000) } + EventDto(99, 1, "Friday lecture", start_at = friday + 3600000, end_at = friday + 7200000))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), data)))
            repo.start(); assertTrue(repo.refresh()); server.takeRequest(2, TimeUnit.SECONDS)
            val container = object : AppContainer {
                override val context: Context = ApplicationProvider.getApplicationContext(); override val clock = clock
                override val tokenStore = token; override val api: Api = api; override val dataRepository = repo
                override val authRepository: AuthRepository get() = error("unused")
                override val calendarScope = Scope(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("scope.preferences_pb") })
            }
            val shell = ShellViewModel(container)
            val upcoming = UpcomingViewModel()
            var available by mutableStateOf(true)
            ui.setContent { val snapshot by repo.snapshot.collectAsState()
                Attendance80Theme { UpcomingScreen(if (available) snapshot else null, clock.now(), clock.zone(), 0, {}, {}, shell::dispatch, upcoming) }
            }
            ui.waitUntil(10000) { ui.onAllNodesWithTag("upcoming-day-2026-10-30").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("upcoming-day-2026-10-30").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("Friday lecture").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Friday lecture").assertIsDisplayed()
            ui.runOnIdle { available = false; upcoming.update(null, java.time.Clock.fixed(java.time.Instant.ofEpochMilli(clock.now()), clock.zone()), clock.zone(), 0) }
            ui.waitUntil(10000) { upcoming.state.value == null && ui.onAllNodesWithTag("upcoming-list").fetchSemanticsNodes().isEmpty() }
            ui.runOnIdle { available = true; upcoming.update(repo.snapshot.value, java.time.Clock.fixed(java.time.Instant.ofEpochMilli(clock.now()), clock.zone()), clock.zone(), 0) }
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); upcoming.state.value != null && ui.onAllNodesWithTag("upcoming-list").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Friday lecture").assertIsDisplayed()
            server.enqueue(MockResponse().setBody("{}"))
            ui.onNode(hasText("Will attend") and hasAnyAncestor(hasTestTag("session-row-99"))).performClick()
            assertEquals("/api/attendance/99", server.takeRequest(10, TimeUnit.SECONDS)!!.path)
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value?.events?.find { it.id == 99L }?.status == "present" && upcoming.state.value?.data?.events?.find { it.id == 99L }?.status == "present" }
            ui.onNodeWithText("Friday lecture").assertIsDisplayed()
            val canonical = data.copy(events = data.events.map { if (it.id == 99L) it.copy(status = "present", note = "Canonical") else it })
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), canonical)))
            assertTrue(repo.refresh())
            assertEquals("/api/data?tasks=1", server.takeRequest(10, TimeUnit.SECONDS)!!.path)
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); upcoming.state.value?.data?.events?.find { it.id == 99L }?.note == "Canonical" }
            ui.onNodeWithText("Friday lecture").assertIsDisplayed()
            ui.onNodeWithText("Canonical").assertIsDisplayed()
            Unit
        } finally { scope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}
