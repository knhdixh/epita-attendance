package com.knhdixh.attendance80

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Run under Android Test Orchestrator: each method gets a fresh app process and retains app data. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LocalServerTest {
    companion object {
        @BeforeClass @JvmStatic fun requireLocalHost() {
            assertEquals("http://10.0.2.2:8787", BuildConfig.BASE_URL.trimEnd('/'))
        }
    }
    @get:Rule val ui = createEmptyComposeRule()
    private val app: App get() = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun waitText(text: String) {
        try {
            ui.waitUntil(30_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $text; UI: ${ui.onRoot().printToString()}", e)
        }
    }
    private fun waitTitle(title: String) = ui.waitUntil(30_000) {
        ui.onAllNodes(hasTestTag("shell-title") and hasText(title)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun openLink(uri: String) {
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun launch() {
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun goBottom(label: String) { ui.onNodeWithTag("nav-${label.lowercase()}").performClick() }
    private fun more(label: String) {
        goBottom("More")
        ui.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun imeVisible(): Boolean {
        var visible = false
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
            visible = activity?.window?.decorView?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
        }
        return visible
    }

    private fun waitTodayLecture() {
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("today-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("today-list").performScrollToNode(hasText("E2E shared lecture"))
        waitText("E2E shared lecture")
    }

    private fun waitFocusSession() {
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("focus-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("E2E focus"))
        waitText("E2E focus")
    }

    private fun dataFromServer(): JSONObject {
        val login = (URL("http://10.0.2.2:8787/auth/dev?email=e2e%40example.test").openConnection() as HttpURLConnection)
            .apply { instanceFollowRedirects = false; connectTimeout = 5000; readTimeout = 5000 }
        val cookie = try { assertEquals(302, login.responseCode); login.getHeaderField("Set-Cookie").substringBefore(';') }
            finally { login.disconnect() }
        val request = (URL("http://10.0.2.2:8787/api/data").openConnection() as HttpURLConnection)
            .apply { connectTimeout = 5000; readTimeout = 5000; setRequestProperty("Cookie", cookie) }
        val data = try { assertEquals(200, request.responseCode); JSONObject(request.inputStream.bufferedReader().readText()) }
            finally { request.disconnect() }
        return data
    }

    private fun idsFromServer(): Triple<Long, Long, Long> {
        val data = dataFromServer()
        val events = data.getJSONArray("events")
        val courses = data.getJSONArray("courses")
        val event = (0 until events.length()).map { events.getJSONObject(it) }.first { it.getString("summary") == "GR1 - E2E Algorithms" }
        val course = (0 until courses.length()).map { courses.getJSONObject(it) }.first { it.getString("name") == "GR1 - E2E Algorithms" }
        val homework = data.getJSONArray("homework")
        val task = (0 until homework.length()).map { homework.getJSONObject(it) }.first { it.getString("title") == "E2E homework" }
        return Triple(event.getLong("id"), course.getLong("id"), task.getLong("id"))
    }

    @Test(timeout = 180_000) fun a_signInAndLiveShell() {
        assertEquals("http://10.0.2.2:8787", BuildConfig.BASE_URL.trimEnd('/'))
        // A rejected app session must retain a link until the next sign-in succeeds.
        val (expiredLinkId, _, _) = idsFromServer()
        runBlocking { app.container.tokenStore.setToken("a80s_expired_test_session") }
        openLink("a80://session/$expiredLinkId")
        waitText("Sign in with dev account")
        ui.onNodeWithText("dev@example.com").performTextReplacement("e2e@example.test")
        Intents.init()
        val url = try {
            Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            ui.onNodeWithText("Sign in with dev account").performScrollTo().performClick()
            Intents.getIntents().last { it.action == Intent.ACTION_VIEW }.data ?: error("Missing developer sign-in URL")
        } finally { Intents.release() }
        assertEquals("e2e@example.test", url.getQueryParameter("email"))
        assertFalse(url.getQueryParameter("challenge").isNullOrBlank())
        val conn = (URL(url.toString()).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 5000; readTimeout = 5000 }
        try {
            assertEquals(302, conn.responseCode)
            val callback = conn.getHeaderField("Location")
            assertTrue(callback.startsWith("com.knhdixh.attendance80:/auth?code="))
            openLink(callback)
        } finally { conn.disconnect() }
        waitTitle("Session")
        waitText("GR1 - E2E Algorithms")
        ui.onNodeWithText("Close").performClick()
        goBottom("Today")
        waitTodayLecture()
        ui.onNodeWithText("E2E shared lecture").assertExists()
        goBottom("Attendance")
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("attendance-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("attendance-list").performScrollToNode(hasText("GR1 - E2E Algorithms"))
        ui.onNodeWithText("GR1 - E2E Algorithms").assertExists()
        val before = dataFromServer()
        val coursesById = before.getJSONArray("courses").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getLong("id") } }
        val eventsBefore = before.getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun truth(v: Any?) = v == true || v == 1
        val minuteNow = System.currentTimeMillis() / 60000 * 60000
        val ids = eventsBefore.filter { e ->
            val c = coursesById[e.getLong("course_id")]
            c != null && truth(c.opt("tracked")) && truth(c.opt("attendance")) && e.getLong("start_at") <= minuteNow && (e.isNull("status") || e.optString("status").isEmpty())
        }.map { it.getLong("id") }.toSet()
        assertTrue("seed must contain unmarked tracked past events", ids.isNotEmpty())
        val button = "Mark ${ids.size} as attended"
        ui.onNodeWithTag("attendance-list").performScrollToNode(hasText(button))
        waitText(button)
        ui.onNodeWithText(button).performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithText(button).fetchSemanticsNodes().isEmpty() }
        runBlocking { assertTrue(app.container.dataRepository.refresh()) }
        val after = dataFromServer().getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getLong("id") } }
        eventsBefore.forEach { old ->
            val id = old.getLong("id")
            val actual = after.getValue(id)
            if (id in ids) assertEquals("present", actual.optString("status"))
            else assertEquals(old.optString("status"), actual.optString("status"))
        }
        assertTrue(app.container.dataRepository.snapshot.value!!.events.filter { it.id in ids }.all { it.status == "present" })
        goBottom("Homework"); waitText("E2E homework")
        val homeworkBefore = dataFromServer().getJSONArray("homework").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getLong("id") } }
        ui.onNodeWithText("Add homework").performClick()
        ui.onNodeWithText("What's due").assertIsFocused()
        ui.waitUntil(30_000) { imeVisible() }
        ui.onNodeWithText("What's due").performTextInput("Device-created homework")
        ui.onNodeWithText("Notes").performTextInput("Created by local device test")
        ui.onNode(hasText("Add homework") and hasClickAction()).performScrollTo().assertIsDisplayed()
        assertTrue("Keyboard remains visible while Add is accessible", imeVisible())
        ui.onNode(hasText("Add homework") and hasClickAction()).performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.homework?.any { it.title == "Device-created homework" } == true }
        ui.waitUntil(30_000) { ui.onAllNodes(hasText("What's due") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("What's due").assertIsDisplayed().assertIsFocused()
        ui.waitUntil(30_000) { imeVisible() }
        ui.onNodeWithText("What's due").performTextInput("Device second homework")
        ui.onNode(hasText("Add homework") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.homework?.any { it.title == "Device second homework" } == true }
        runBlocking { assertTrue(app.container.dataRepository.refresh()) }
        val created = dataFromServer().getJSONArray("homework").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("title") == "Device-created homework" } }
        val secondCreated = dataFromServer().getJSONArray("homework").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("title") == "Device second homework" } }
        assertTrue(secondCreated.getLong("id") != created.getLong("id"))
        assertEquals("", secondCreated.optString("notes"))
        val createdId = created.getLong("id")
        assertEquals("Created by local device test", created.getString("notes"))
        goBottom("Homework"); waitText("Device-created homework")
        ui.onNodeWithTag("homework-list").performScrollToNode(hasTestTag("homework-row-$createdId"))
        ui.onNodeWithContentDescription("Done: Device-created homework").performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.homework?.find { it.id == createdId }?.done == true }
        runBlocking { assertTrue(app.container.dataRepository.refresh()) }
        val homeworkAfter = dataFromServer().getJSONArray("homework").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getLong("id") } }
        assertTrue(truth(homeworkAfter.getValue(createdId).opt("done")))
        homeworkBefore.forEach { (id, old) -> assertEquals(old.toString(), homeworkAfter.getValue(id).toString()) }
        goBottom("Focus"); waitFocusSession()
        more("Upcoming"); waitText("E2E shared lecture")
        ui.onNodeWithTag("upcoming-next").performClick()
        val nextMonday = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).plusWeeks(1)
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("upcoming-day-$nextMonday").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("upcoming-prev").assertIsEnabled()
        ui.onNodeWithTag("upcoming-prev").performClick()
        waitText("E2E shared lecture")
        val todayKey = java.time.LocalDate.now(java.time.ZoneId.systemDefault()).toString()
        ui.onNodeWithTag("upcoming-day-$todayKey").performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("upcoming-section-$todayKey").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("upcoming-section-$todayKey").assertIsDisplayed()
        more("Sessions"); waitText("E2E shared lecture")
        more("Courses"); waitText("GR1 - E2E Algorithms")
        // The row stays visible after untracking; refresh is serialized behind the mutation.
        val originalCourses = dataFromServer().getJSONArray("courses").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val target = originalCourses.first { it.getString("name") == "GR1 - E2E Algorithms" }
        val targetId = target.getLong("id")
        val originallyTracked = truth(target.opt("tracked"))
        val row = "course-row-$targetId"
        ui.onNodeWithTag("courses-list").performScrollToNode(hasTestTag(row))
        val toggle = hasTestTag("course-tracked") and hasAnyAncestor(hasTestTag(row))
        ui.onNode(toggle).performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.courses?.find { it.id == targetId }?.tracked == !originallyTracked }
        runBlocking { assertTrue(app.container.dataRepository.refresh()) }
        val afterToggle = dataFromServer().getJSONArray("courses").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getLong("id") } }
        originalCourses.forEach { old ->
            val id = old.getLong("id")
            assertEquals(if (id == targetId) !originallyTracked else truth(old.opt("tracked")), truth(afterToggle.getValue(id).opt("tracked")))
            if (id != targetId) assertEquals(old.toString(), afterToggle.getValue(id).toString())
        }
        ui.waitUntil(30_000) { ui.onAllNodes(toggle).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(toggle).performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.courses?.find { it.id == targetId }?.tracked == originallyTracked }
        runBlocking { assertTrue(app.container.dataRepository.refresh()) }
        val restored = dataFromServer().getJSONArray("courses").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getLong("id") == targetId } }
        assertEquals(originallyTracked, truth(restored.opt("tracked")))
        more("Calendar"); waitText("E2E shared lecture")
        more("Breaks")
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("breaks-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("breaks-list").performScrollToNode(hasText("E2E break", substring = true))
        waitText("E2E break")
        more("Habits"); waitText("E2E habit")
        more("Countdowns"); waitText("E2E countdown")
        more("Settings"); waitText("e2e@example.test")
        // Attendance rules saved through the real API, then put back.
        val thresholdBefore = dataFromServer().getJSONObject("user").getDouble("threshold")
        ui.onNodeWithTag("settings-threshold").performTextReplacement("75")
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-rules-save"))
        ui.onNodeWithTag("settings-rules-save").performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.user?.threshold == 0.75 }
        assertEquals(0.75, dataFromServer().getJSONObject("user").getDouble("threshold"), 1e-9)
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-threshold"))
        ui.onNodeWithTag("settings-threshold").performTextReplacement(Math.round(thresholdBefore * 100).toString())
        ui.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-rules-save"))
        ui.onNodeWithTag("settings-rules-save").performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.user?.threshold == thresholdBefore }
        assertEquals(thresholdBefore, dataFromServer().getJSONObject("user").getDouble("threshold"), 1e-9)
        // Personal tokens are loaded from the server when Settings opens.
        ui.onNodeWithTag("settings-list").performScrollToNode(hasText("Claude Code access"))

        // Scope applies to sessions, including shared lectures, and is persisted.
        more("Sessions")
        ui.onNodeWithTag("show-untracked").performClick()
        val calendars = dataFromServer().getJSONArray("calendars")
        val group1Id = (0 until calendars.length()).map { calendars.getJSONObject(it) }.first { it.getString("name") == "Group 1" }.getLong("id")
        ui.onNodeWithTag("scope-$group1Id").performClick()
        ui.onNodeWithTag("sessions-list").performScrollToNode(hasText("GR1 - E2E Algorithms"))
        ui.onAllNodesWithText("GR2 - E2E Algorithms").assertCountEquals(0)
        ui.onNodeWithTag("scope-all").performClick()
        ui.onNodeWithTag("sessions-list").performScrollToNode(hasText("GR2 - E2E Algorithms"))
        val (sessionId, courseId, homeworkId) = idsFromServer()
        ui.onNodeWithContentDescription("Search").performClick()
        ui.onNodeWithTag("search-query").performTextInput("E2E homework")
        waitText("E2E homework")
        ui.onNodeWithTag("result-homework:$homeworkId").performClick()
        waitText("E2E homework")
        openLink("a80://focus")
        waitFocusSession()
        val firstWeek = com.knhdixh.attendance80.logic.addDays(com.knhdixh.attendance80.logic.weekStart(System.currentTimeMillis(), java.time.ZoneId.systemDefault()), -52 * 7, java.time.ZoneId.systemDefault())
        ui.waitUntil(30_000) { (app.container.dataRepository.snapshot.value?.focus?.since ?: Long.MAX_VALUE) <= firstWeek }
        val focusId = dataFromServer().getJSONObject("focus").getJSONArray("sessions").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("label") == "E2E focus" }.getLong("id") }
        ui.onNodeWithText("Delete").performClick()
        waitText("Delete this focus session from your stats?")
        ui.onAllNodesWithText("Delete").onLast().performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.focus?.sessions?.none { it.id == focusId } == true }
        val sessionsAfterDelete = dataFromServer().getJSONObject("focus").getJSONArray("sessions")
        assertTrue((0 until sessionsAfterDelete.length()).none { sessionsAfterDelete.getJSONObject(it).getLong("id") == focusId })
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Daily goal"))
        ui.onNodeWithText("Daily goal").performScrollTo().performTextReplacement("3")
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Save"))
        ui.onNodeWithText("Save").performScrollTo().performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.focus?.settings?.goal == 3 }
        assertEquals(3, dataFromServer().getJSONObject("focus").getJSONObject("settings").getInt("goal"))
        openLink("a80://homework")
        waitText("E2E homework")
        openLink("a80://session/$sessionId")
        waitText("GR1 - E2E Algorithms")
        waitTitle("Session")
        // The first seeded occurrence is past and was marked present by the overview action.
        val sessionBefore = dataFromServer().getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getLong("id") } }
        ui.onNodeWithText("Absent").performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.homework?.any { it.kind == "catchup" && it.event_id == sessionId } == true }
        runBlocking { assertTrue(app.container.dataRepository.refresh()) }
        val sessionAfter = dataFromServer().getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getLong("id") } }
        sessionBefore.forEach { (id, old) -> assertEquals(if (id == sessionId) "absent" else old.optString("status"), sessionAfter.getValue(id).optString("status")) }
        ui.onNodeWithText("Exam").performScrollTo().performClick()
        ui.onNodeWithText("Note").performScrollTo().performTextInput("Local device note")
        ui.onNodeWithText("Save note").performScrollTo().performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.events?.find { it.id == sessionId }?.let { it.tag == "exam" && it.note == "Local device note" } == true }
        runBlocking { assertTrue(app.container.dataRepository.refresh()) }
        val edited = dataFromServer().getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getLong("id") == sessionId } }
        assertEquals("exam", edited.getString("tag")); assertEquals("Local device note", edited.getString("note"))
        ui.onNodeWithText("Attended").performScrollTo().performClick()
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.homework?.none { it.kind == "catchup" && it.event_id == sessionId } == true }
        runBlocking { assertTrue(app.container.dataRepository.refresh()) }
        val restoredSession = dataFromServer().getJSONArray("events").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getLong("id") == sessionId } }
        assertEquals("present", restoredSession.getString("status"))
        openLink("a80://course/$courseId")
        waitText("GR1 - E2E Algorithms")
        waitTitle("Course")
        waitText("Default tag: Normal")
        ui.onNodeWithText("Default tag: Normal").performClick()
        ui.onNodeWithText("Mandatory").performClick()
        waitText("Default tag: Mandatory")
        ui.waitUntil(30_000) { app.container.dataRepository.snapshot.value?.courses?.find { it.id == courseId }?.tag == "mandatory" && !app.container.dataRepository.loading.value }
        runBlocking { assertTrue(app.container.dataRepository.refresh()) }
        val persistedCourses = dataFromServer().getJSONArray("courses")
        assertEquals("mandatory", (0 until persistedCourses.length()).map { persistedCourses.getJSONObject(it) }.first { it.getLong("id") == courseId }.getString("tag"))
        openLink("a80://today")
        waitTodayLecture()
        // Orchestrator destroys this process after this method. The next test launches the app offline.
        app.filesDir.resolve("e2e-restart-marker").writeText(android.os.Process.myPid().toString())
        val cutoff = (URL("http://10.0.2.2:8787/__e2e/offline").openConnection() as HttpURLConnection)
            .apply { requestMethod = "POST"; connectTimeout = 5000; readTimeout = 5000 }
        try { assertEquals(204, cutoff.responseCode) } finally { cutoff.disconnect() }
    }

    @Test(timeout = 90_000) fun b_cachedOfflineProcessRestart() {
        run {
            val marker = app.filesDir.resolve("e2e-restart-marker")
            assertTrue("First test must finish before offline restart", marker.exists())
            assertNotEquals("Orchestrator must restart the app process", marker.readText().toInt(), android.os.Process.myPid())
            assertTrue(marker.delete())
            // A request to the actual server must fail before cached data can establish offline startup.
            val unavailable = runCatching {
                (URL("http://10.0.2.2:8787/auth/config").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500; readTimeout = 2500
                }.let { try { it.responseCode } finally { it.disconnect() } }
            }.isFailure
            assertTrue("Device still reaches the server; offline restart was not exercised", unavailable)
            launch()
            waitTodayLecture()
            goBottom("Homework"); waitText("E2E homework")
        }
    }
}
