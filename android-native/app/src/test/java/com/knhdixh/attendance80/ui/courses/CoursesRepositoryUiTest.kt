package com.knhdixh.attendance80.ui.courses

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
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
class CoursesRepositoryUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun trackedControlRollsBackAndPersists() = runBlocking {
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
            val course = base.courses.first().copy(name = "GR1 - Language")
            val sibling = CourseDto(99991, "GR2 - Language", tracked = false)
            val data = base.copy(courses = base.courses.map { if (it.id == course.id) course.copy(tracked = false) else it } + sibling)
            val fixture = ApiClient.json.encodeToString(AppData.serializer(), data)
            val updated = ApiClient.json.encodeToString(AppData.serializer(), data.copy(courses = data.courses.map { if (it.id == course.id) it.copy(tracked = true) else it }))
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
                Attendance80Theme { Shell(ShellState(data = snapshot, error = error), {}, {}, {}, initialRoute = "courses", now = clock.now(), zone = clock.zone(), onAction = vm::dispatch) }
            }
            ui.waitUntil(10_000) { ui.onAllNodesWithTag("courses-list").fetchSemanticsNodes().isNotEmpty() }
            fun row() = "course-row-${course.id}"
            fun scrollRow() { ui.onNodeWithTag("courses-list").performScrollToNode(hasTestTag(row())) }
            fun inRow(matcher: SemanticsMatcher) = matcher and hasAnyAncestor(hasTestTag(row()))
            scrollRow()
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Cannot update course\"}"))
            ui.onNode(inRow(hasTestTag("course-tracked"))).performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText("Cannot update course").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(repo.snapshot.value!!.courses.first().tracked)
            val failed = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/courses/${course.id}", failed.path)
            assertEquals("{\"tracked\":true}", org.json.JSONObject(failed.body.readUtf8()).toString())
            ui.mainClock.advanceTimeBy(6000)
            server.enqueue(MockResponse().setBody("{\"ok\":true}")); server.enqueue(MockResponse().setBody(updated))
            ui.onNode(inRow(hasTestTag("course-tracked"))).performClick()
            ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value!!.courses.first().tracked && server.requestCount >= 4 }
            assertEquals("/api/courses/${course.id}", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            assertEquals("/api/data", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            val trackedData = data.copy(courses = data.courses.map { if (it.id == course.id) it.copy(tracked = true) else it })
            val tagged = trackedData.copy(courses = trackedData.courses.map { if (it.id == course.id) it.copy(tag = "exam") else it })
            server.enqueue(MockResponse().setBody("{\"ok\":true}")); server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), tagged)))
            ui.waitUntil(10_000) { ui.onAllNodesWithText("Default tag: Normal").fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(inRow(hasText("Default tag: Normal"))).performClick(); ui.onNodeWithText("Exam").performClick()
            val tagRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/courses/${course.id}", tagRequest.path)
            assertEquals("{\"tag\":\"exam\"}", org.json.JSONObject(tagRequest.body.readUtf8()).toString())
            ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); server.requestCount >= 6 }
            assertEquals("/api/data", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            ui.waitUntil(10_000) { repo.snapshot.value?.courses?.first()?.tag == "exam" }
            val grouped = tagged.copy(courses = tagged.courses.map { if (it.id == course.id) it.copy(tracked = false) else if (it.id == sibling.id) it.copy(tracked = true) else it })
            server.enqueue(MockResponse().setBody("{\"ok\":true}")); server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), grouped)))
            ui.onNodeWithTag("courses-list").performScrollToNode(hasText("GR1")); ui.onNodeWithText("GR1").performClick(); ui.onNodeWithText("GR2").performClick()
            val groupRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/courses/tracked", groupRequest.path)
            val groupBody = org.json.JSONObject(groupRequest.body.readUtf8()).getJSONArray("updates")
            assertEquals(2, groupBody.length())
            assertEquals(setOf(course.id, sibling.id), (0 until groupBody.length()).map { groupBody.getJSONObject(it).getLong("id") }.toSet())
            assertFalse(groupBody.getJSONObject(0).getBoolean("tracked")); assertTrue(groupBody.getJSONObject(1).getBoolean("tracked"))
            ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); server.requestCount >= 8 }
            assertEquals("/api/data", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            ui.waitUntil(10_000) { repo.snapshot.value?.courses?.find { it.id == sibling.id }?.tracked == true }
            assertEquals(tagged.courses.filter { it.id != course.id && it.id != sibling.id }, repo.snapshot.value!!.courses.filter { it.id != course.id && it.id != sibling.id })
            val none = grouped.copy(courses = grouped.courses.map { if (it.id == sibling.id) it.copy(tracked = false) else it })
            server.enqueue(MockResponse().setBody("{\"ok\":true}")); server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), none)))
            ui.waitUntil(10_000) { ui.onAllNodesWithText("GR2").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("GR2").performClick(); ui.onNodeWithText("Not in this course").performClick()
            val noneRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/courses/tracked", noneRequest.path)
            val noneUpdates = org.json.JSONObject(noneRequest.body.readUtf8()).getJSONArray("updates")
            assertEquals(2, noneUpdates.length()); assertFalse(noneUpdates.getJSONObject(0).getBoolean("tracked")); assertFalse(noneUpdates.getJSONObject(1).getBoolean("tracked"))
            ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); server.requestCount >= 10 }
            assertEquals("/api/data", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            ui.waitUntil(10_000) { repo.snapshot.value?.courses?.find { it.id == sibling.id }?.tracked == false && !repo.loading.value }
            var current = none
            listOf("https://example.org", "https://example.org/second", "").forEachIndexed { index, value ->
                val canonical = when (index) { 0 -> "https://example.org/"; 1 -> value; else -> null }
                current = current.copy(courses = current.courses.map { if (it.id == course.id) it.copy(recording_url = canonical) else it })
                server.enqueue(MockResponse().setBody("{\"ok\":true}")); server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), current)))
                val button = if (index == 0) "Add link" else "Edit link"
                scrollRow(); ui.onNode(inRow(hasText(button))).performClick()
                ui.onNodeWithTag("recording-url").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(value)) }
                ui.onNodeWithText("Save").performClick()
                ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); server.requestCount >= 11 + index * 2 }
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/api/courses/${course.id}", request.path)
                val recordingBody = org.json.JSONObject(request.body.readUtf8())
                assertEquals(1, recordingBody.length())
                assertEquals(value, recordingBody.getString("recording_url"))
                ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); server.requestCount >= 12 + index * 2 }
                assertEquals("/api/data", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
                ui.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value?.courses?.first()?.recording_url == canonical }
            }
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Paste the address of the recordings page (https://…).\"}"))
            scrollRow(); ui.onNode(inRow(hasText("Add link"))).performClick()
            ui.onNodeWithTag("recording-url").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("bad address")) }
            ui.onNodeWithText("Save").performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText("Paste the address of the recordings page (https://…).").fetchSemanticsNodes().isNotEmpty() }
            val invalid = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/courses/${course.id}", invalid.path)
            assertEquals("{\"recording_url\":\"bad address\"}", org.json.JSONObject(invalid.body.readUtf8()).toString())
            assertNull(repo.snapshot.value!!.courses.first().recording_url)
        } finally { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}
