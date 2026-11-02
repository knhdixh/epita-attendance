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
class SessionSelectionApiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun hiddenSelectionNeverReachesBulkApi() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("selection-api").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val clock = object : Clock { override fun now() = 1793600040000L; override fun zone() = ZoneId.of("UTC") }
            val token = object : TokenStore {
                override val token = MutableStateFlow<String?>("owner"); override val isLoaded = MutableStateFlow(true)
                override suspend fun awaitToken() = token.value
                override suspend fun setToken(token: String) { this.token.value = token }
                override suspend fun clearToken() { token.value = null }
            }
            val api = ApiClient.createApi(server.url("/").toString(), { token.currentToken() }, {})
            val repo = DataRepository(api, dir, clock) { token.currentToken() }
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
            val events = listOf(EventDto(7, 1, "First", start_at = clock.now()+60000, end_at = clock.now()+120000), EventDto(8, 1, "Second", start_at = clock.now()+120000, end_at = clock.now()+180000))
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
            ui.setContent { val snapshot by repo.snapshot.collectAsState(); Attendance80Theme { Shell(ShellState(data = snapshot, session = "owner"), {}, {}, {}, initialRoute = "sessions", now = clock.now(), zone = clock.zone(), onAction = vm::dispatch) } }
            ui.waitUntil(10000) { ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() }
            for (id in listOf(7, 8)) { ui.onNodeWithTag("sessions-list").performScrollToNode(hasTestTag("session-row-$id")); ui.onNode(hasText("Select") and hasAnyAncestor(hasTestTag("session-row-$id"))).performClick() }
            ui.onNodeWithTag("sessions-list").performScrollToNode(hasSetTextAction())
            ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("sessions-list"))).performTextInput("First")
            ui.waitUntil(10000) { ui.onAllNodes(hasText("Deselect") and hasAnyAncestor(hasTestTag("session-row-7"))).fetchSemanticsNodes().isNotEmpty() }
            server.enqueue(MockResponse().setBody("{\"updated\":1}")); server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), data)))
            ui.onNodeWithTag("sessions-list").performScrollToNode(hasText("Absent")); ui.onNodeWithText("Absent").performClick()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); server.requestCount >= 2 }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/attendance/bulk", request.path)
            val ids = org.json.JSONObject(request.body.readUtf8()).getJSONArray("ids")
            assertEquals(1, ids.length()); assertEquals(7, ids.getInt(0))
        } finally { scope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}
