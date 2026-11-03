package com.knhdixh.attendance80.ui.settings

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.*
import com.knhdixh.attendance80.auth.*
import com.knhdixh.attendance80.data.*
import com.knhdixh.attendance80.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsActionsTest {
    private val server = MockWebServer()
    private val dir = Files.createTempDirectory("settings").toFile()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fixture = javaClass.getResource("/api/data.json")!!.readText()
    private lateinit var tokenStore: TokenStore
    private lateinit var repo: DataRepository
    private lateinit var container: AppContainer
    private lateinit var actions: AccountSettingsActions

    @Before fun setUp() = runBlocking {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        tokenStore = object : TokenStore {
            override val token = MutableStateFlow<String?>("a")
            override val isLoaded = MutableStateFlow(true)
            override suspend fun awaitToken() = token.value
            override suspend fun setToken(token: String) { this.token.value = token }
            override suspend fun clearToken() { token.value = null }
        }
        val clock = object : Clock { override fun now() = settingsNow; override fun zone() = settingsZone }
        val api = ApiClient.createApi(server.url("/").toString(), { tokenStore.currentToken() }, {})
        repo = DataRepository(api, dir, clock) { tokenStore.currentToken() }
        val store = tokenStore
        container = object : AppContainer {
            override val context = context
            override val clock = clock
            override val tokenStore = store
            override val api = api
            override val dataRepository = repo
            override val authRepository = AuthRepository(api, store, CredentialManager.create(context))
            override val calendarScope = Scope(PreferenceDataStoreFactory.create(scope = storeScope) { dir.resolve("scope.preferences_pb") })
            override val baseUrl = "https://app.test"
        }
        repo.start(); server.enqueue(MockResponse().setBody(fixture)); assertTrue(repo.refresh()); server.takeRequest()
        actions = AccountSettingsActions(container, "a")
    }
    @After fun tearDown() { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }

    private fun reply(body: String) = server.enqueue(MockResponse().setBody(body))
    private fun take(): RecordedRequest = server.takeRequest()
    private fun RecordedRequest.line() = "$method $path"
    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8())
    private fun message(): String? = repo.error.value?.also { repo.consumeError(it) }

    @Test fun rulesAndNotificationsUseTheWebBodies() = runBlocking {
        reply("""{"ok":true}""")
        assertTrue(actions.saveRules(0.75, "hours"))
        take().let { assertEquals("PATCH /api/settings", it.line()); assertEquals(buildJsonObject { put("threshold", 0.75); put("unit", "hours") }, it.json()) }
        assertEquals(0.75, repo.snapshot.value!!.user.threshold, 0.0)
        assertEquals("Settings saved.", message())

        reply("""{"settings":{"classes":true,"classLead":15,"habits":false,"focus":true,"tz":"Europe/Paris"},"devices":2}""")
        assertTrue(actions.saveNotify(buildJsonObject { put("classes", true); put("classLead", 15); put("habits", false); put("focus", true) }))
        take().let { r ->
            assertEquals("PUT /api/notify/settings", r.line())
            assertEquals("Europe/Paris", r.json().jsonObject["tz"]!!.jsonPrimitive.content)
        }
        assertEquals(15, repo.snapshot.value!!.notifications.settings.classLead)
        assertEquals(2, repo.snapshot.value!!.notifications.devices)
    }

    @Test fun calendarsReloadAndSummarise() = runBlocking {
        reply("""{"calendar":5,"total":12,"added":12,"updated":0,"removed":0}"""); reply(fixture)
        assertTrue(actions.addLink(" https://zeus.test/ics ", " SE1 ", "2026-09-01"))
        take().let { assertEquals("POST /api/calendars", it.line()); assertEquals(buildJsonObject { put("kind", "link"); put("url", "https://zeus.test/ics"); put("name", "SE1"); put("from", "2026-09-01") }, it.json()) }
        assertEquals("GET /api/data?tasks=1", take().line())
        assertEquals("Added calendar: 12 sessions (12 new, 0 updated).", message())

        // Not a calendar: refused before anything is sent.
        assertFalse(actions.uploadFile("hello", "notes.txt", null))
        assertEquals("That does not look like an .ics calendar file.", message())

        // Same file name as the uploaded calendar: a newer version of it.
        reply("""{"calendar":1,"total":3,"unchanged":true}"""); reply(fixture)
        assertTrue(actions.uploadFile("BEGIN:VCALENDAR\nEND:VCALENDAR", "epita_classes.ics", null))
        take().let { assertEquals("POST /api/calendars/1/file", it.line()); assertEquals("epita_classes.ics", it.json().jsonObject["fileName"]!!.jsonPrimitive.content) }
        take()
        assertEquals("Updated epita_classes: 3 sessions (no changes).", message())

        // A file calendar has no first day to send.
        reply("""{"calendar":1}"""); reply(fixture)
        assertTrue(actions.editCalendar(1, " Lectures ", false, "2026-10-01"))
        take().let { assertEquals("PATCH /api/calendars/1", it.line()); assertEquals(buildJsonObject { put("name", "Lectures"); put("attendance", false) }, it.json()) }
        take()
        assertEquals("Saved.", message())

        reply("""{"removed":3}"""); reply(fixture)
        assertTrue(actions.removeCalendar(1))
        assertEquals("DELETE /api/calendars/1", take().line()); take()
        assertEquals("Removed epita_classes (3 sessions).", message())

        // A failed update reloads, so the calendar shows its error, and keeps the server's message.
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"Zeus did not answer."}""")); reply(fixture)
        assertFalse(actions.syncCalendar(1))
        assertEquals("POST /api/calendars/1/sync", take().line())
        assertEquals("GET /api/data?tasks=1", take().line())
        assertEquals("Zeus did not answer.", message())
    }

    @Test fun integrationsTokensAndAccount() = runBlocking {
        reply("""{"synced":50,"failed":0,"remaining":10}"""); reply("""{"synced":10,"failed":1,"remaining":0}"""); reply(fixture)
        assertTrue(actions.gcalResync())
        assertEquals(listOf("POST /api/gcal/resync", "POST /api/gcal/resync", "GET /api/data?tasks=1"), List(3) { take().line() })
        assertEquals("1 homework item(s) could not be synced. Check Settings.", message())

        reply("""{"url":"/auth/app/web?code=abc"}""")
        assertEquals("https://app.test/auth/app/web?code=abc", actions.gcalConnectUrl())
        take().let { assertEquals("POST /api/app/web-link", it.line()); assertEquals("/auth/google/calendar?app=1", it.json().jsonObject["next"]!!.jsonPrimitive.content) }

        reply("""{"ok":true}"""); reply(fixture)
        assertTrue(actions.rotateFeed()); assertEquals("POST /api/feed/rotate", take().line()); take()
        assertEquals("New feed link created.", message())

        reply("""{"id":4,"name":"Mac","scope":"read","token":"a80_xyz","created_at":1}""")
        assertEquals("a80_xyz", actions.createToken(" Mac ", "read")!!.token)
        take().let { assertEquals("POST /api/tokens", it.line()); assertEquals(buildJsonObject { put("name", "Mac"); put("scope", "read") }, it.json()) }
        reply("""{"ok":true}""")
        assertTrue(actions.revokeToken(4)); assertEquals("DELETE /api/tokens/4", take().line())
        assertEquals("Token revoked.", message())
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"Give the token a name."}"""))
        assertNull(actions.createToken("", "read")); take()
        assertEquals("Give the token a name.", message())

        reply("""{"ok":true}"""); reply(fixture)
        assertTrue(actions.deleteData()); assertEquals("DELETE /api/data", take().line()); take()
        assertEquals("All data deleted.", message())

        // Another account's actions send nothing.
        val before = server.requestCount
        val other = AccountSettingsActions(container, "b")
        assertFalse(other.deleteData()); assertNull(other.loadTokens()); assertNull(other.gcalConnectUrl())
        assertEquals(before, server.requestCount)

        reply("""{"ok":true}"""); reply("""{"ok":true}""")
        assertTrue(actions.deleteAccount())
        take().let { assertEquals("DELETE /api/account", it.line()); assertEquals(buildJsonObject { put("confirm", "DELETE") }, it.json()) }
        assertEquals("POST /auth/logout", take().line())
        assertNull(tokenStore.token.value)
        // The deleted account's cached snapshot is gone from disk.
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("data-") })
        assertNull(repo.snapshot.value)
    }

    @Test fun addingAPasskeySignedInSendsTheBearerToAuthEndpoints() = runBlocking {
        reply("{}")
        container.api.passkeyRegisterOptions(PasskeyRegisterOptionsRequest(""), "Bearer a")
        take().let { assertEquals("POST /auth/passkey/register/options", it.line()); assertEquals("Bearer a", it.getHeader("Authorization")) }
        reply("""{"ok":true,"created":false}""")
        container.api.passkeyRegisterVerify(PasskeyRegisterVerifyRequest(JsonObject(emptyMap()), "Pixel"), "Bearer a")
        take().let { assertEquals("Bearer a", it.getHeader("Authorization")); assertEquals("1", it.getHeader("X-A80-App")) }
        // Sign-in without a session still sends none.
        reply("{}")
        container.api.passkeyRegisterOptions(PasskeyRegisterOptionsRequest("New"))
        assertNull(take().getHeader("Authorization"))
    }

    @Test fun filesAreReadUpToTheLimitOnly() {
        assertEquals(10, readAtMost(java.io.ByteArrayInputStream(ByteArray(300_000)), 10).size)
        assertEquals(MAX_ICS_BYTES + 1, readAtMost(java.io.ByteArrayInputStream(ByteArray(MAX_ICS_BYTES + 500)), MAX_ICS_BYTES + 1).size)
        assertArrayEquals("BEGIN:VCALENDAR".toByteArray(), readAtMost(java.io.ByteArrayInputStream("BEGIN:VCALENDAR".toByteArray()), MAX_ICS_BYTES + 1))
    }

    @Test fun importSummaryMatchesTheWeb() {
        assertEquals("1 session (no changes)", importSummary(CalendarImportSummary(1, total = 1, unchanged = true)))
        assertEquals("20 sessions (2 new, 3 updated, 1 removed, 4 recurring only imported once)",
            importSummary(CalendarImportSummary(1, total = 20, added = 2, updated = 3, removed = 1, recurringIgnored = 4)))
        assertEquals("Calendar access was not granted. Tick the calendar permission on the Google screen.", gcalErrorMessage("scope"))
        assertEquals("Could not connect Google Calendar.", gcalErrorMessage("other"))
        assertEquals("https://app.test/feed/x/timetable.ics?groups=1,2", timetableFeedUrl("https://app.test/feed/x.ics", listOf(1, 2)))
        assertEquals("https://app.test/feed/x/timetable.ics", timetableFeedUrl("https://app.test/feed/x.ics", emptyList()))
    }
}
