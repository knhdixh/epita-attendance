package com.knhdixh.attendance80.ui.sessions

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.*
import com.knhdixh.attendance80.auth.*
import com.knhdixh.attendance80.data.*
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.shell.ShellViewModel
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionMutationTest {
    @Test fun queuedActionsCannotCrossAccountReplacement() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("owner-queue").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val clock = object : Clock { override fun now() = 1794000000000L; override fun zone() = ZoneId.of("Europe/Paris") }
            val token = object : TokenStore {
                override val token = MutableStateFlow<String?>("a"); override val isLoaded = MutableStateFlow(true)
                override suspend fun awaitToken() = token.value
                override suspend fun setToken(token: String) { this.token.value = token }
                override suspend fun clearToken() { token.value = null }
            }
            val api = ApiClient.createApi(server.url("/").toString(), { token.currentToken() }, {})
            val repo = DataRepository(api, dir, clock) { token.currentToken() }
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
            val data = base.copy(courses = listOf(CourseDto(7, "Overlap")), events = listOf(EventDto(7, 7, "Overlap", start_at = clock.now()-60000, end_at = clock.now())))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), data)))
            repo.start(); assertTrue(repo.refresh()); withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            val container = object : AppContainer {
                override val context: Context = ApplicationProvider.getApplicationContext(); override val clock = clock
                override val tokenStore = token; override val api: Api = api; override val dataRepository = repo
                override val authRepository: AuthRepository get() = error("unused")
                override val calendarScope = Scope(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("scope.preferences_pb") })
            }
            val vm = ShellViewModel(container)
            listOf(PaletteAction.CourseField(7, "tag", "exam"), PaletteAction.CourseGroups(listOf(7), 7), PaletteAction.SyncCalendar(7), PaletteAction.ToggleHomework(7, true), PaletteAction.CheckHabit(7, "2026-10-31", 1), PaletteAction.AddHabit("Read every day"), PaletteAction.AddCountdown("Exam tomorrow"), PaletteAction.SessionStatus(7, "absent"), PaletteAction.BulkAttendance(listOf(7))).forEach(vm::dispatch)
            token.setToken("b"); repo.start()
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), data)))
            assertTrue(repo.refresh()); withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            scheduler.advanceUntilIdle()
            assertNull(withContext(Dispatchers.IO) { server.takeRequest(500, TimeUnit.MILLISECONDS) })
            assertEquals("b", repo.sessionSnapshot.value.first); assertEquals(data, repo.snapshot.value)
            assertFalse(repo.mutate<Unit>({ it }, { throw IllegalStateException("same message") }, { d, _ -> d }, expectedOwner = "b"))
            assertEquals("same message", repo.error.value)
            vm.consumeError("same message", "a"); assertEquals("same message", repo.error.value)
            vm.consumeError("same message", "b"); assertNull(repo.error.value)
        } finally { scope.cancel(); server.shutdown(); dir.deleteRecursively(); Dispatchers.resetMain() }
    }
    @Test fun rejectedAttendanceStillCreatesCanonicalCatchupAndRetainsError() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("session-mutation").toFile()
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
            val event = EventDto(7, 1, "Lecture", start_at = clock.now() - 60000, end_at = clock.now())
            val data = base.copy(courses = listOf(CourseDto(1, "Course")), events = listOf(event))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), data)))
            repo.start(); assertTrue(repo.refresh()); withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            val container = object : AppContainer {
                override val context: Context = ApplicationProvider.getApplicationContext(); override val clock = clock
                override val tokenStore = token; override val api: Api = api; override val dataRepository = repo
                override val authRepository: AuthRepository get() = error("unused")
                override val calendarScope = Scope(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("scope.preferences_pb") })
            }
            val model = ShellViewModel(container)
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Attendance rejected\"}"))
            val canonical = HomeworkDto(88, 1, "Server catch-up", kind = "catchup", event_id = 7)
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(HomeworkDto.serializer(), canonical)))
            model.dispatch(PaletteAction.SessionStatus(7, "absent"))
            withTimeout(10000) { while (repo.snapshot.value?.homework?.none { it.id == 88L } != false) { shadowOf(android.os.Looper.getMainLooper()).idle(); delay(20) } }
            val patch = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!
            assertEquals("/api/attendance/7", patch.path); assertEquals("{\"status\":\"absent\"}", patch.body.readUtf8())
            val catchup = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!
            assertEquals("/api/catchups", catchup.path)
            val catchupBody = org.json.JSONObject(catchup.body.readUtf8())
            assertEquals(7, catchupBody.getInt("event_id")); assertEquals("Europe/Paris", catchupBody.getString("tz")); assertEquals(2, catchupBody.length())
            assertNull(repo.snapshot.value!!.events.single().status)
            assertEquals(canonical, repo.snapshot.value!!.homework.single())
            assertEquals("Attendance rejected", repo.error.value)
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            model.dispatch(PaletteAction.SessionStatus(7, "present"))
            withTimeout(10000) { while (repo.snapshot.value?.homework?.isNotEmpty() != false) { shadowOf(android.os.Looper.getMainLooper()).idle(); delay(20) } }
            assertEquals("/api/attendance/7", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!.path)
            assertEquals("/api/homework/88", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!.path)
            assertEquals("present", repo.snapshot.value!!.events.single().status)
            for (preserved in listOf(canonical.copy(created_at = 1, updated_at = 2), canonical.copy(done = true))) {
                val prior = data.copy(events = listOf(event.copy(status = "absent")), homework = listOf(preserved))
                server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), prior)))
                assertTrue(repo.refresh()); withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
                server.enqueue(MockResponse().setBody("{\"ok\":true}"))
                model.dispatch(PaletteAction.SessionStatus(7, "present"))
                withTimeout(10000) { while (repo.snapshot.value?.events?.single()?.status != "present") { shadowOf(android.os.Looper.getMainLooper()).idle(); delay(20) } }
                assertEquals("/api/attendance/7", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!.path)
                assertEquals(preserved, repo.snapshot.value!!.homework.single())
                assertNull(withContext(Dispatchers.IO) { server.takeRequest(250, TimeUnit.MILLISECONDS) })
            }
            val future = data.copy(events = listOf(event.copy(start_at = clock.now() + 60000, end_at = clock.now() + 120000)))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), future)))
            assertTrue(repo.refresh()); withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            model.dispatch(PaletteAction.SessionStatus(7, "absent"))
            withTimeout(10000) { while (repo.snapshot.value?.events?.single()?.status != "absent") { shadowOf(android.os.Looper.getMainLooper()).idle(); delay(20) } }
            assertEquals("/api/attendance/7", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!.path)
            assertNull(withContext(Dispatchers.IO) { server.takeRequest(250, TimeUnit.MILLISECONDS) })
            server.enqueue(MockResponse().setBody("{\"updated\":1}"))
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), future.copy(events = listOf(future.events.single().copy(status = "excused"))))))
            model.dispatch(PaletteAction.BulkAttendance(listOf(7), "excused"))
            val bulk = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!
            assertEquals("/api/attendance/bulk", bulk.path)
            val bulkBody = org.json.JSONObject(bulk.body.readUtf8())
            assertEquals("excused", bulkBody.getString("status")); assertEquals(7, bulkBody.getJSONArray("ids").getInt(0)); assertEquals(1, bulkBody.getJSONArray("ids").length())
            assertEquals("/api/data?tasks=1", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!.path)
            withTimeout(10000) { while (repo.snapshot.value?.events?.single()?.status != "excused") delay(20) }
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            model.dispatch(PaletteAction.SessionStatus(7, null))
            val clear = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!
            assertEquals("/api/attendance/7", clear.path); assertEquals("{\"status\":null}", clear.body.readUtf8())
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            model.dispatch(PaletteAction.SessionField(7, "tag", null))
            val tag = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!
            assertEquals("/api/attendance/7", tag.path); assertEquals("{\"tag\":null}", tag.body.readUtf8())
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            model.dispatch(PaletteAction.SessionField(7, "note", "A note"))
            val note = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!
            assertEquals("/api/attendance/7", note.path); assertEquals("{\"note\":\"A note\"}", note.body.readUtf8())
            model.dispatch(PaletteAction.SessionStatus(7, "absent"), "different owner")
            assertNull(withContext(Dispatchers.IO) { server.takeRequest(300, TimeUnit.MILLISECONDS) })
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(AppData.serializer(), data)))
            assertTrue(repo.refresh()); withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Mark rejected\"}"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Catch-up rejected\"}"))
            model.dispatch(PaletteAction.SessionStatus(7, "absent"))
            assertEquals("/api/attendance/7", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!.path)
            assertEquals("/api/catchups", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!.path)
            withTimeout(10000) { while (repo.error.value != "Mark rejected") delay(20) }
            model.consumeError("Mark rejected", "old-owner")
            assertEquals("Mark rejected", repo.error.value)
            repo.consumeError("Mark rejected")
            withTimeout(10000) { while (repo.error.value != "Catch-up rejected") delay(20) }
        } finally { scope.cancel(); server.shutdown(); dir.deleteRecursively(); Dispatchers.resetMain() }
    }
}
