package com.knhdixh.attendance80.data

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.net.ApiClient
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class DataRepositoryTest {
    private val clock = object : Clock {
        override fun now() = 1234L
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }
    private fun fixture() = javaClass.classLoader!!.getResourceAsStream("api/data.json")!!.bufferedReader().readText()

    @Test fun cacheRestartAndFailedRefresh() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("snapshot").toFile()
        try {
            val api = ApiClient.createApi(server.url("/").toString(), { "token" }, {})
            val first = DataRepository(api, dir, clock) { "token" }
            first.start()
            server.enqueue(MockResponse().setBody(fixture()))
            assertTrue(first.refresh())
            assertEquals(1234L, first.lastSynced.value)
            val restarted = DataRepository(api, dir, clock) { "token" }
            restarted.start()
            assertEquals(first.snapshot.value, restarted.snapshot.value)
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"Try again\"}"))
            assertFalse(restarted.refresh())
            assertEquals("Try again", restarted.error.value)
            val again = DataRepository(api, dir, clock) { "token" }; again.start()
            assertEquals(first.snapshot.value, again.snapshot.value)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun cacheAppearsBeforeDelayedNetworkAndSessionChangesClearIt() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("snapshot").toFile()
        var token: String? = "token"
        try {
            val api = ApiClient.createApi(server.url("/").toString(), { token }, {})
            val first = DataRepository(api, dir, clock) { token }
            first.start(); server.enqueue(MockResponse().setBody(fixture())); assertTrue(first.refresh())
            val restarted = DataRepository(api, dir, clock) { token }
            restarted.start()
            server.enqueue(MockResponse().setBody(fixture()).setBodyDelay(1, TimeUnit.SECONDS))
            val refresh = async(Dispatchers.IO) { restarted.refresh() }
            server.takeRequest(2, TimeUnit.SECONDS) // first request
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertNotNull(restarted.snapshot.value)
            assertFalse(refresh.isCompleted)
            token = null
            val signedOut = async(Dispatchers.IO) { restarted.start() }
            withTimeout(1000) { while (restarted.snapshot.value != null) yield() }
            assertFalse(refresh.await())
            signedOut.await(); assertNull(restarted.snapshot.value)
            token = "another-token"; restarted.start(); assertNull(restarted.snapshot.value)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun renameFailureKeepsPreviousSnapshotOnDiskAndCleansTemporaryFile() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("snapshot").toFile()
        try {
            val api = ApiClient.createApi(server.url("/").toString(), { "token" }, {})
            val first = DataRepository(api, dir, clock) { "token" }
            first.start(); server.enqueue(MockResponse().setBody(fixture())); assertTrue(first.refresh())
            val before = first.snapshot.value
            val cached = dir.listFiles()!!.single().readText()
            val failing = DataRepository(api, dir, clock, rename = { source, _ ->
                assertTrue(source.exists())
                assertTrue(source.readText().contains("changed@example.com"))
                false
            }) { "token" }
            failing.start()
            server.enqueue(MockResponse().setBody(fixture().replace("student@epita.fr", "changed@example.com")))
            assertFalse(failing.refresh())
            assertEquals("Could not save snapshot", failing.error.value)
            assertEquals(before, failing.snapshot.value)
            assertEquals(1, dir.listFiles()!!.size)
            assertEquals(cached, dir.listFiles()!!.single().readText())
            val restarted = DataRepository(api, dir, clock) { "token" }; restarted.start()
            assertEquals(before, restarted.snapshot.value)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun refreshWaitsForMutationBeforeReplacingSnapshot() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("snapshot").toFile()
        try {
            val api = ApiClient.createApi(server.url("/").toString(), { "token" }, {})
            val repo = DataRepository(api, dir, clock) { "token" }
            repo.start(); server.enqueue(MockResponse().setBody(fixture())); assertTrue(repo.refresh())
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val mutation = async {
                repo.mutate({ it.copy(courses = emptyList()) }, {
                    entered.complete(Unit); finish.await(); Unit
                }, { data, _ -> data })
            }
            entered.await()
            server.enqueue(MockResponse().setBody(fixture()))
            val refresh = async { repo.refresh() }
            yield()
            assertEquals(1, server.requestCount)
            assertTrue(repo.snapshot.value!!.courses.isEmpty())
            finish.complete(Unit)
            assertTrue(mutation.await()); assertTrue(refresh.await())
            assertEquals(2, server.requestCount)
            assertEquals(2, repo.snapshot.value!!.courses.size)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test fun optimisticFailureRevertsAndReloads() = runBlocking {
        val server = MockWebServer(); server.start()
        val dir = Files.createTempDirectory("snapshot").toFile()
        try {
            val api = ApiClient.createApi(server.url("/").toString(), { "token" }, {})
            val repo = DataRepository(api, dir, clock) { "token" }; repo.start()
            server.enqueue(MockResponse().setBody(fixture())); assertTrue(repo.refresh())
            val before = repo.snapshot.value!!
            val pending = CompletableDeferred<Unit>()
            val job = async {
                repo.mutate({ it.copy(courses = emptyList()) }, {
                    pending.await(); api.data()
                }, { _, response -> response })
            }
            yield()
            assertTrue(repo.snapshot.value!!.courses.isEmpty())
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"Invalid change\"}"))
            pending.complete(Unit)
            assertFalse(job.await()); assertEquals(before, repo.snapshot.value)
            assertEquals("Invalid change", repo.error.value)
            server.enqueue(MockResponse().setBody(fixture()))
            assertTrue(repo.mutate({ it }, { Unit }, { data, _ -> data }, reload = true))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }
}
