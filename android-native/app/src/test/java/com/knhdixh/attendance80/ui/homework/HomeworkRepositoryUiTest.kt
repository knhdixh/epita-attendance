package com.knhdixh.attendance80.ui.homework

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
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class HomeworkRepositoryUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun quickAddReturnsToTitleAfterCanonicalCreate() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory(java.nio.file.Paths.get("/tmp/epita-native-night"), "homework-ui").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            val sessionStart = clock.now() + 3_600_000
            val sessionLocal = java.time.Instant.ofEpochMilli(sessionStart).atZone(clock.zone())
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText()).copy(
                courses = listOf(CourseDto(5, "Session course")), events = listOf(EventDto(51, 5, "Session", start_at = sessionStart, end_at = sessionStart + 3600000)))
            repo.start(); server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), base)))
            assertTrue(repo.refresh()); server.takeRequest(2, TimeUnit.SECONDS)
            val container = object : AppContainer {
                override val context: Context = ApplicationProvider.getApplicationContext(); override val clock = clock
                override val tokenStore = token; override val api: Api = api; override val dataRepository = repo
                override val authRepository: AuthRepository get() = error("unused")
                override val calendarScope = Scope(PreferenceDataStoreFactory.create(scope = storeScope) { dir.resolve("scope.preferences_pb") })
            }
            val vm = ShellViewModel(container)
            ui.setContent {
                val snapshot by repo.snapshot.collectAsState(); val error by repo.error.collectAsState(); val pending by vm.pendingHomeworkDelete.collectAsState()
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, 2f)) { Attendance80Theme { Shell(ShellState(data = snapshot, session = "token", error = error), {}, {}, {}, initialRoute = "homework", now = clock.now(), zone = clock.zone(), onAction = vm::dispatch,
                    onErrorConsumed = { vm.consumeError(it, "token") }, pendingDelete = pending, onDeleteHomework = { vm.deleteHomework("token", it) }, onFinishHomeworkDelete = { undo, expected -> vm.finishHomeworkDelete(undo, expected) },
                    onSaveHomework = { id, body -> vm.saveHomework("token", id, body) }) } }
            }
            ui.waitUntil(10000) { ui.onAllNodesWithText("Nothing due. Press Q to add homework.").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Add homework").performClick()
            ui.onNodeWithText("What's due").assertIsDisplayed().assertIsFocused()
            ui.onNodeWithText("What's due").performTextInput("First task")
            ui.onNodeWithText("Course: No course").performScrollTo().performClick()
            ui.onNodeWithText("Session course").performClick()
            ui.onNodeWithText("Notes").performScrollTo().performTextInput("First notes")
            ui.onNodeWithText("Notes").assertIsFocused()
            val expected = buildJsonObject { put("title", "First task"); put("notes", "First notes"); put("course_id", 5); put("due_date", "2026-10-29"); put("due_time", JsonNull); put("tz", "Europe/Paris"); put("remind_minutes", 1440); put("effort_minutes", JsonNull) }
            // The create is queued at once (the form is free for the next one); the server refuses it later: dropped, reloaded, message shown.
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Create refused\"}"))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), base)))
            ui.onNode(hasText("Add homework") and hasClickAction()).performScrollTo().performClick()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.error.value == "Create refused" }
            val failed = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", failed.method); assertEquals("/api/homework", failed.path)
            val failedBody = Json.parseToJsonElement(failed.body.readUtf8()).jsonObject
            assertTrue("a client_id travels with every create, so a retry is safe", failedBody["client_id"]!!.jsonPrimitive.content.length in 8..64)
            assertEquals(expected, JsonObject(failedBody - "client_id"))
            assertEquals("/api/data?tasks=1", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            assertTrue("nothing of the refused task is left", repo.snapshot.value!!.homework.isEmpty())
            ui.onNodeWithText("What's due").performTextInput("First task")
            ui.onNodeWithText("Notes").performScrollTo().performTextInput("First notes")
            val saved = HomeworkDto(88, course_id = 5, title = "First task", notes = "First notes", due_date = "2026-10-29", remind_minutes = 1440)
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(HomeworkDto.serializer(), saved)))
            ui.onNode(hasText("Add homework") and hasClickAction()).performScrollTo().performClick()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value?.homework?.any { it.id == 88L } == true }
            val first = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", first.method); assertEquals("/api/homework", first.path); assertEquals(expected, JsonObject(Json.parseToJsonElement(first.body.readUtf8()).jsonObject - "client_id"))
            ui.onNodeWithText("What's due").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(""))).assertIsDisplayed().assertIsFocused()
            ui.onNodeWithText("What's due").performTextInput("Second task")
            ui.onNodeWithText("Course: Session course").performScrollTo().assertExists()
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(HomeworkDto.serializer(), saved.copy(id = 89, title = "Second task", notes = ""))))
            ui.onNode(hasText("Add homework") and hasClickAction()).performScrollTo().performClick()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value?.homework?.any { it.id == 89L } == true }
            val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", second.method); assertEquals("/api/homework", second.path)
            assertEquals(buildJsonObject { put("title", "Second task"); put("notes", ""); put("course_id", 5); put("due_date", "2026-10-29"); put("due_time", JsonNull); put("tz", "Europe/Paris"); put("remind_minutes", 1440); put("effort_minutes", JsonNull) }, JsonObject(Json.parseToJsonElement(second.body.readUtf8()).jsonObject - "client_id"))
            assertEquals(setOf(88L, 89L), repo.snapshot.value!!.homework.map { it.id }.toSet())
            Unit
        } finally { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
    @Test fun formAndDeletionUseRealRepository() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory(java.nio.file.Paths.get("/tmp/epita-native-night"), "homework-ui").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            val sessionStart = clock.now() + 3_600_000
            val sessionLocal = java.time.Instant.ofEpochMilli(sessionStart).atZone(clock.zone())
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText()).copy(
                courses = listOf(CourseDto(5, "Session course")), events = listOf(EventDto(51, 5, "Session", start_at = sessionStart, end_at = sessionStart + 3600000)))
            repo.start(); server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), base)))
            assertTrue(repo.refresh()); server.takeRequest(2, TimeUnit.SECONDS)
            val container = object : AppContainer {
                override val context: Context = ApplicationProvider.getApplicationContext(); override val clock = clock
                override val tokenStore = token; override val api: Api = api; override val dataRepository = repo
                override val authRepository: AuthRepository get() = error("unused")
                override val calendarScope = Scope(PreferenceDataStoreFactory.create(scope = storeScope) { dir.resolve("scope.preferences_pb") })
            }
            val vm = ShellViewModel(container)
            ui.setContent {
                val snapshot by repo.snapshot.collectAsState(); val error by repo.error.collectAsState(); val pending by vm.pendingHomeworkDelete.collectAsState()
                Attendance80Theme { Shell(ShellState(data = snapshot, session = "token", error = error), {}, {}, {}, initialRoute = "homework", now = clock.now(), zone = clock.zone(), onAction = vm::dispatch,
                    onErrorConsumed = { vm.consumeError(it, "token") }, pendingDelete = pending, onDeleteHomework = { vm.deleteHomework("token", it) }, onFinishHomeworkDelete = { undo, expected -> vm.finishHomeworkDelete(undo, expected) },
                    onSaveHomework = { id, body -> vm.saveHomework("token", id, body) }) }
            }
            ui.waitUntil(10000) { ui.onAllNodesWithText("Nothing due. Press Q to add homework.").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Add homework").performClick()
            ui.onNodeWithText("What's due").performTextInput("Canonical task")
            ui.onNodeWithText("Notes").performTextInput("Some notes")
            ui.onNodeWithText("Reminder: 1 day before").performScrollTo().performClick()
            ui.onNodeWithText("No reminder").performClick()
            val saved = HomeworkDto(88, title = "Canonical task", notes = "Some notes", due_date = "2026-10-26", due_at = clock.now() + 100000, sync_error = "quota")
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(HomeworkDto.serializer(), saved)))
            ui.onNode(hasText("Add homework") and hasClickAction()).performScrollTo().performClick()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value?.homework?.any { it.id == 88L } == true }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method); assertEquals("/api/homework", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("Europe/Paris", body.getValue("tz").jsonPrimitive.content)
            assertEquals("Some notes", body.getValue("notes").jsonPrimitive.content)
            for (field in listOf("course_id", "due_time", "remind_minutes", "effort_minutes")) assertEquals(JsonNull, body[field])
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.error.value != null }
            assertEquals("Saved, but Google Calendar sync failed: quota", repo.error.value)
            ui.onNodeWithText("What's due").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
            ui.onNodeWithText("What's due").performTextInput("Due at session")
            ui.onNodeWithText("Course: No course").performScrollTo().performClick()
            ui.onNodeWithText("Session course").performClick()
            ui.onNodeWithText("Due at a session: — or pick a session —").performScrollTo().performClick()
            ui.onNodeWithText("${com.knhdixh.attendance80.logic.Dates.formatDate(sessionStart, clock.zone())} ${com.knhdixh.attendance80.logic.Dates.formatTime(sessionStart, clock.zone())}").performClick()
            val sessionTime = sessionLocal.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            val sessionRow = HomeworkDto(89, course_id = 5, title = "Due at session", due_date = sessionLocal.toLocalDate().toString(), due_time = sessionTime, due_at = sessionStart, remind_minutes = 1440)
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(HomeworkDto.serializer(), sessionRow)))
            ui.onNode(hasText("Add homework") and hasClickAction()).performScrollTo().performClick()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value?.homework?.any { it.id == 89L } == true }
            val sessionRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            val sessionBody = Json.parseToJsonElement(sessionRequest.body.readUtf8()).jsonObject
            assertEquals(5, sessionBody.getValue("course_id").jsonPrimitive.int)
            assertEquals(sessionLocal.toLocalDate().toString(), sessionBody.getValue("due_date").jsonPrimitive.content)
            assertEquals(sessionTime, sessionBody.getValue("due_time").jsonPrimitive.content)
            assertEquals("Europe/Paris", sessionBody.getValue("tz").jsonPrimitive.content)
            ui.onNodeWithText("Course: Session course").performScrollTo().assertExists()
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            ui.runOnIdle { vm.deleteHomework("token", sessionRow); vm.finishHomeworkDelete(false) }
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value?.homework?.none { it.id == 89L } == true }
            assertEquals("/api/homework/89", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            ui.onNodeWithText("Cancel").performScrollTo().performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithText("Canonical task").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Canonical task").performClick()
            ui.onNodeWithText("Notes").performTextReplacement("Edited draft")
            // The edit is queued at once; the server refuses it: dropped, the data reloaded (the server still has the old notes), message shown.
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Edit refused\"}"))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), base.copy(homework = listOf(saved.copy(sync_error = null))))))
            ui.onNodeWithText("Save").performScrollTo().performClick()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.error.value == "Edit refused" }
            val failedEdit = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("PATCH", failedEdit.method); assertEquals("/api/homework/88", failedEdit.path)
            assertEquals("/api/data?tasks=1", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            assertEquals("Some notes", repo.snapshot.value!!.homework.single().notes)
            ui.waitUntil(10000) { ui.onAllNodesWithText("Canonical task").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Canonical task").performClick()
            ui.onNodeWithText("Notes").performTextReplacement("Edited draft")
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(HomeworkDto.serializer(), saved.copy(notes = "Edited draft", sync_error = null))))
            ui.onNodeWithText("Save").performScrollTo().performClick()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value?.homework?.singleOrNull()?.notes == "Edited draft" }
            val edit = server.takeRequest(5, TimeUnit.SECONDS)!!
            val editBody = Json.parseToJsonElement(edit.body.readUtf8()).jsonObject
            assertEquals("Edited draft", editBody.getValue("notes").jsonPrimitive.content)
            for (field in listOf("course_id", "due_time", "remind_minutes", "effort_minutes")) assertEquals(JsonNull, editBody[field])
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Done: Canonical task").fetchSemanticsNodes().isNotEmpty() }
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(HomeworkDto.serializer(), saved.copy(notes = "Edited draft", done = true, sync_error = null))))
            ui.onNodeWithContentDescription("Done: Canonical task").performClick()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value?.homework?.singleOrNull()?.done == true }
            val doneRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("a tick is a complete, not a PATCH of done", "/api/homework/88/complete", doneRequest.path)
            assertEquals(buildJsonObject { }, Json.parseToJsonElement(doneRequest.body.readUtf8()))
            ui.waitUntil(10000) { ui.onAllNodesWithText("Done 1 ▾").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Done 1 ▾").performClick()
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(HomeworkDto.serializer(), saved.copy(notes = "Edited draft", sync_error = null))))
            ui.onNodeWithContentDescription("Done: Canonical task").performClick()
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value?.homework?.singleOrNull()?.done == false }
            assertEquals("/api/homework/88/uncomplete", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            ui.waitUntil(10000) { ui.onAllNodesWithText("Delete").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Delete").performScrollTo().performClick()
            ui.onNodeWithText("Cancel").performClick()
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            ui.onNodeWithText("Delete").performClick(); ui.onAllNodesWithText("Delete").onLast().performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("nav-today").performClick()
            ui.onNodeWithText("Undo").performClick()
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            ui.onNodeWithTag("nav-homework").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithText("Canonical task").fetchSemanticsNodes().isNotEmpty() }
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Cannot delete\"}"))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), base.copy(homework = listOf(saved.copy(notes = "Edited draft", sync_error = null))))))
            ui.onNodeWithText("Delete").performClick(); ui.onAllNodesWithText("Delete").onLast().performClick()
            ui.waitUntil(5000) { vm.pendingHomeworkDelete.value != null }
            ui.runOnIdle { vm.finishHomeworkDelete(false) }
            ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.error.value == "Cannot delete" }
            assertEquals("DELETE", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            assertEquals("/api/data?tasks=1", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            assertEquals(88L, repo.snapshot.value!!.homework.single().id)
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            // The task went when it was deleted and came back with the reload: the open form is gone, open it again.
            ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); ui.onAllNodesWithText("Canonical task").fetchSemanticsNodes().isNotEmpty() }
            if (ui.onAllNodesWithText("Delete").fetchSemanticsNodes().isEmpty()) ui.onNodeWithText("Canonical task").performClick()
            ui.waitUntil(5000) { ui.onAllNodesWithText("Delete").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Delete").performClick(); ui.onAllNodesWithText("Delete").onLast().performClick()
            ui.waitUntil(5000) { vm.pendingHomeworkDelete.value != null }
            ui.runOnIdle { vm.finishHomeworkDelete(false); vm.finishHomeworkDelete(false) }
            ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value!!.homework.isEmpty() }
            val finalDelete = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("DELETE", finalDelete.method); assertEquals("/api/homework/88", finalDelete.path)
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), base.copy(homework = listOf(saved)))))
            assertTrue(repo.refresh()); server.takeRequest(5, TimeUnit.SECONDS)
            ui.runOnIdle { vm.deleteHomework("token", saved) }
            val stale = vm.pendingHomeworkDelete.value!!
            ui.runOnIdle { vm.deleteHomework("token", saved) }
            assertEquals(stale, vm.pendingHomeworkDelete.value)
            ui.runOnIdle { vm.finishHomeworkDelete(true, stale); vm.deleteHomework("token", saved) }
            val current = vm.pendingHomeworkDelete.value!!
            assertNotEquals(stale.sequence, current.sequence)
            ui.runOnIdle { vm.finishHomeworkDelete(false, stale); vm.finishHomeworkDelete(true, stale) }
            assertEquals(current, vm.pendingHomeworkDelete.value)
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            ui.runOnIdle { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS) }
            ui.waitUntil(10000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); repo.snapshot.value!!.homework.isEmpty() }
            assertEquals("/api/homework/88", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), base.copy(homework = listOf(saved)))))
            assertTrue(repo.refresh()); server.takeRequest(5, TimeUnit.SECONDS)
            ui.runOnIdle { vm.deleteHomework("token", saved) }
            token.setToken("other-token"); repo.start()
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), base)))
            assertTrue(repo.refresh()); server.takeRequest(5, TimeUnit.SECONDS)
            ui.runOnIdle { vm.finishHomeworkDelete(false) }
            assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS))
            assertTrue(repo.snapshot.value!!.homework.isEmpty())
            Unit
        } finally { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}
