package com.knhdixh.attendance80.notify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class CheckInTest {
    private val dir = Files.createTempDirectory("notify").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var store: NotifyStore
    private val alarm = FakeAlarm()
    private val notifier = FakeNotifier()

    @Before fun setUp() { store = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("notify.preferences_pb") }) }
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private val maths = course(1, "Mathématiques")
    // 3 Nov 2026 is a Tuesday. Today (2 Nov) has three sessions; one is over, one is running, one is later.
    private val data = emptyData(
        courses = listOf(maths, course(2, "Sisu course", attendance = false), course(3, "Not mine", tracked = false)),
        events = listOf(
            event(10, 1, "2026-11-02T08:00:00", "2026-11-02T10:00:00"),
            event(11, 1, "2026-11-02T10:00:00", "2026-11-02T12:00:00", status = "present"),
            event(12, 1, "2026-11-02T14:00:00", "2026-11-02T16:00:00"),
            event(13, 2, "2026-11-02T08:00:00", "2026-11-02T10:00:00"),
            event(14, 3, "2026-11-02T08:00:00", "2026-11-02T10:00:00"),
            event(15, 1, "2026-11-03T08:00:00", "2026-11-03T10:00:00"),
            event(16, 1, "2026-11-04T08:00:00", "2026-11-04T10:00:00"),
        ),
    )

    @Test fun onlyUnmarkedTrackedSessionsThatCountGetACheckIn() {
        val now = at("2026-11-02T10:05:00")
        val plan = planReminders(data, NotifyChoices(), now, PARIS)
        // 10: over and unmarked. 11 is marked. 13 and 14 are not tracked for attendance. 16 is the day after tomorrow.
        assertEquals(listOf(10L, 12L, 15L), plan.map { it.ref })
        val first = plan.first()
        assertEquals("Were you at Mathématiques?", first.title)
        assertEquals("08:00–10:00", first.body)
        assertEquals(at("2026-11-02T10:00:00"), first.at)
        assertEquals("a80://session/10", first.route)
        assertEquals(listOf("Present", "Absent", "Excused"), first.actions.map { it.label })
        assertEquals(listOf("present", "absent", "excused"), first.actions.map { it.value })
    }

    @Test fun aSessionThatEndedLongAgoIsLeftAlone() {
        val plan = planReminders(data, NotifyChoices(), at("2026-11-02T13:30:00"), PARIS)
        assertEquals(listOf(12L, 15L), plan.map { it.ref })
    }

    @Test fun theSwitchTurnsThemOff() {
        assertTrue(planReminders(data, NotifyChoices(checkin = false), at("2026-11-02T10:05:00"), PARIS).isEmpty())
    }

    @Test fun engineDeliversWhatIsDueOnceAndSetsTheAlarmForTheNext() = runBlocking {
        val engine = ReminderEngine(testClock(at("2026-11-02T10:05:00")), store, alarm, notifier)
        engine.run(data)
        assertEquals(listOf("checkin:10:${at("2026-11-02T10:00:00")}"), notifier.posted.map { it.first.key })
        assertEquals("me@example.com", notifier.posted.single().second)
        assertEquals(at("2026-11-02T16:00:00"), alarm.at)
        engine.run(data) // a second run (the snapshot changed, the alarm fired) posts nothing twice
        assertEquals(1, notifier.posted.size)
    }

    @Test fun nothingIsMarkedPostedWhileThePermissionIsMissing() = runBlocking {
        val engine = ReminderEngine(testClock(at("2026-11-02T10:05:00")), store, alarm, notifier)
        notifier.allowed = false
        engine.run(data)
        assertTrue(notifier.posted.isEmpty())
        notifier.allowed = true
        engine.run(data)
        assertEquals(1, notifier.posted.size)
    }

    @Test fun theAlarmNeverSleepsPastMidnightSoTheNextDayIsPlanned() = runBlocking {
        val engine = ReminderEngine(testClock(at("2026-11-02T17:00:00")), store, alarm, notifier)
        engine.run(data.copy(events = emptyList()))
        assertEquals(at("2026-11-03T00:00:00"), alarm.at)
    }

    @Test fun noSnapshotMeansNoAlarm() = runBlocking {
        ReminderEngine(testClock(at("2026-11-02T10:05:00")), store, alarm, notifier).run(null)
        assertNull(alarm.at)
        assertEquals(1, alarm.calls)
    }

    @Test fun signOutCancelsTheAlarmAndTheShade() = runBlocking {
        val engine = ReminderEngine(testClock(at("2026-11-02T10:05:00")), store, alarm, notifier)
        engine.run(data)
        engine.signedOut()
        assertNull(alarm.at)
        assertEquals(1, notifier.cancelledAll)
    }

    // ------------------------------------------------ the buttons

    private val flushes = mutableListOf<Unit>()
    private fun actions(d: com.knhdixh.attendance80.net.AppData? = data) = ReminderActions(testClock(at("2026-11-02T10:05:00")), store, notifier, { flushes += Unit }, { d })

    @Test fun presentIsQueuedAndTheNotificationGoes() = runBlocking {
        actions().handle("checkin", 10, "present", null, "me@example.com", "k10")
        val queued = store.pendingActions().single()
        assertEquals(listOf("attendance", "10", "present"), listOf(queued.type, queued.ref.toString(), queued.value))
        assertEquals(listOf("k10"), notifier.cancelled)
        assertEquals(1, flushes.size)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test fun absentOffersACatchupAndAddingItQueuesIt() = runBlocking {
        actions().handle("checkin", 10, "absent", null, "me@example.com", "k10")
        val offer = notifier.posted.single().first
        assertEquals("Marked absent: Mathématiques", offer.title)
        assertEquals(listOf("Add catch-up"), offer.actions.map { it.label })
        actions().handle("catchup", 10, null, null, "me@example.com", offer.key)
        assertEquals(listOf("attendance", "catchup"), store.pendingActions().map { it.type })
        assertEquals("Europe/Paris", store.pendingActions().last().tz)
    }

    /** The first tap in a cold process: the snapshot is null until the cache has loaded. */
    @Test fun absentWaitsForTheSnapshotThenOffersTheCatchup() = runBlocking {
        val loading = kotlinx.coroutines.CompletableDeferred<com.knhdixh.attendance80.net.AppData>()
        val a = ReminderActions(testClock(at("2026-11-02T10:05:00")), store, notifier, { flushes += Unit }, { loading.await() })
        val tap = launch(kotlinx.coroutines.Dispatchers.Default) { a.handle("checkin", 10, "absent", null, "me@example.com", "k10") }
        kotlinx.coroutines.delay(100)
        assertTrue(notifier.posted.isEmpty())
        loading.complete(data)
        tap.join()
        assertEquals("Marked absent: Mathématiques", notifier.posted.single().first.title)
    }

    @Test fun noCatchupIsOfferedWhenOneExists() = runBlocking {
        val withCatchup = data.copy(homework = listOf(homework(5, "Catch up").copy(kind = "catchup", event_id = 10)))
        actions(withCatchup).handle("checkin", 10, "absent", null, "me@example.com", "k10")
        assertTrue(notifier.posted.isEmpty())
    }

    @Test fun aStatusThatIsNotOneOfThreeIsIgnored() = runBlocking {
        actions().handle("checkin", 10, "bogus", null, "me@example.com", "k10")
        assertTrue(store.pendingActions().isEmpty())
    }

    // ------------------------------------------------ the sender

    private val server = MockWebServer()
    private fun sender(): ActionSender {
        server.start()
        return ActionSender(store, ApiClient.createApi(server.url("/").toString(), { "token" }, {}))
    }

    @Test fun senderPatchesAttendanceAndPostsTheCatchupInOrderThenEmptiesTheQueue() = runBlocking {
        store.addAction(PendingAction("me@example.com", "attendance", 10, "absent"))
        store.addAction(PendingAction("me@example.com", "catchup", 10, tz = "Europe/Paris"))
        val s = sender()
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        server.enqueue(MockResponse().setBody("""{"id":9,"title":"Catch up","notes":"","created_at":1,"updated_at":1}"""))
        assertTrue(s.flush("me@example.com"))
        val patch = server.takeRequest()
        assertEquals("PATCH /api/attendance/10", "${patch.method} ${patch.path}")
        assertEquals("absent", Json.parseToJsonElement(patch.body.readUtf8()).jsonObject["status"]!!.jsonPrimitive.content)
        val catchup = server.takeRequest()
        assertEquals("POST /api/catchups", "${catchup.method} ${catchup.path}")
        assertTrue(store.pendingActions().isEmpty())
        server.shutdown()
    }

    @Test fun senderKeepsWhatFailedOnA500AndDropsA4xx() = runBlocking {
        store.addAction(PendingAction("me@example.com", "attendance", 10, "present"))
        store.addAction(PendingAction("me@example.com", "attendance", 11, "present"))
        val s = sender()
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        assertFalse(s.flush("me@example.com"))
        assertEquals(2, store.pendingActions().size) // order kept: the second waits for the first
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"Session not found."}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertTrue(s.flush("me@example.com"))
        assertTrue(store.pendingActions().isEmpty())
        server.shutdown()
    }

    @Test fun anotherAccountsItemsAreNeverSentFromThisOne() = runBlocking {
        store.addAction(PendingAction("other@example.com", "attendance", 10, "present"))
        val s = sender()
        assertTrue(s.flush("me@example.com"))
        assertEquals(0, server.requestCount)
        assertTrue(store.pendingActions().isEmpty())
        server.shutdown()
    }

    @Test fun nothingIsSentWhileSignedOut() = runBlocking {
        store.addAction(PendingAction("me@example.com", "attendance", 10, "present"))
        val s = sender()
        assertTrue(s.flush(null))
        assertEquals(0, server.requestCount)
        assertEquals(1, store.pendingActions().size)
        server.shutdown()
    }
}
