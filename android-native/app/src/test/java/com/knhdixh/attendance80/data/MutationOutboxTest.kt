package com.knhdixh.attendance80.data

import com.knhdixh.attendance80.Clock
import com.knhdixh.attendance80.net.ApiClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** The outbox for tasks, projects, labels and the rest: queued offline, kept over a restart, sent once and in order, ids remapped. */
class MutationOutboxTest {
    private var nowMs = 1_000_000L
    private val clock = object : Clock { override fun now() = nowMs; override fun zone(): ZoneId = ZoneId.of("Europe/Paris") }
    private fun fixture() = javaClass.classLoader!!.getResourceAsStream("api/data.json")!!.bufferedReader().readText()

    /** A tiny stand-in for the Worker: hands out ids, and gives back the same row for a repeated `client_id`. */
    private class FakeServer(var data: String) : Dispatcher() {
        val log: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val bodies: MutableList<JsonObject> = java.util.Collections.synchronizedList(mutableListOf())
        private var next = 100L
        private val byClient = HashMap<String, JsonObject>()
        /** `METHOD path` to the 4xx/5xx that answers it. */
        val failures = HashMap<String, Pair<Int, String>>()
        var created = 0

        private fun row(path: String, b: JsonObject): JsonObject {
            val cid = b["client_id"]?.jsonPrimitive?.content
            if (cid != null) byClient[cid]?.let { return it }
            val id = next++; created++
            val common = mapOf("id" to JsonPrimitive(id), "client_id" to (cid?.let(::JsonPrimitive) ?: JsonNull), "created_at" to JsonPrimitive(5), "updated_at" to JsonPrimitive(5))
            val out = JsonObject(when {
                path.startsWith("/api/labels") -> mapOf("name" to b.getValue("name"), "color" to JsonNull, "position" to JsonPrimitive(0), "favorite" to JsonPrimitive(0)) + common
                path.startsWith("/api/projects") -> mapOf("name" to b.getValue("name"), "parent_id" to (b["parent_id"] ?: JsonNull), "view" to JsonPrimitive("list"), "position" to JsonPrimitive(0), "favorite" to JsonPrimitive(0), "archived" to JsonPrimitive(0)) + common
                else -> b + mapOf("done" to JsonPrimitive(0), "labels" to (b["label_ids"] ?: JsonArray(emptyList())), "priority" to (b["priority"] ?: JsonPrimitive(1)), "position" to JsonPrimitive(0)) - "label_ids" - "tz" + common
            })
            if (cid != null) byClient[cid] = out
            return out
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val line = "${request.method} ${request.path}"
            if (request.method == "GET") return MockResponse().setBody(data).also { log += line }
            val body = request.body.readUtf8()
            log += line
            bodies += (if (body.isBlank()) JsonObject(emptyMap()) else ApiClient.json.parseToJsonElement(body).jsonObject)
            failures[line.substringBefore('?')]?.let { (code, msg) -> return MockResponse().setResponseCode(code).setBody("""{"error":"$msg"}""") }
            val path = request.path!!
            val answer = when {
                request.method == "POST" && (path == "/api/labels" || path == "/api/projects" || path == "/api/homework") -> row(path, bodies.last()).toString()
                request.method == "POST" && path.endsWith("/complete") -> """{"id":${path.split('/')[3]},"title":"x","done":1,"completed_at":9}"""
                else -> """{"ok":true}"""
            }
            return MockResponse().setBody(answer)
        }
    }

    private class Env(val server: MockWebServer, val fake: FakeServer, val dir: java.io.File, val clock: Clock, var token: String? = "t") {
        val online = AtomicBoolean(true)
        /** Paths whose answer is lost once: the server did the work, the app never hears (OkHttp would retry on its own, hiding it). */
        val loseResponseOnce = mutableSetOf<String>()
        private val real = ApiClient.createApi(server.url("/").toString(), { token }, {})
        val api: com.knhdixh.attendance80.net.Api = object : com.knhdixh.attendance80.net.Api by real {
            private fun up() { if (!online.get()) throw java.io.IOException("offline") }
            private fun lost(path: String) { if (loseResponseOnce.remove(path)) throw java.io.IOException("lost response") }
            override suspend fun rawData(tasks: Int): okhttp3.ResponseBody { up(); return real.rawData(tasks) }
            override suspend fun sendPost(path: String, body: JsonObject): okhttp3.ResponseBody { up(); return real.sendPost(path, body).also { lost(path) } }
            override suspend fun sendPatch(path: String, body: JsonObject): okhttp3.ResponseBody { up(); return real.sendPatch(path, body).also { lost(path) } }
            override suspend fun sendPut(path: String, body: JsonObject): okhttp3.ResponseBody { up(); return real.sendPut(path, body).also { lost(path) } }
            override suspend fun sendDelete(path: String): okhttp3.ResponseBody { up(); return real.sendDelete(path).also { lost(path) } }
        }
        fun repo() = DataRepository(api, dir, clock, token = { token })
        fun close() { server.shutdown(); dir.deleteRecursively() }
    }

    private fun env(): Env {
        val server = MockWebServer(); val fake = FakeServer(fixture())
        server.dispatcher = fake; server.start()
        return Env(server, fake, Files.createTempDirectory("outbox").toFile(), clock)
    }

    private fun body(vararg p: Pair<String, Any?>) = buildJsonObject {
        for ((k, v) in p) when (v) { null -> put(k, JsonNull); is Number -> put(k, v); is Boolean -> put(k, v); is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it as Long) })); else -> put(k, v.toString()) }
    }

    private suspend fun DataRepository.online(e: Env) { start(); assertTrue(refresh()) }

    @Test fun createdOfflineWithASubtaskAndALabel_survivesARestart_thenArrivesOnceInOrderWithRealIds() = runBlocking {
        val e = env()
        try {
            val first = e.repo(); first.online(e)
            e.online.set(false)
            val label = first.enqueue(Mutations.create("label", body("name" to "deep")))!!.temp!!
            val parent = first.enqueue(Mutations.createTask(body("title" to "Write report", "due_date" to "2026-12-01", "tz" to "Europe/Paris", "label_ids" to listOf(label))))!!.temp!!
            val child = first.enqueue(Mutations.createTask(body("title" to "Outline", "due_date" to null, "tz" to "Europe/Paris", "parent_id" to parent)))!!.temp!!
            assertTrue(label < 0 && parent < label && child < parent)
            assertEquals(3, first.pending.value)
            assertFalse("offline: nothing leaves", first.flushQueue())
            val shown = first.snapshot.value!!
            assertEquals(listOf(label), shown.homework.single { it.id == parent }.labels)
            assertEquals(parent, shown.homework.single { it.id == child }.parent_id)

            // The process is killed: a new repository on the same files still has all three, and the screen shows them.
            val second = e.repo(); second.start()
            assertEquals(3, second.pending.value)
            assertEquals(setOf(parent, child), second.snapshot.value!!.homework.filter { it.id < 0 }.map { it.id }.toSet())
            assertEquals("deep", second.snapshot.value!!.labels.single { it.id == label }.name)

            e.online.set(true); e.fake.log.clear(); e.fake.bodies.clear()
            assertTrue(second.flushQueue())
            assertEquals(listOf("POST /api/labels", "POST /api/homework", "POST /api/homework"), e.fake.log)
            assertEquals("labels first, so the task can name the real id", 100L, e.fake.bodies[1].getValue("label_ids").jsonArray[0].jsonPrimitive.long)
            assertEquals("the subtask names the parent's real id", 101L, e.fake.bodies[2].getValue("parent_id").jsonPrimitive.long)
            assertTrue(e.fake.bodies.all { it["client_id"]!!.jsonPrimitive.content.length in 8..64 })
            val done = second.snapshot.value!!
            assertTrue("no temporary ids are left", done.homework.none { it.id < 0 } && done.labels.none { it.id < 0 })
            assertEquals(listOf(100L), done.homework.single { it.id == 101L }.labels)
            assertEquals(101L, done.homework.single { it.id == 102L }.parent_id)
            assertEquals(0, second.pending.value)
            assertEquals(3, e.fake.created)
            assertTrue("sent once", second.flushQueue() && e.fake.created == 3)
        } finally { e.close() }
    }

    @Test fun aLostResponseIsRetriedWithTheSameClientIdAndCreatesOneRow() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            repo.enqueue(Mutations.createTask(body("title" to "Pay rent", "due_date" to null, "tz" to "UTC")))
            e.loseResponseOnce += "api/homework"
            assertFalse(repo.flushQueue())
            assertEquals("the server did it, the app doesn't know", 1, e.fake.created)
            assertEquals(1, repo.pending.value)
            assertTrue(repo.flushQueue())
            assertEquals(1, e.fake.created)
            assertEquals(2, e.fake.bodies.size); assertEquals(e.fake.bodies[0]["client_id"], e.fake.bodies[1]["client_id"])
            assertEquals(1, repo.snapshot.value!!.homework.count { it.title == "Pay rent" })
            assertTrue(repo.snapshot.value!!.homework.none { it.id < 0 })
        } finally { e.close() }
    }

    @Test fun aRetriedDeleteThatFindsTheRowGoneIsSettledQuietly() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            val victim = repo.snapshot.value!!.homework.first()
            repo.enqueue(Mutations.deleteTask(victim.id))
            e.loseResponseOnce += "api/homework/${victim.id}"
            assertFalse("the server deleted it, the answer was lost", repo.flushQueue())
            assertEquals(1, repo.pending.value)
            e.fake.failures["DELETE /api/homework/${victim.id}"] = 404 to "Homework not found."
            assertTrue(repo.flushQueue())
            assertNull("a 404 on a DELETE means it is done", repo.error.value)
            assertEquals(0, repo.pending.value)
            assertTrue(repo.snapshot.value!!.homework.none { it.id == victim.id })
        } finally { e.close() }
    }

    @Test fun aRefusedChangeIsDroppedWithItsDependantsTheMessageIsShownAndTheDataReloaded() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            val p = repo.enqueue(Mutations.create("project", body("name" to "Thesis")))!!.temp!!
            repo.enqueue(Mutations.createTask(body("title" to "Chapter 1", "due_date" to null, "tz" to "UTC", "project_id" to p)))
            repo.enqueue(Mutations.createTask(body("title" to "Unrelated", "due_date" to null, "tz" to "UTC")))
            e.fake.failures["POST /api/projects"] = 400 to "Unknown parent project."
            e.fake.log.clear()
            assertTrue(repo.flushQueue())
            assertEquals("Unknown parent project.", repo.error.value)
            assertEquals(listOf("POST /api/projects", "POST /api/homework", "GET /api/data?tasks=1"), e.fake.log.map { it.replace(Regex("^(POST /api/homework).*"), "$1") })
            assertEquals("the task that named the refused project went with it; the unrelated one was sent", listOf("Thesis", "Unrelated"), e.fake.bodies.map { (it["title"] ?: it["name"])!!.jsonPrimitive.content })
            assertEquals(0, repo.pending.value)
        } finally { e.close() }
    }

    @Test fun aServerErrorStopsTheQueueSoOrderIsKept() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            repo.enqueue(Mutations.create("label", body("name" to "a")))
            repo.enqueue(Mutations.create("label", body("name" to "b")))
            e.fake.failures["POST /api/labels"] = 503 to "Try later"
            e.fake.log.clear()
            assertFalse(repo.flushQueue())
            assertEquals("only the first was tried", listOf("POST /api/labels"), e.fake.log)
            assertEquals(2, repo.pending.value)
            e.fake.failures.clear()
            assertTrue(repo.flushQueue())
            assertEquals(listOf("a", "b"), e.fake.bodies.takeLast(2).map { it.getValue("name").jsonPrimitive.content })
        } finally { e.close() }
    }

    @Test fun aRefreshKeepsWhatIsNotSentYetAndSettlesWhatTheServerAlreadyHas() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            e.online.set(false)
            val t = repo.enqueue(Mutations.createTask(body("title" to "Offline one", "due_date" to null, "tz" to "UTC")))!!.temp!!
            repo.enqueue(Mutations.createComment(t, "first thought"))
            e.online.set(true)
            assertTrue(repo.refresh())
            assertTrue("a refresh doesn't throw away unsent changes", repo.snapshot.value!!.homework.any { it.id == t && it.title == "Offline one" })
            assertEquals(1, repo.snapshot.value!!.homework.single { it.id == t }.comment_count)
            assertEquals(2, repo.pending.value)
        } finally { e.close() }
    }

    @Test fun completingARepeatingTaskSendsTheOccurrenceAndMovesItOnLocally() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            val h = repo.snapshot.value!!.homework.first()
            val seed = Mutations.updateTask(h.id, body("recurrence" to "every day", "due_date" to "2030-03-30", "due_time" to null, "tz" to "Europe/Paris"))
            repo.enqueue(seed)
            repo.enqueue(Mutations.completeTask(h.id, "2030-03-30"))
            val row = repo.snapshot.value!!.homework.single { it.id == h.id }
            assertEquals("2030-03-31", row.due_date); assertFalse(row.done)
            assertTrue(repo.flushQueue())
            assertEquals("2030-03-30", e.fake.bodies.last()["occurrence"]!!.jsonPrimitive.content)
        } finally { e.close() }
    }

    @Test fun undoOfARepeatingTaskSendsTheOccurrenceItMovedOnTo() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            val h = repo.snapshot.value!!.homework.first()
            repo.enqueue(Mutations.updateTask(h.id, body("recurrence" to "every day", "due_date" to "2030-03-30", "due_time" to null, "tz" to "Europe/Paris")))
            repo.enqueue(Mutations.completeTask(h.id, "2030-03-30"))
            val moved = repo.snapshot.value!!.homework.single { it.id == h.id }.due_date
            assertEquals("2030-03-31", moved)
            repo.enqueue(Mutations.uncompleteTask(h.id, moved, reload = true))
            e.fake.bodies.clear(); e.fake.log.clear()
            assertTrue(repo.flushQueue())
            assertTrue(e.fake.log.contains("POST /api/homework/${h.id}/uncomplete"))
            assertEquals("2030-03-31", e.fake.bodies.last()["occurrence"]!!.jsonPrimitive.content)
        } finally { e.close() }
    }

    @Test fun signingOutDropsTheQueue() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            e.online.set(false)
            repo.enqueue(Mutations.create("label", body("name" to "x")))
            assertEquals(1, repo.pending.value)
            repo.signedOut()
            e.token = null
            val again = e.repo(); again.start()
            e.token = "t"; again.start()
            assertEquals(0, again.pending.value)
            assertEquals(0, e.dir.walkTopDown().count { it.name.startsWith("outbox-") && it.isFile })
        } finally { e.close() }
    }

    @Test fun aHeldDeleteSurvivesARestartAndIsSentOnlyAfterItsWindow() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            val victim = repo.snapshot.value!!.homework.first()
            repo.enqueue(Mutations.deleteTask(victim.id, holdMs = 5000))
            assertTrue("gone from the screen at once", repo.snapshot.value!!.homework.none { it.id == victim.id })
            e.fake.log.clear()
            assertFalse("held", repo.flushQueue()); assertTrue(e.fake.log.isEmpty())
            val restarted = e.repo(); restarted.start()
            assertEquals("the delete is in the outbox, not only in memory", 1, restarted.pending.value)
            assertTrue(restarted.snapshot.value!!.homework.none { it.id == victim.id })
            nowMs += 5001
            assertTrue(restarted.flushQueue())
            assertEquals(listOf("DELETE /api/homework/${victim.id}"), e.fake.log)
        } finally { nowMs = 1_000_000L; e.close() }
    }

    @Test fun undoTakesAHeldDeleteBackAndPutsTheRowsBack() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            val rows = repo.snapshot.value!!.homework
            val victim = rows.first()
            val m = Mutations.deleteTask(victim.id, holdMs = 5000)
            repo.enqueue(m)
            assertTrue(repo.cancelQueued(m.clientId) { d -> d.copy(homework = (d.homework + victim).sortedBy { it.due_at ?: Long.MAX_VALUE }) })
            assertEquals(rows.map { it.id }.toSet(), repo.snapshot.value!!.homework.map { it.id }.toSet())
            assertEquals(0, repo.pending.value)
            nowMs += 6000; e.fake.log.clear()
            assertTrue(repo.flushQueue()); assertTrue("nothing to send", e.fake.log.isEmpty())
            assertFalse("a sent change can't be taken back", repo.cancelQueued(m.clientId) { it })
        } finally { nowMs = 1_000_000L; e.close() }
    }

    @Test fun anotherAccountsChangesAreDroppedAndNeverSentFromThisOne() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            e.online.set(false)
            repo.enqueue(Mutations.create("label", body("name" to "mine")))
            e.online.set(true)
            e.fake.data = fixture().replace("student@epita.fr", "someone.else@example.com")
            e.token = "another-token"
            val other = e.repo(); other.start()
            assertTrue(other.refresh())
            assertEquals(0, other.pending.value)
            e.fake.log.clear()
            assertTrue(other.flushQueue())
            assertTrue("the first account's label was never sent as someone else", e.fake.log.isEmpty())
            assertTrue(other.snapshot.value!!.labels.none { it.name == "mine" })
        } finally { e.close() }
    }

    @Test fun nothingIsSentWhileSignedOut() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            e.online.set(false)
            repo.enqueue(Mutations.create("label", body("name" to "x")))
            e.online.set(true); e.fake.log.clear()
            e.token = null
            repo.start()
            assertTrue(repo.flushQueue())
            assertTrue(e.fake.log.isEmpty())
        } finally { e.close() }
    }

    @Test fun aHabitTheServerAlreadyMadeIsNotAddedAgainByARefresh() = runBlocking {
        val e = env()
        try {
            val repo = e.repo(); repo.online(e)
            e.online.set(false)
            val queued = repo.enqueue(Mutations.createHabit(body("name" to "Piano", "color" to "blue", "target" to 1, "schedule" to null)))!!
            assertEquals(1, repo.snapshot.value!!.habits.list.count { it.name == "Piano" })
            // The request got through and only the answer was lost: the server has the habit, with our client_id.
            val base = ApiClient.json.decodeFromString<com.knhdixh.attendance80.net.AppData>(fixture())
            val made = com.knhdixh.attendance80.net.HabitDto(500, "Piano", null, "blue", com.knhdixh.attendance80.net.HabitScheduleDto("daily"), 1, null, null, 9, false, 5, client_id = queued.clientId)
            e.fake.data = ApiClient.json.encodeToString(com.knhdixh.attendance80.net.AppData.serializer(), base.copy(habits = base.habits.copy(list = base.habits.list + made)))
            e.online.set(true)
            assertTrue(repo.refresh())
            assertEquals(listOf(500L), repo.snapshot.value!!.habits.list.filter { it.name == "Piano" }.map { it.id })
            assertEquals("settled by the refresh: nothing left to send", 0, repo.pending.value)
            e.fake.log.clear()
            assertTrue(repo.flushQueue()); assertTrue(e.fake.log.isEmpty())
        } finally { e.close() }
    }
}
