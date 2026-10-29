package com.knhdixh.attendance80.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ParityApiTest {
    @Test fun mutationsPreservePathsAndExplicitNulls() = runBlocking {
        MockWebServer().use { server ->
            val api = ApiClient.createApi(server.url("/").toString(), { null }, {})
            server.enqueue(MockResponse().setBody("{\"homework\":[]}"))
            val body = Json.parseToJsonElement("""{"items":[{"id":7,"planned_at":null,"planned_minutes":null}]}""").let { it as kotlinx.serialization.json.JsonObject }
            api.planHomework(body)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/homework/plan", request.path)
            assertEquals(body, Json.parseToJsonElement(request.body.readUtf8()))
            server.enqueue(MockResponse().setBody("""{"habit_id":3,"day":"2026-10-30","count":0}"""))
            assertEquals(0, api.logHabit(3, HabitLogRequest("2026-10-30", 0)).count)
            val log = server.takeRequest()
            assertEquals("PUT", log.method)
            assertEquals("/api/habits/3/log", log.path)
            assertEquals(Json.parseToJsonElement("""{"day":"2026-10-30","count":0}"""), Json.parseToJsonElement(log.body.readUtf8()))
        }
    }
}
