package com.knhdixh.attendance80.data

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.net.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class FocusRepositoryTest {
    private val clock = object : Clock { override fun now() = 1793184400000L; override fun zone() = ZoneId.of("UTC") }
    private fun fixture() = javaClass.classLoader!!.getResourceAsStream("api/data.json")!!.bufferedReader().readText()
    @Test fun canonicalHistoryAndMutationErrors() = runBlocking {
        val server = MockWebServer(); server.start(); val dir = Files.createTempDirectory("focus").toFile()
        try {
            val api = ApiClient.createApi(server.url("/").toString(), { "a" }, {})
            val repo = DataRepository(api, dir, clock) { "a" }; repo.start()
            server.enqueue(MockResponse().setBody(fixture())); assertTrue(repo.refresh()); server.takeRequest()
            val old = repo.snapshot.value!!.focus.sessions.first()
            val queued = old.copy(id = null, client_id = "local-pending", started_at = old.started_at + 100)
            assertTrue(repo.mutate({ it }, { Unit }, { data, _ -> data.copy(focus = data.focus.copy(sessions = data.focus.sessions + queued)) }, expectedOwner = "a"))
            val canonical = old.copy(id = 99, label = "Canonical", started_at = old.started_at - 1000)
            val response = FocusListResponse(listOf(canonical), 1000)
            server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(FocusListResponse.serializer(), response)))
            repo.ensureFocusHistory("a", 1000); assertEquals("/api/focus?since=1000", server.takeRequest().path)
            val result = repo.snapshot.value!!.focus
            assertEquals(1000L, result.since); assertEquals(3, result.sessions.size); assertTrue(result.sessions.any { it == queued })
            assertEquals(99L, result.sessions.first { it.client_id == old.client_id }.id)
            assertEquals(result.sessions.sortedBy { it.started_at }, result.sessions)
            repo.ensureFocusHistory("a", 1000); assertEquals(2, server.requestCount)
            val body = buildJsonObject { put("goal", 3) }
            server.enqueue(MockResponse().setBody("{\"settings\":{\"goal\":4}}"))
            assertTrue(repo.saveFocusSettings("a", body)); val put = server.takeRequest(); assertEquals("PUT", put.method); assertEquals("/api/focus/settings", put.path); assertEquals("{\"goal\":3}", put.body.readUtf8()); assertEquals(4, repo.snapshot.value!!.focus.settings.goal)
            server.enqueue(MockResponse().setBody("{\"settings\":{\"goal\":4,\"tasks\":[\"Canonical task\"],\"tags\":[\"study\"]}}"))
            val lists = buildJsonObject { put("tasks", JsonArray(listOf(JsonPrimitive("draft")))) }
            assertEquals(listOf("Canonical task"), repo.saveFocusSettingsCanonical("a", lists)!!.tasks)
            assertEquals("/api/focus/settings", server.takeRequest().path); assertEquals(listOf("study"), repo.snapshot.value!!.focus.settings.tags)
            val before = repo.snapshot.value
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Bad setting\"}"))
            assertFalse(repo.saveFocusSettings("a", body)); assertEquals(before, repo.snapshot.value); assertEquals("Bad setting", repo.error.value); server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"Delete failed\"}"))
            assertFalse(repo.deleteFocus("a", 1)); assertEquals(before, repo.snapshot.value); assertEquals("Delete failed", repo.error.value); server.takeRequest()
            server.enqueue(MockResponse().setBody("{\"ok\":true}")); assertTrue(repo.deleteFocus("a", 1)); val del = server.takeRequest(); assertEquals("DELETE", del.method); assertEquals("/api/focus/1", del.path); assertTrue(repo.snapshot.value!!.focus.sessions.none { it.id == 1L })
            assertFalse(repo.deleteFocus("b", 99)); assertEquals(7, server.requestCount)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
    @Test fun historyFailureAndOwnerSwitch() = runBlocking {
        val server = MockWebServer(); server.start(); val dir = Files.createTempDirectory("focus").toFile(); var token = "a"
        try {
            val api = ApiClient.createApi(server.url("/").toString(), { token }, {})
            val repo = DataRepository(api, dir, clock) { token }; repo.start()
            server.enqueue(MockResponse().setBody(fixture())); assertTrue(repo.refresh()); server.takeRequest()
            val before = repo.snapshot.value!!.focus
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"History failed\"}"))
            repo.ensureFocusHistory("a", 1000); assertEquals("History failed", repo.error.value); assertTrue(repo.focusHistory.value.failed)
            assertEquals(before, repo.snapshot.value!!.focus); repo.ensureFocusHistory("a", 1000); assertEquals(2, server.requestCount); server.takeRequest()
            val version = repo.focusHistory.value.refreshVersion
            server.enqueue(MockResponse().setBody(fixture())); assertTrue(repo.refresh()); server.takeRequest()
            assertTrue(repo.focusHistory.value.refreshVersion > version)
            server.enqueue(MockResponse().setBody("{\"sessions\":[],\"since\":1000}")); repo.ensureFocusHistory("a", 1000)
            assertEquals("/api/focus?since=1000", server.takeRequest().path); assertEquals(1000L, repo.snapshot.value!!.focus.since)
            server.enqueue(MockResponse().setBody("{\"sessions\":[],\"since\":500}").setBodyDelay(1, TimeUnit.SECONDS))
            val pending = async(Dispatchers.IO) { repo.ensureFocusHistory("a", 500) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); token = "b"
            val switched = async(Dispatchers.IO) { repo.start() }; pending.await(); switched.await()
            assertNull(repo.snapshot.value); assertNotEquals("a", repo.focusHistory.value.owner)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
    @Test fun delayedSettingsAndDeleteCannotChangeReplacementAccount() = runBlocking {
        val server = MockWebServer(); server.start(); val dir = Files.createTempDirectory("focus").toFile(); var token = "a"
        try {
            val api = ApiClient.createApi(server.url("/").toString(), { token }, {})
            val repo = DataRepository(api, dir, clock) { token }; repo.start()
            server.enqueue(MockResponse().setBody(fixture())); assertTrue(repo.refresh()); server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"Old owner error\"}").setBodyDelay(1, TimeUnit.SECONDS))
            val pending = async(Dispatchers.IO) { repo.saveFocusSettings("a", buildJsonObject { put("goal", 3) }) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); token = "b"
            val switch = async(Dispatchers.IO) { repo.start() }; assertFalse(pending.await()); switch.await()
            assertNull(repo.snapshot.value); assertNull(repo.error.value)
            server.enqueue(MockResponse().setBody(fixture())); assertTrue(repo.refresh()); server.takeRequest()
            server.enqueue(MockResponse().setBody("{\"ok\":true}").setBodyDelay(1, TimeUnit.SECONDS))
            val deleting = async(Dispatchers.IO) { repo.deleteFocus("b", 1) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); token = "c"
            val switchAgain = async(Dispatchers.IO) { repo.start() }; assertFalse(deleting.await()); switchAgain.await()
            assertNull(repo.snapshot.value); assertNull(repo.error.value)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
}
