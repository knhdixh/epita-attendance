package com.knhdixh.attendance80.notify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Locale

class HomeworkReminderTest {
    private fun at(hw: com.knhdixh.attendance80.net.HomeworkDto) = reminderAt(hw, PARIS)

    // ---- the rule, as reminderMinutes() in src/homework.js

    @Test fun reminderMinutesIsTheWebsRule() {
        assertNull(reminderMinutes(null, true))
        assertEquals(30, reminderMinutes(30, true))
        assertEquals(1440, reminderMinutes(1440, true))
        assertEquals(0, reminderMinutes(0, false))
        // all-day: N days before counts from midnight, so it is moved to 18:00 the day it names
        assertEquals(360, reminderMinutes(1, false))        // ceil(1/1440) = 1 day, minus 18 h
        assertEquals(360, reminderMinutes(1440, false))
        assertEquals(1800, reminderMinutes(1441, false))
        assertEquals(1800, reminderMinutes(2880, false))
        assertEquals(10080 - 1080, reminderMinutes(10080, false))
    }

    @Test fun timedItemsRemindThatManyMinutesBefore() {
        assertEquals(at("2026-11-04T16:30:00"), at(homework(1, "Essay", "2026-11-04", "17:00", remind = 30)))
        assertEquals(at("2026-11-03T17:00:00"), at(homework(1, "Essay", "2026-11-04", "17:00", remind = 1440)))
        assertEquals(at("2026-11-04T17:00:00"), at(homework(1, "Essay", "2026-11-04", "17:00", remind = 0)))
    }

    @Test fun allDayItemsRemindAt1800TheDayBeforeAndAtDeadlineAtMidnight() {
        assertEquals(at("2026-11-03T18:00:00"), at(homework(1, "Report", "2026-11-04", remind = 1440)))
        assertEquals(at("2026-11-03T18:00:00"), at(homework(1, "Report", "2026-11-04", remind = 60)))   // under a day still means the day before
        assertEquals(at("2026-11-02T18:00:00"), at(homework(1, "Report", "2026-11-04", remind = 2880)))
        assertEquals(at("2026-11-04T00:00:00"), at(homework(1, "Report", "2026-11-04", remind = 0)))
    }

    @Test fun outsideAClockChangeItEqualsCountingTheWebsMinutesBack() {
        val hw = homework(1, "Report", "2026-11-12", remind = 2880)
        val midnight = at("2026-11-12T00:00:00")
        assertEquals(midnight - reminderMinutes(2880, false)!! * 60_000L, at(hw))
    }

    // ---- across a clock change

    @Test fun anAllDayItemStaysAt1800WhenTheClocksGoBackBetween() {
        // Paris clocks go back on 25 Oct 2026. Due Mon 26 Oct, "2 days before": 18:00 on Sat 24 Oct, still summer time.
        // Counting 1800 real minutes back from midnight would give 19:00.
        val hw = homework(1, "Report", "2026-10-26", remind = 2880)
        assertEquals(at("2026-10-24T18:00:00"), at(hw))
        assertEquals("2026-10-24T18:00", at(hw)!!.iso())
    }

    @Test fun anAllDayItemStaysAt1800WhenTheClocksGoForwardBetween() {
        // Clocks go forward on 29 Mar 2026. Due Mon 30 Mar, "2 days before": 18:00 on Sat 28 Mar, still winter time.
        assertEquals(at("2026-03-28T18:00:00"), at(homework(1, "Report", "2026-03-30", remind = 2880)))
    }

    @Test fun aTimedItemKeepsTheWebsRealMinutesAcrossTheChange() {
        // Due Sun 25 Oct 09:00 (after the change), 24 h before: Sat 24 Oct 10:00 summer time, as Google Calendar does it.
        assertEquals(at("2026-10-24T10:00:00"), at(homework(1, "Quiz", "2026-10-25", "09:00", remind = 1440)))
    }

    @Test fun theItemsOwnTimeZoneWinsOverTheDevices() {
        val hw = homework(1, "Essay", "2026-11-04", "17:00", remind = 60, tz = "Europe/Helsinki")
        assertEquals(at("2026-11-04T16:00:00", java.time.ZoneId.of("Europe/Helsinki")), at(hw))
    }

    // ---- what is planned

    private val maths = course(1, "Mathématiques")
    private fun data(vararg hw: com.knhdixh.attendance80.net.HomeworkDto, gcal: Boolean = false) =
        emptyData(courses = listOf(maths), homework = hw.toList(), gcal = gcal)

    @Test fun doneItemsAndItemsWithoutAReminderAreSkipped() {
        val now = at("2026-11-02T09:00:00")
        val plan = planReminders(data(
            homework(1, "Essay", "2026-11-03", "10:00", remind = 60),
            homework(2, "Done one", "2026-11-03", "10:00", remind = 60, done = true),
            homework(3, "No reminder", "2026-11-03", "10:00"),
        ), NotifyChoices(), now, PARIS)
        assertEquals(listOf(1L), plan.map { it.ref })
    }

    @Test fun textAndButtons() {
        val r = homeworkReminders(data(homework(1, "Essay", "2026-11-03", "10:00", remind = 60, course = 1)), PARIS, Locale.ENGLISH).single()
        assertEquals("Essay", r.title)
        assertEquals("Due Tue 3 Nov at 10:00 · Mathématiques", r.body)
        assertEquals("a80://homework/1", r.route)
        assertEquals(listOf("Done", "Focus on this"), r.actions.map { it.label })
        assertEquals(listOf("homework-done", "homework-focus"), r.actions.map { it.type })
        val allDay = homeworkReminders(data(homework(2, "Report", "2026-11-04", remind = 1440)), PARIS, Locale.ENGLISH).single()
        assertEquals("Due Wed 4 Nov", allDay.body)
    }

    @Test fun offByDefaultWhileGoogleCalendarIsConnectedUnlessTurnedOn() {
        val now = at("2026-11-02T09:00:00")
        val hw = homework(1, "Essay", "2026-11-03", "10:00", remind = 60)
        assertEquals(1, planReminders(data(hw), NotifyChoices(), now, PARIS).size)
        assertTrue(planReminders(data(hw, gcal = true), NotifyChoices(), now, PARIS).isEmpty())
        assertEquals(1, planReminders(data(hw, gcal = true), NotifyChoices(homework = true), now, PARIS).size)
        assertTrue(planReminders(data(hw), NotifyChoices(homework = false), now, PARIS).isEmpty())
    }

    @Test fun aChangedReminderTimeIsANewReminder() {
        val a = homeworkReminders(data(homework(1, "Essay", "2026-11-03", "10:00", remind = 60)), PARIS).single()
        val b = homeworkReminders(data(homework(1, "Essay", "2026-11-03", "10:00", remind = 30)), PARIS).single()
        assertNotEquals(a.key, b.key)
    }

    // ---- the buttons

    private val dir = Files.createTempDirectory("notify-hw").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private fun focusData(): com.knhdixh.attendance80.net.AppData {
        val d = data(homework(1, "Essay", "2026-11-03", "10:00", remind = 60))
        return d.copy(focus = d.focus.copy(settings = d.focus.settings.copy(focus = 50)))
    }

    @Test fun doneIsQueuedAndFocusOnThisStartsTheTimer() = runBlocking {
        val store = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("n.preferences_pb") })
        val notifier = FakeNotifier()
        val focused = mutableListOf<Long>()
        var flushed = 0
        val loaded = focusData()
        val actions = ReminderActions(testClock(at("2026-11-02T09:00:00")), store, notifier, { flushed++ }, { loaded }, { t, _ -> focused += t.homework_id!! })
        actions.handle("homework-done", 1, null, null, "me@example.com", "k1")
        assertEquals(listOf("homework-done"), store.pendingActions().map { it.type })
        assertEquals(1, flushed)
        actions.handle("homework-focus", 1, null, null, "me@example.com", "k1")
        assertEquals(listOf(1L), focused)
        assertEquals(1, store.pendingActions().size) // focus is not a server change
        assertEquals(listOf("k1", "k1"), notifier.cancelled)
    }

    /** A tap can start a cold process: the snapshot is null until the cache has loaded, and the button must wait for it. */
    @Test fun focusOnThisWaitsForTheSnapshotAndStartsWithTheAccountsLength() = runBlocking {
        val store = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("n.preferences_pb") })
        val loading = kotlinx.coroutines.CompletableDeferred<com.knhdixh.attendance80.net.AppData>()
        val started = mutableListOf<Pair<com.knhdixh.attendance80.logic.FocusTarget, Int>>()
        val actions = ReminderActions(testClock(at("2026-11-02T09:00:00")), store, FakeNotifier(), {}, { loading.await() }, { t, d -> started += t to d.focus.settings.focus })
        val tap = scope.launch { actions.handle("homework-focus", 1, null, null, "me@example.com", "k1") }
        kotlinx.coroutines.delay(100)
        assertTrue(started.isEmpty()) // still waiting, not dropped
        loading.complete(focusData())
        tap.join()
        assertEquals(listOf(com.knhdixh.attendance80.logic.FocusTarget(homework_id = 1, course_id = null, label = "Essay") to 50), started)
    }

    @Test fun focusOnThisIgnoresAnotherAccountsHomework() = runBlocking {
        val store = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("n.preferences_pb") })
        val started = mutableListOf<Long>()
        val actions = ReminderActions(testClock(at("2026-11-02T09:00:00")), store, FakeNotifier(), {}, { focusData() }, { t, _ -> started += t.homework_id!! })
        actions.handle("homework-focus", 1, null, null, "someone@else.com", "k1")
        assertTrue(started.isEmpty())
    }
}
