package com.knhdixh.attendance80.focus

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.data.DataRepository
import com.knhdixh.attendance80.logic.FocusLogEntry
import com.knhdixh.attendance80.logic.FocusTarget
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.FocusSessionDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private const val ALICE = "alice@x.test"
private const val BOB = "bob@x.test"

/** Review 5, finding 1: sessions, tasks and the running timer belong to the account that made them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FocusAccountTest {
    private val dir = Files.createTempDirectory("focus-account").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val server = MockWebServer()
    private val clock = FakeClock()
    private val alarm = FakeAlarm()
    private val flushes = CountingOutbox()
    private lateinit var store: FocusStore
    private lateinit var controller: FocusController
    private lateinit var outbox: Outbox
    private var token = "token-a"
    private val min = 60_000L

    @Before fun setUp() {
        server.start()
        store = FocusStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("focus.preferences_pb") })
        controller = FocusController(store, clock, alarm, FakeNotifier(), CountingSurfaces(), flushes) { "run-${clock.now}" }
        val api = com.knhdixh.attendance80.net.ApiClient.createApi(server.url("/").toString(), { token }, {})
        outbox = Outbox(store, api)
    }
    @After fun tearDown() { server.shutdown(); scope.cancel(); dir.deleteRecursively() }

    private fun entry(id: String) = FocusLogEntry(client_id = id, started_at = 1000, ended_at = 1_501_000, planned_seconds = 1500, focus_seconds = 1500, completed = true, homework_id = 4, label = "Essay")
    private fun row(id: String) = ApiClient.json.encodeToString(FocusSessionDto.serializer(), FocusSessionDto(id = 9, client_id = id, started_at = 1000, ended_at = 1_501_000, focus_seconds = 1500))

    @Test fun aSessionQueuedUnderAliceNeverReachesBobAndComesBackWithAlice() = runBlocking {
        store.setAccount(ALICE)
        store.addLogs(listOf(entry("aaaaaaaa-1")), ALICE)
        store.savePendingTasks(listOf("Alice's thesis"))
        // Alice signs out, Bob signs in.
        controller.signedOut(); store.setAccount(BOB); token = "token-b"
        assertTrue(outbox.flush())
        assertEquals(0, server.requestCount)
        assertTrue(store.outbox().isEmpty())
        assertTrue(store.pendingTasks().isEmpty())
        assertTrue(store.pendingTasksFlow.first().isEmpty())
        // Alice signs in again (new token, same account, any letter case): her session goes, on its own.
        controller.signedOut(); store.setAccount("Alice@X.test"); token = "token-a2"
        server.enqueue(MockResponse().setBody(row("aaaaaaaa-1")))
        assertTrue(outbox.flush())
        val sent = server.takeRequest()
        assertEquals("Bearer token-a2", sent.getHeader("Authorization"))
        // The owner is storage only: the body is the session alone.
        assertEquals(setOf("client_id", "started_at", "ended_at", "planned_seconds", "focus_seconds", "completed", "homework_id", "label", "tags"),
            Json.parseToJsonElement(sent.body.readUtf8()).jsonObject.keys.filter { it != "course_id" }.toSet())
        assertEquals(listOf("Alice's thesis"), store.pendingTasks())
    }

    @Test fun aTimerRunningAcrossSignOutIsStoppedRecordedForAliceAndNotRunningForBob() = runBlocking {
        store.setAccount(ALICE)
        controller.start(FocusTarget(label = "Essay"))
        clock.now += 10 * min
        controller.signedOut()
        val t = controller.timer.value
        assertFalse(t.running()); assertNull(t.target)
        assertNull(alarm.current)
        assertNull(store.account())
        assertEquals(1, store.outbox(ALICE).size) // her ten minutes are hers, and kept for her
        store.setAccount(BOB)
        assertTrue(store.outbox().isEmpty())
        token = "token-b"
        assertTrue(outbox.flush())
        assertEquals(0, server.requestCount)
    }

    @Test fun aSessionRecordedWhileSignedOutIsNotQueuedForWhoeverSignsInNext() = runBlocking {
        controller.start(); clock.now += 5 * min; controller.stop() // nobody signed in: the timer works, nothing is queued
        store.setAccount(BOB)
        assertTrue(store.outbox().isEmpty())
        assertEquals(0, flushes.flushes)
    }

    @Test fun signingOutIsForgottenWhenNobodyWasSignedIn() = runBlocking {
        controller.start()
        controller.signedOut() // a fresh start while signed out must not stop a timer that was started signed out
        assertTrue(controller.timer.value.running())
    }

    @Test fun accountDeletionPurgesItsQueueButNotAnothersAndWorksOnCaseDifferences() = runBlocking {
        store.setAccount(ALICE); store.addLogs(listOf(entry("a-1")), ALICE); store.savePendingTasks(listOf("A"))
        store.addLogs(listOf(entry("b-1")), BOB)
        store.purgeAccount("ALICE@x.test")
        assertTrue(store.outbox(ALICE).isEmpty()); assertTrue(store.pendingTasks().isEmpty())
        assertEquals(listOf("b-1"), store.outbox(BOB).map { it.client_id })
    }

    @Test fun anOldQueueWithoutOwnersIsTakenOverByTheFirstAccountOnly() = runBlocking {
        store.addLegacy(listOf(entry("old-1")), listOf("Old task"))
        assertTrue(store.outbox(ALICE).isEmpty())
        store.setAccount(ALICE)
        assertEquals(listOf("old-1"), store.outbox().map { it.client_id })
        assertEquals(listOf("Old task"), store.pendingTasks())
        store.setAccount(BOB)
        assertTrue(store.outbox().isEmpty()); assertTrue(store.pendingTasks().isEmpty())
    }

    @Test fun pendingTasksAreMergedOnlyIntoTheirOwnAccountsSnapshot() = runBlocking {
        val repoDir = Files.createTempDirectory("repo").toFile()
        try {
            val api = ApiClient.createApi(server.url("/").toString(), { token }, {})
            val repo = DataRepository(api, repoDir, clock) { token }
            val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data.json")!!.readText())
            val bobs = ApiClient.json.encodeToString(AppData.serializer(), base.copy(user = base.user.copy(email = BOB)))
            repo.start(); server.enqueue(MockResponse().setBody(bobs)); assertTrue(repo.refresh()); server.takeRequest(5, TimeUnit.SECONDS)
            store.setAccount(ALICE); store.savePendingTasks(listOf("Alice's thesis"))
            // Bob's snapshot is on screen while Alice's tasks are waiting: nothing is sent, nothing is lost.
            assertFalse(syncPendingTasks(store, repo) { token })
            assertEquals(0, server.requestCount - 1)
            assertEquals(listOf("Alice's thesis"), store.pendingTasks())
            // Her own snapshot: the task is merged into her list.
            val alices = ApiClient.json.encodeToString(AppData.serializer(), base.copy(user = base.user.copy(email = ALICE)))
            server.enqueue(MockResponse().setBody(alices)); assertTrue(repo.refresh()); server.takeRequest(5, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setBody("""{"settings":{"tasks":["Alice's thesis"]}}"""))
            assertTrue(syncPendingTasks(store, repo) { token })
            val put = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("PUT", put.method)
            assertTrue(put.body.readUtf8().contains("Alice's thesis"))
            assertTrue(store.pendingTasks().isEmpty())
        } finally { repoDir.deleteRecursively() }
    }
}
