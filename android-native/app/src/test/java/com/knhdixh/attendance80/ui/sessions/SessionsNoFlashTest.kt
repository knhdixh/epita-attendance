package com.knhdixh.attendance80.ui.sessions

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
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
class SessionsNoFlashTest {
    @get:Rule val ui = createComposeRule()

    // The screen's own spinner replaces the whole list; the Shell's small refresh indicator is allowed.
    private fun assertListOnScreen() {
        assertEquals(1, ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().size)
        assertTrue(ui.onNodeWithTag("sessions-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 1000f)
    }

    /** Marking a row far down the list, and the minute ticking over, must not drop the list to a spinner or back to the top. */
    @Test fun markingARowAndAMinuteTickKeepTheListOnScreen() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("no-flash").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val start = 1793600040000L
            var nowMs by mutableStateOf(start)
            val clock = object : Clock { override fun now() = nowMs; override fun zone() = ZoneId.of("UTC") }
            val token = object : TokenStore {
                override val token = MutableStateFlow<String?>("owner"); override val isLoaded = MutableStateFlow(true)
                override suspend fun awaitToken() = token.value
                override suspend fun setToken(token: String) { this.token.value = token }
                override suspend fun clearToken() { token.value = null }
            }
            val api = ApiClient.createApi(server.url("/").toString(), { token.currentToken() }, {})
            val repo = DataRepository(api, dir, clock) { token.currentToken() }
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
            val events = (1L..40L).map { EventDto(it, 1, "Lecture $it", start_at = start + it * 86_400_000L, end_at = start + it * 86_400_000L + 3_600_000L) }
            val data = base.copy(courses = listOf(CourseDto(1, "Course")), events = events)
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), data)))
            repo.start(); assertTrue(repo.refresh()); server.takeRequest(2, TimeUnit.SECONDS)
            val container = object : AppContainer {
                override val context: Context = ApplicationProvider.getApplicationContext(); override val clock = clock
                override val tokenStore = token; override val api: Api = api; override val dataRepository = repo
                override val authRepository: AuthRepository get() = error("unused")
                override val calendarScope = Scope(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("scope.preferences_pb") })
            }
            val vm = ShellViewModel(container)
            ui.setContent {
                val snapshot by repo.snapshot.collectAsState()
                Attendance80Theme { Shell(ShellState(data = snapshot, session = "owner"), {}, {}, {}, initialRoute = "sessions", now = nowMs, zone = clock.zone(), onAction = vm::dispatch) }
            }
            ui.waitUntil(10000) { ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() }
            val row = hasTestTag("session-row-30")
            ui.onNodeWithTag("sessions-list").performScrollToNode(row)
            ui.onNode(row).assertIsDisplayed()
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            ui.onNode(hasText("Will attend") and hasAnyAncestor(row)).performClick()
            // The optimistic publish must not blank the list, even before the new derivation lands.
            assertListOnScreen()
            ui.onNode(row).assertIsDisplayed()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value!!.events.find { it.id == 30L }?.status == "present" }
            assertListOnScreen(); ui.onNode(row).assertIsDisplayed()
            assertEquals("/api/attendance/30", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            // A minute advance re-derives in the background and keeps the row where it was.
            nowMs += 60_000L; ui.waitForIdle()
            assertListOnScreen(); ui.onNode(row).assertIsDisplayed()
            ui.mainClock.advanceTimeBy(500); ui.waitForIdle()
            assertListOnScreen(); ui.onNode(row).assertIsDisplayed()
            Unit
        } finally { scope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}
