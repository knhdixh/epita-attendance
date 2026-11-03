package com.knhdixh.attendance80.focus

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.logic.FocusLogEntry
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.FocusSessionDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class OutboxTest {
    private val dir = Files.createTempDirectory("outbox").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val server = MockWebServer()
    private lateinit var store: FocusStore
    private val recorded = mutableListOf<FocusSessionDto>()
    private val dropped = mutableListOf<Pair<String, String>>()
    private lateinit var outbox: Outbox

    private fun entry(id: String, start: Long = 1000) = FocusLogEntry(
        client_id = id, started_at = start, ended_at = start + 1_500_000, planned_seconds = 1500, focus_seconds = 1500,
        completed = true, homework_id = 4, label = "Essay", tags = listOf("study"),
    )

    private fun row(id: String) = ApiClient.json.encodeToString(FocusSessionDto.serializer(),
        FocusSessionDto(id = 9, client_id = id, started_at = 1000, ended_at = 1_501_000, focus_seconds = 1500))

    private suspend fun queue(vararg e: FocusLogEntry) = store.addLogs(e.toList(), ME)

    @Before fun setUp() {
        server.start()
        store = FocusStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("focus.preferences_pb") })
        runBlocking { store.setAccount(ME) }
        val api = ApiClient.createApi(server.url("/").toString(), { "token" }, {})
        outbox = Outbox(store, api, { recorded += it }, { e, why -> dropped += e.client_id to why })
    }

    @After fun tearDown() { server.shutdown(); scope.cancel(); dir.deleteRecursively() }

    @Test fun sendsEntriesInOrderWithTheSessionJsonAndRemovesThemAfter2xx() = runBlocking {
        queue(entry("aaaaaaaa-1"), entry("bbbbbbbb-2", 5000))
        server.enqueue(MockResponse().setBody(row("aaaaaaaa-1")))
        server.enqueue(MockResponse().setBody(row("bbbbbbbb-2")))
        assertTrue(outbox.flush())
        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/api/focus", first.path)
        assertEquals("Bearer token", first.getHeader("Authorization"))
        val body = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
        assertEquals("aaaaaaaa-1", body["client_id"]!!.jsonPrimitive.content)
        assertEquals("4", body["homework_id"]!!.jsonPrimitive.content)
        assertEquals("1500", body["focus_seconds"]!!.jsonPrimitive.content)
        assertEquals("bbbbbbbb-2", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["client_id"]!!.jsonPrimitive.content)
        assertTrue(store.outbox().isEmpty())
        assertEquals(2, recorded.size)
    }

    @Test fun aServerErrorKeepsTheEntryAndStopsSoOrderIsKept() = runBlocking {
        queue(entry("aaaaaaaa-1"), entry("bbbbbbbb-2", 5000))
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"Try later\"}"))
        assertFalse(outbox.flush())
        assertEquals(1, server.requestCount)
        assertEquals(listOf("aaaaaaaa-1", "bbbbbbbb-2"), store.outbox().map { it.client_id })
        assertTrue(dropped.isEmpty())
        server.enqueue(MockResponse().setBody(row("aaaaaaaa-1")))
        server.enqueue(MockResponse().setBody(row("bbbbbbbb-2")))
        assertTrue(outbox.flush())
        assertTrue(store.outbox().isEmpty())
    }

    @Test fun aBadRequestIsDroppedAndLoggedAndTheNextOneStillGoes() = runBlocking {
        queue(entry("aaaaaaaa-1"), entry("bbbbbbbb-2", 5000))
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Invalid client_id.\"}"))
        server.enqueue(MockResponse().setBody(row("bbbbbbbb-2")))
        assertTrue(outbox.flush())
        assertTrue(store.outbox().isEmpty())
        assertEquals(listOf("aaaaaaaa-1" to "Invalid client_id."), dropped)
        assertEquals(listOf("bbbbbbbb-2"), recorded.map { it.client_id })
    }

    @Test fun noNetworkAndAnExpiredTokenKeepTheEntry() = runBlocking {
        queue(entry("aaaaaaaa-1"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertFalse(outbox.flush())
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"Sign in\"}"))
        assertFalse(outbox.flush())
        assertEquals(1, store.outbox().size)
        assertTrue(dropped.isEmpty())
    }

    @Test fun sendingTheSameSessionTwiceIsSafe() = runBlocking {
        queue(entry("aaaaaaaa-1"))
        server.enqueue(MockResponse().setBody(row("aaaaaaaa-1")))
        assertTrue(outbox.flush())
        // The app was killed after the server answered but before the entry was removed: it comes back.
        queue(entry("aaaaaaaa-1"))
        server.enqueue(MockResponse().setBody(row("aaaaaaaa-1")))
        assertTrue(outbox.flush())
        assertTrue(store.outbox().isEmpty())
        assertEquals(1, recorded.map { it.client_id }.toSet().size)
    }

    @Test fun anEmptyOutboxMakesNoRequest() = runBlocking {
        assertTrue(outbox.flush())
        assertEquals(0, server.requestCount)
    }

    // ---- review 5: the account the session belongs to, one edit for a finish, queued sessions in the stats

    @Test fun nothingIsSentUnlessTheSignedInSessionIsTheQueuesAccount() = runBlocking {
        queue(entry("aaaaaaaa-1"))
        val api = ApiClient.createApi(server.url("/").toString(), { "token" }, {})
        var sessionEmail: String? = "someone.else@example.com"
        val guarded = Outbox(store, api, { recorded += it }, sessionEmail = { sessionEmail })
        assertFalse("another account is signed in: wait", guarded.flush())
        sessionEmail = null
        assertFalse("no snapshot yet: wait", guarded.flush())
        assertEquals(0, server.requestCount)
        sessionEmail = ME.uppercase()
        server.enqueue(MockResponse().setBody(row("aaaaaaaa-1")))
        assertTrue(guarded.flush())
        assertEquals(1, server.requestCount)
    }

    @Test fun theNextTimerAndTheFinishedSessionAreOneEditAndARepeatedFinishAddsItOnce() = runBlocking {
        val timer = com.knhdixh.attendance80.logic.FocusTimer()
        store.saveTimerAndLog(timer, entry("aaaaaaaa-1"), ME)
        store.saveTimerAndLog(timer, entry("aaaaaaaa-1"), ME)
        assertEquals(listOf("aaaaaaaa-1"), store.outbox(ME).map { it.client_id })
        assertEquals(timer, store.timer())
        assertEquals(listOf("aaaaaaaa-1"), store.queuedLogsFlow.first().map { it.client_id })
    }

    @Test fun queuedSessionsCountInTheStatsUntilTheServerHasThem() {
        val data = ApiClient.json.decodeFromString<com.knhdixh.attendance80.net.AppData>(javaClass.getResource("/api/data.json")!!.readText())
        val have = data.focus.sessions.size
        val out = com.knhdixh.attendance80.ui.shell.withQueuedSessions(data, listOf(entry("aaaaaaaa-1", 9_000_000_000_000), entry("aaaaaaaa-1", 9_000_000_000_000)))
        assertEquals("once, whatever the queue holds", have + 1, out.focus.sessions.size)
        assertNull("no id yet: the screen says saving", out.focus.sessions.last().id)
        assertEquals("a session the server already has isn't doubled", out.focus.sessions.size, com.knhdixh.attendance80.ui.shell.withQueuedSessions(out, listOf(entry("aaaaaaaa-1"))).focus.sessions.size)
        assertSame(data, com.knhdixh.attendance80.ui.shell.withQueuedSessions(data, emptyList()))
    }
}
