package com.knhdixh.attendance80.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.data.DataRepository
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class OfflineShellTest {
    @get:Rule val compose = createComposeRule()
    @Test fun cachedShellStaysVisibleWhileRefreshIsDelayed() = runBlocking {
        val fixture = javaClass.getResource("/api/data.json")!!.readText()
        // The second request (the delayed refresh) is held until the test lets it go, however slow the machine is.
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (requests.incrementAndGet() == 2) { arrived.countDown(); release.await(60, TimeUnit.SECONDS) }
                return MockResponse().setBody(fixture)
            }
        }
        server.start()
        val dir = Files.createTempDirectory("offline-shell").toFile()
        val clock = object : Clock { override fun now() = 1793600000000L; override fun zone() = ZoneId.of("Europe/Paris") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val api = ApiClient.createApi(server.url("/").toString(), { "token" }, {})
            val first = DataRepository(api, dir, clock) { "token" }
            first.start(); assertTrue(first.refresh())
            val repo = DataRepository(api, dir, clock) { "token" }; repo.start()
            val pending = scope.async { repo.refresh() }
            assertTrue("the delayed refresh reached the server", arrived.await(60, TimeUnit.SECONDS))
            assertFalse(pending.isCompleted)
            compose.setContent {
                val snapshot by repo.snapshot.collectAsState()
                Attendance80Theme { Shell(ShellState(data = snapshot), {}, {}, {}, initialRoute = "courses", now = clock.now(), zone = clock.zone()) }
            }
            compose.onNodeWithText("Algorithmique").assertIsDisplayed()
            assertFalse("still held while the cached shell is shown", pending.isCompleted)
            release.countDown()
            assertTrue(pending.await())
        } finally { release.countDown(); scope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}
