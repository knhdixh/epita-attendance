package com.knhdixh.attendance80.notify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** The Done buttons of homework and habit reminders reach the right endpoints with the right bodies. */
class ActionSenderHabitTest {
    private val dir = Files.createTempDirectory("notify-send").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val server = MockWebServer()
    @After fun tearDown() { server.shutdown(); scope.cancel(); dir.deleteRecursively() }

    @Test fun habitDoneIsAPutOnTheDayAndHomeworkDoneAPatch() = runBlocking {
        val store = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("n.preferences_pb") })
        store.addAction(PendingAction("me@example.com", "habit-done", 7, "2026-11-03", 8))
        store.addAction(PendingAction("me@example.com", "homework-done", 3))
        server.start()
        server.enqueue(MockResponse().setBody("""{"habit_id":7,"day":"2026-11-03","count":8}"""))
        server.enqueue(MockResponse().setBody("""{"id":3,"title":"Essay","done":1,"created_at":1,"updated_at":2}"""))
        assertTrue(ActionSender(store, ApiClient.createApi(server.url("/").toString(), { "token" }, {})).flush("me@example.com"))
        val habit = server.takeRequest()
        assertEquals("PUT /api/habits/7/log", "${habit.method} ${habit.path}")
        val body = Json.parseToJsonElement(habit.body.readUtf8()).jsonObject
        assertEquals("2026-11-03", body["day"]!!.jsonPrimitive.content)
        assertEquals("8", body["count"]!!.jsonPrimitive.content)
        val hw = server.takeRequest()
        assertEquals("PATCH /api/homework/3", "${hw.method} ${hw.path}")
        assertEquals("true", Json.parseToJsonElement(hw.body.readUtf8()).jsonObject["done"]!!.jsonPrimitive.content)
    }
}
