package com.knhdixh.attendance80.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ParityApiTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun fixture(): JsonObject = obj(javaClass.classLoader!!.getResourceAsStream("api/data.json")!!.bufferedReader().use { it.readText() })

    @Test fun mutationsAndServerFixtureResponses() = runBlocking {
        MockWebServer().use { server ->
            val api = ApiClient.createApi(server.url("/").toString(), { null }, {})
            val data = fixture()
            fun reply(body: JsonElement) { server.enqueue(MockResponse().setBody(body.toString())) }
            fun request(method: String, path: String, body: JsonElement? = null) {
                val r = server.takeRequest()
                assertEquals(method, r.method); assertEquals(path, r.path)
                if (body != null) assertEquals(body, Json.parseToJsonElement(r.body.readUtf8()))
            }
            val planned = obj("""{"items":[{"id":7,"planned_at":null,"planned_minutes":null}]}""")
            reply(obj("""{"homework":[]}""")); assertTrue(api.planHomework(planned).homework.isEmpty()); request("POST", "/api/homework/plan", planned)
            val clear = obj("""{"due_time":null,"course_id":null,"remind_minutes":null,"planned_at":null,"planned_minutes":null}""")
            reply(data["homework"]!!.jsonArray[0]); api.updateHomework(7, clear); request("PATCH", "/api/homework/7", clear)
            val habit = data["habits"]!!.jsonObject["list"]!!.jsonArray[0]
            val habitBody = obj("""{"name":"Read","schedule":{"type":"daily"},"remind_at":null}""")
            reply(habit); assertEquals("Read", api.createHabit(habitBody).name); request("POST", "/api/habits", habitBody)
            reply(habit); api.updateHabit(3, habitBody); request("PATCH", "/api/habits/3", habitBody)
            reply(obj("""{"habit_id":3,"day":"2026-10-30","count":0}""")); assertEquals(0, api.logHabit(3, HabitLogRequest("2026-10-30", 0)).count)
            request("PUT", "/api/habits/3/log", obj("""{"day":"2026-10-30","count":0}"""))
            reply(obj("""{"ok":true}""")); api.orderHabits(HabitOrderRequest(listOf(3, 2))); request("POST", "/api/habits/order", obj("""{"ids":[3,2]}"""))
            reply(obj("""{"ok":true}""")); api.deleteHabit(3); request("DELETE", "/api/habits/3")
            val countdown = data["countdowns"]!!.jsonArray[0]
            val countdownBody = obj("""{"title":"Exam","at":1793188000000,"note":null}""")
            reply(countdown); assertEquals("Exam", api.createCountdown(countdownBody).title); request("POST", "/api/countdowns", countdownBody)
            reply(countdown); api.updateCountdown(4, countdownBody); request("PATCH", "/api/countdowns/4", countdownBody)
            reply(obj("""{"ok":true}""")); api.deleteCountdown(4); request("DELETE", "/api/countdowns/4")
            val notify = obj("""{"tz":"Europe/Helsinki","classLead":30}""")
            reply(buildJsonObject { put("settings", data["notifications"]!!.jsonObject["settings"]!!); put("devices", 0) })
            assertEquals(30, api.saveNotifySettings(notify).settings.classLead); request("PUT", "/api/notify/settings", notify)
            val cal = obj("""{"calendar":9,"total":4,"updated":2,"recurringIgnored":1}""")
            reply(cal); assertEquals(9L, api.addCalendar(CalendarAddRequest(kind = "file", ics = "x")).calendar); request("POST", "/api/calendars")
            val calPatch = obj("""{"from":null}""")
            reply(obj("""{"calendar":9}""")); assertEquals(9L, api.updateCalendar(9, calPatch).calendar); request("PATCH", "/api/calendars/9", calPatch)
            reply(cal); assertEquals(2, api.syncCalendar(9).updated); request("POST", "/api/calendars/9/sync")
            reply(cal); api.updateCalendarFile(9, CalendarFileRequest("x")); request("POST", "/api/calendars/9/file")
            reply(obj("""{"synced":2,"failed":1,"remaining":0}""")); assertEquals(1, api.resyncGcal().failed); request("POST", "/api/gcal/resync")
            val nullTag = obj("""{"status":null,"tag":null}""")
            reply(obj("""{"ok":true}""")); api.updateAttendance(1, nullTag); request("PATCH", "/api/attendance/1", nullTag)
            val bulk = obj("""{"ids":[1,2],"status":null}""")
            reply(obj("""{"updated":2}""")); assertEquals(2, api.bulkAttendance(bulk).updated); request("POST", "/api/attendance/bulk", bulk)
            reply(data["homework"]!!.jsonArray[0]); api.createCatchup(CatchupRequest(1, "Europe/Paris")); request("POST", "/api/catchups", obj("""{"event_id":1,"tz":"Europe/Paris"}"""))
            reply(obj("""{"ok":true}""")); api.deleteHomework(7); request("DELETE", "/api/homework/7")
            val partialFocus = obj("""{"goal":3}""")
            reply(buildJsonObject { put("settings", data["focus"]!!.jsonObject["settings"]!!) }); api.saveFocusSettings(partialFocus); request("PUT", "/api/focus/settings", partialFocus)
            val nullUrl = obj("""{"recording_url":null}""")
            reply(obj("""{"ok":true}""")); api.updateCourse(1, nullUrl); request("PATCH", "/api/courses/1", nullUrl)
        }
    }
}
