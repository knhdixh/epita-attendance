package com.knhdixh.attendance80.notify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.data.QueuedMutation
import com.knhdixh.attendance80.net.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Locale

/** Phase 13, task 1: reminders on tasks (absolute and relative), Done, snooze, and the daily digest. */
class TaskReminderTest {
    private val dir = Files.createTempDirectory("notify-task").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    private fun task(id: Long, title: String, date: String?, time: String? = null, vararg reminders: TaskReminderDto, recurrence: String? = null, done: Boolean = false, tz: String = "Europe/Paris") =
        homework(id, title, date, time, done = done, tz = tz).copy(reminders = reminders.toList(), recurrence = recurrence)
    private fun data(vararg rows: HomeworkDto) = emptyData(homework = rows.toList())
    private fun plan(d: AppData, now: String, choices: NotifyChoices = NotifyChoices(checkin = false, exam = false), snoozes: Map<Long, TaskSnooze> = emptyMap()) =
        planReminders(d, choices, at(now), PARIS, snoozes)

    @Test fun anAbsoluteReminderGoesAtItsTime() {
        val r = plan(data(task(1, "Essay", "2026-11-03", "10:00", TaskReminderDto(7, at = at("2026-11-02T18:30:00")))), "2026-11-02T08:00:00").single()
        assertEquals(at("2026-11-02T18:30:00"), r.at); assertEquals("Essay", r.title); assertEquals("a80://homework/1", r.route)
        assertEquals(Kinds.TASK, r.kind); assertEquals("task:1:7:${r.at}", r.key)
        assertEquals(listOf("Done" to "homework-done", "Snooze" to "snooze-menu"), r.actions.map { it.label to it.type })
        assertEquals("2026-11-03", r.actions.first().value)
    }

    @Test fun aRelativeReminderCountsBackFromTheDueTime() {
        val r = plan(data(task(1, "Essay", "2026-11-03", "10:00", TaskReminderDto(7, before_minutes = 90))), "2026-11-02T08:00:00").single()
        assertEquals("2026-11-03T08:30", r.at.iso())
    }

    @Test fun aDateWithoutATimeCountsBackFromNineOClockInTheTasksOwnZone() {
        val d = data(task(1, "Read", "2026-11-03", null, TaskReminderDto(7, before_minutes = 30), tz = "America/New_York"))
        assertEquals(at("2026-11-03T08:30:00", java.time.ZoneId.of("America/New_York")), plan(d, "2026-11-02T08:00:00").single().at)
    }

    @Test fun aTaskWithoutADateHasNoRelativeReminderButKeepsAnAbsoluteOne() {
        val none = homework(1, "Someday").copy(reminders = listOf(TaskReminderDto(1, before_minutes = 10), TaskReminderDto(2, at = at("2026-11-02T12:00:00"))))
        assertEquals(listOf(at("2026-11-02T12:00:00")), plan(data(none), "2026-11-02T08:00:00").map { it.at })
    }

    @Test fun relativeRemindersAcrossTheSpringClockChangeAreRealMinutes() {
        // 29 March 2026: 02:00 Paris becomes 03:00. 24 hours before 10:00 CEST is 09:00 CET the day before.
        val r = plan(data(task(1, "Lab", "2026-03-29", "10:00", TaskReminderDto(7, before_minutes = 1440))), "2026-03-28T07:00:00").single()
        assertEquals("2026-03-28T09:00", r.at.iso())
        // A date-only task still reminds at 09:00 local on the day, clock change or not.
        val allDay = plan(data(task(2, "Lab 2", "2026-03-29", null, TaskReminderDto(8, before_minutes = 0))), "2026-03-28T07:00:00").single()
        assertEquals("2026-03-29T09:00", allDay.at.iso())
    }

    @Test fun changingTheDueTimeMovesTheReminderAndGivesItANewKey() {
        val before = plan(data(task(1, "Essay", "2026-11-03", "10:00", TaskReminderDto(7, before_minutes = 60))), "2026-11-02T08:00:00").single()
        val after = plan(data(task(1, "Essay", "2026-11-03", "14:00", TaskReminderDto(7, before_minutes = 60))), "2026-11-02T08:00:00").single()
        assertEquals("2026-11-03T09:00", before.at.iso()); assertEquals("2026-11-03T13:00", after.at.iso())
        assertNotEquals(before.key, after.key) // already posted for the old time: the new time posts again
    }

    @Test fun completingARepeatingTaskMovesItsRelativeReminderToTheNextOccurrence() {
        val open = task(1, "Pills", "2026-11-02", "09:00", TaskReminderDto(7, before_minutes = 10), recurrence = "every day")
        val next = open.copy(due_date = "2026-11-03", due_at = at("2026-11-03T09:00:00"))
        assertEquals("2026-11-02T08:50", plan(data(open), "2026-11-02T08:00:00").single().at.iso())
        assertEquals("2026-11-03T08:50", plan(data(next), "2026-11-02T09:30:00").single().at.iso())
    }

    @Test fun anAbsoluteReminderOnARepeatingTaskStaysASingleMoment() {
        val moved = task(1, "Pills", "2026-11-03", "09:00", TaskReminderDto(7, at = at("2026-11-02T08:50:00")), recurrence = "every day")
        assertTrue("posted already at 08:50 on the 2nd; no reminder is invented for the 3rd", plan(data(moved), "2026-11-02T13:00:00").isEmpty())
    }

    @Test fun doneTasksAreSkippedAndTheSwitchTurnsThemOff() {
        val d = data(task(1, "Done already", "2026-11-03", "10:00", TaskReminderDto(7, before_minutes = 5), done = true), task(2, "Open", "2026-11-03", "10:00", TaskReminderDto(8, before_minutes = 5)))
        assertEquals(listOf(2L), plan(d, "2026-11-03T08:00:00").map { it.ref })
        assertTrue(plan(d, "2026-11-03T08:00:00", NotifyChoices(checkin = false, exam = false, tasks = false)).isEmpty())
    }

    // ---- snooze

    @Test fun snoozeEndsTenMinutesAnHourOrTomorrowMorning() {
        val now = at("2026-11-02T22:00:00")
        assertEquals(now + 600_000, Snooze.until(Snooze.TEN_MIN, now, PARIS)); assertEquals(now + 3_600_000, Snooze.until(Snooze.HOUR, now, PARIS))
        assertEquals("2026-11-03T09:00", Snooze.until(Snooze.TOMORROW, now, PARIS)!!.iso()); assertNull(Snooze.until("2d", now, PARIS))
        // Across the spring change: tomorrow 09:00 is local time, not 24 hours later.
        assertEquals("2026-03-29T09:00", Snooze.until(Snooze.TOMORROW, at("2026-03-28T23:00:00"), PARIS)!!.iso())
    }

    @Test fun aSnoozedTaskGetsItsOwnReminderAndNotWhenItIsDone() {
        val open = task(1, "Essay", "2026-11-03", "10:00")
        val snoozed = plan(data(open), "2026-11-02T09:00:00", snoozes = mapOf(1L to TaskSnooze(at("2026-11-02T09:10:00"), "2026-11-03"))).single()
        assertEquals(at("2026-11-02T09:10:00"), snoozed.at); assertEquals("Essay", snoozed.title); assertTrue(snoozed.key.startsWith("snooze:1:"))
        assertTrue(plan(data(open.copy(done = true)), "2026-11-02T09:00:00", snoozes = mapOf(1L to TaskSnooze(at("2026-11-02T09:10:00"), "2026-11-03"))).isEmpty())
    }

    @Test fun aSnoozeIsDroppedOnceTheRepeatingTaskHasMovedOnOrIsGone() {
        val snooze = mapOf(1L to TaskSnooze(at("2026-11-02T09:10:00"), "2026-11-02"))
        // Pressed on the 2 Nov occurrence; the task was ticked and now sits on 3 Nov, still open.
        val moved = task(1, "Pills", "2026-11-03", "09:00", recurrence = "every day")
        assertTrue(plan(data(moved), "2026-11-02T09:00:00", snoozes = snooze).isEmpty())
        assertEquals(1, plan(data(moved.copy(due_date = "2026-11-02")), "2026-11-02T09:00:00", snoozes = snooze).size)
        assertTrue(plan(data(), "2026-11-02T09:00:00", snoozes = snooze).isEmpty())
    }

    @Test fun theStoreKeepsSnoozesPerAccountAndForgetsOldOnes() = runBlocking {
        val s = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("n.preferences_pb") })
        s.snooze("me@example.com", 1, TaskSnooze(5_000, "2026-11-03")); s.snooze("you@example.com", 2, TaskSnooze(6_000))
        assertEquals(mapOf(1L to TaskSnooze(5_000, "2026-11-03")), s.snoozes("me@example.com", 1_000))
        assertTrue(s.snoozes("me@example.com", 5_000 + 4 * 24 * 3600_000L).isEmpty())
        s.signedOut(); assertTrue(s.snoozes("me@example.com", 1_000).isEmpty())
    }

    @Test fun theEnginePostsTheSnoozedReminderOnceAndArmsTheAlarmForIt() = runBlocking {
        val store = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("e.preferences_pb") })
        store.setCheckin(false); store.setExam(false)
        val alarm = FakeAlarm(); val notifier = FakeNotifier()
        val d = data(task(1, "Essay", "2026-11-03", "10:00"))
        var now = at("2026-11-02T09:00:00")
        val clock = object : com.knhdixh.attendance80.Clock { override fun now() = now; override fun zone() = PARIS }
        val engine = ReminderEngine(clock, store, alarm, notifier)
        store.snooze("me@example.com", 1, TaskSnooze(at("2026-11-02T09:10:00"), "2026-11-03"))
        engine.run(d)
        assertTrue(notifier.posted.isEmpty()); assertEquals(at("2026-11-02T09:10:00"), alarm.at)
        now = at("2026-11-02T09:10:00"); engine.run(d); engine.run(d)
        assertEquals(listOf("Essay"), notifier.posted.map { it.first.title })
    }

    @Test fun theSnoozeButtonsStoreTheEndAndTheMenuOffersTheThreeLengths() = runBlocking {
        val notifier = FakeNotifier(); val snoozed = mutableListOf<Triple<String, Long, TaskSnooze>>()
        val loaded = data(task(1, "Essay", "2026-11-03", "10:00"))
        val now = at("2026-11-02T09:00:00")
        val actions = ReminderActions(testClock(now), notifier, { loaded }, { _: QueuedMutation, _: String -> true }, snooze = { o, id, until -> snoozed += Triple(o, id, until) })
        actions.handle("snooze-menu", 1, null, null, "me@example.com", "task:1:7:0")
        val menu = notifier.posted.single().first
        assertEquals(listOf("10 min", "1 hour", "Tomorrow"), menu.actions.map { it.label })
        assertEquals(listOf("task:1:7:0"), notifier.cancelled)
        actions.handle("snooze", 1, menu.actions[0].value, null, "me@example.com", menu.key)
        actions.handle("snooze", 1, menu.actions[2].value, null, "me@example.com", menu.key)
        assertEquals(listOf(now + 600_000, at("2026-11-03T09:00:00")), snoozed.map { it.third.until })
        assertEquals(listOf("2026-11-03", "2026-11-03"), snoozed.map { it.third.due })
        // A task that has been done since is not offered a snooze.
        val gone = ReminderActions(testClock(now), FakeNotifier().also { notifier.posted.clear() }, { data(task(1, "Essay", "2026-11-03", "10:00", done = true)) }, { _, _ -> true })
        gone.handle("snooze-menu", 1, null, null, "me@example.com", "k")
        assertTrue(notifier.posted.isEmpty())
    }

    @Test fun doneOnARepeatingTaskCarriesTheOccurrenceSoItMovesToTheNextOne() = runBlocking {
        val queued = mutableListOf<QueuedMutation>()
        val loaded = data(task(1, "Pills", "2026-11-02", "09:00", recurrence = "every day"))
        ReminderActions(testClock(at("2026-11-02T09:00:00")), FakeNotifier(), { loaded }, { m, _ -> queued += m; true })
            .handle("homework-done", 1, "2026-11-02", null, "me@example.com", "k")
        assertEquals("api/homework/1/complete", queued.single().path); assertTrue(queued.single().reload)
        assertEquals("2026-11-02", queued.single().body["occurrence"]!!.toString().trim('"'))
    }

    @Test fun aWidgetTickIsTheSameQueuedCompleteAndOnlyAParentOrARepeatReloads() = runBlocking {
        val queued = mutableListOf<QueuedMutation>()
        val loaded = data(task(1, "Plain", "2026-11-02"), task(2, "Parent", "2026-11-02"), homework(3, "Child").copy(parent_id = 2L))
        val actions = ReminderActions(testClock(at("2026-11-02T09:00:00")), FakeNotifier(), { loaded }, { m, _ -> queued += m; true })
        assertTrue(actions.complete(1, "2026-11-02", "me@example.com")); assertTrue(actions.complete(2, null, "me@example.com"))
        assertEquals(listOf(false, true), queued.map { it.reload }); assertEquals("api/homework/2/complete", queued[1].path)
        assertTrue("no occurrence when the widget drew none", queued[1].body.isEmpty())
    }

    // ---- the daily digest

    private val digestOn = NotifyChoices(checkin = false, exam = false, tasks = false, digestAt = "07:30")

    @Test fun theDigestIsOffByDefault() {
        assertNull(NotifyChoices().digestAt)
        assertTrue(plan(data(task(1, "Essay", "2026-11-02")), "2026-11-02T06:00:00", NotifyChoices(checkin = false, exam = false, tasks = false)).isEmpty())
    }

    @Test fun theDigestListsTodaysTasksClassesAndHabitsAtTheChosenTime() {
        val habit = HabitDto(5, "Read", null, "#4488ff", HabitScheduleDto("daily"), 1, "pages", null, 0, false, at("2026-10-01T10:00:00"))
        val d = emptyData(
            courses = listOf(course(1, "Maths")), events = listOf(event(10, 1, "2026-11-02T14:00:00", "2026-11-02T16:00:00")),
            homework = listOf(task(1, "Essay", "2026-11-02"), task(2, "Later", "2026-11-09"), task(3, "Old", "2026-10-30"), task(4, "Ticked", "2026-11-02", done = true)),
            habits = listOf(habit),
        )
        val r = plan(d, "2026-11-02T06:00:00", digestOn).first { it.kind == Kinds.DIGEST }
        assertEquals("2026-11-02T07:30", r.at.iso()); assertEquals("digest:2026-11-02", r.key); assertEquals("a80://today", r.route)
        assertEquals("Today: 1 task, 1 class, 1 habit", r.title)
        assertEquals("1 overdue\n• Essay\n14:00 Maths\nHabits: Read", r.body)
    }

    @Test fun anEmptyDayGetsNoDigestAndTomorrowsIsPlannedForTomorrow() {
        val d = data(task(1, "Tomorrow only", "2026-11-03"))
        val plans = plan(d, "2026-11-02T06:00:00", digestOn).filter { it.kind == Kinds.DIGEST }
        assertEquals(listOf("2026-11-03T07:30"), plans.map { it.at.iso() })
    }

    @Test fun aDigestThatIsHoursLateIsStillPostedButNotOnceTheWindowIsOver() {
        val d = data(task(1, "Essay", "2026-11-02"))
        assertEquals(1, plan(d, "2026-11-02T09:00:00", digestOn).count { it.kind == Kinds.DIGEST })
        assertEquals(0, plan(d, "2026-11-02T11:00:00", digestOn).count { it.kind == Kinds.DIGEST })
    }

    @Test fun theStoreRemembersTheTwoNewChoices() = runBlocking {
        val s = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("c.preferences_pb") })
        s.setTasks(false); s.setDigest("08:15")
        assertEquals(NotifyChoices(tasks = false, digestAt = "08:15"), s.choices.first())
        s.setDigest(null)
        assertNull(s.choices.first().digestAt)
    }
}
