package com.knhdixh.attendance80.ui.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.knhdixh.attendance80.AppContainer
import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.auth.AuthRepository
import com.knhdixh.attendance80.auth.TokenStore
import com.knhdixh.attendance80.data.DataRepository
import com.knhdixh.attendance80.data.Scope
import com.knhdixh.attendance80.net.Api
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.ui.search.PaletteAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaletteDispatchTest {
    @Test fun bulkAndHabitActionsSendRequestsAndUpdateSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val server = MockWebServer(); server.start()
        val directory = Files.createTempDirectory("palette-dispatch").toFile()
        val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = storeScope) { directory.resolve("scope.preferences_pb") }
            val token = object : TokenStore {
                override val token = MutableStateFlow<String?>("token")
                override val isLoaded = MutableStateFlow(true)
                override suspend fun awaitToken() = token.value
                override suspend fun setToken(token: String) { this.token.value = token }
                override suspend fun clearToken() { token.value = null }
            }
            val clock = object : Clock { override fun now() = 1793600000000L; override fun zone() = ZoneId.of("Europe/Paris") }
            val api = ApiClient.createApi(server.url("/").toString(), { token.currentToken() }, {})
            val repo = DataRepository(api, directory, clock) { token.currentToken() }
            val fixture = javaClass.getResource("/api/data.json")!!.readText()
            repo.start(); server.enqueue(MockResponse().setBody(fixture)); assertTrue(repo.refresh())
            server.takeRequest(2, TimeUnit.SECONDS)
            val container = object : AppContainer {
                override val context: Context = ApplicationProvider.getApplicationContext()
                override val clock = clock
                override val tokenStore = token
                override val api: Api = api
                override val dataRepository = repo
                override val authRepository: AuthRepository get() = error("unused")
                override val calendarScope = Scope(store)
            }
            val vm = ShellViewModel(container)
            server.enqueue(MockResponse().setBody("{\"habit_id\":1,\"day\":\"2026-11-02\",\"count\":2}"))
            vm.dispatch(PaletteAction.CheckHabit(1, "2026-11-02", 2)); advanceUntilIdle()
            val habit = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("PUT", habit.method); assertEquals("/api/habits/1/log", habit.path)
            repeat(100) {
                if (repo.snapshot.value!!.habits.logs.any { it.habit_id == 1L && it.day == "2026-11-02" && it.count == 2 }) return@repeat
                Thread.sleep(10); runCurrent()
            }
            assertEquals(2, repo.snapshot.value!!.habits.logs.single { it.habit_id == 1L && it.day == "2026-11-02" }.count)
            val before = repo.snapshot.value!!
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Cannot check habit\"}"))
            vm.dispatch(PaletteAction.CheckHabit(1, "2026-11-02", 0)); advanceUntilIdle()
            var failedHabitRequest: okhttp3.mockwebserver.RecordedRequest? = null
            repeat(500) { if (failedHabitRequest == null) { runCurrent(); failedHabitRequest = server.takeRequest(10, TimeUnit.MILLISECONDS) } }
            assertEquals("/api/habits/1/log", failedHabitRequest!!.path)
            repeat(100) { if (repo.error.value == null) { Thread.sleep(10); runCurrent() } }
            assertEquals("Cannot check habit", repo.error.value)
            assertEquals(before.habits, repo.snapshot.value!!.habits)
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Cannot complete homework\"}"))
            vm.dispatch(PaletteAction.ToggleHomework(1, true)); advanceUntilIdle()
            assertEquals("/api/homework/1", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            repeat(100) { if (repo.error.value != "Cannot complete homework") { Thread.sleep(10); runCurrent() } }
            assertEquals("Cannot complete homework", repo.error.value)
            assertEquals(before.homework, repo.snapshot.value!!.homework)
            server.enqueue(MockResponse().setBody("{\"updated\":1}")); server.enqueue(MockResponse().setBody(fixture))
            vm.dispatch(PaletteAction.BulkAttendance(listOf(1))); advanceUntilIdle()
            val bulk = server.takeRequest(5, TimeUnit.SECONDS)!!
            repeat(100) { Thread.sleep(10); runCurrent(); if (server.requestCount >= 4) return@repeat }
            assertEquals("POST", bulk.method); assertEquals("/api/attendance/bulk", bulk.path)
            assertTrue(bulk.body.readUtf8().contains("\"present\""))
            assertEquals("/api/data", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        } finally { storeScope.cancel(); server.shutdown(); directory.deleteRecursively(); Dispatchers.resetMain() }
    }
}
