package com.knhdixh.attendance80.notify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.knhdixh.attendance80.net.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HabitReminderTest {
    private fun plan(d: AppData, now: String) = planReminders(d, NotifyChoices(checkin = false, exam = false), at(now), PARIS)

    // ---- habits (task 7)

    private val daily = HabitDto(7, "Read", "📖", "blue", HabitScheduleDto("daily"), 1, null, "20:30", 0, false, at("2026-10-01T09:00:00"))
    private fun habits(vararg h: HabitDto, logs: List<HabitLogDto> = emptyList()) =
        emptyData(habits = h.toList(), logs = logs, notify = NotifySettingsDto(classes = false, habits = true, tz = "Europe/Paris"))

    @Test fun aHabitReminderGoesAtItsTimeTodayAndTomorrowWithADoneButton() {
        val plan = plan(habits(daily), "2026-11-03T09:00:00")
        assertEquals(listOf(at("2026-11-03T20:30:00"), at("2026-11-04T20:30:00")), plan.map { it.at })
        val r = plan.first()
        assertEquals("📖 Read", r.title)
        assertEquals("Time for your habit. Tap Done when it's done.", r.body)
        assertEquals("habit:7:${daily.created_at}:2026-11-03", r.key)
        assertEquals(listOf(ReminderAction("Done", "habit-done", "2026-11-03", 1)), r.actions)
    }

    @Test fun doneFillsTheWholeTarget() {
        val glasses = daily.copy(id = 8, name = "Water", icon = null, target = 8)
        assertEquals(8, plan(habits(glasses), "2026-11-03T09:00:00").first().actions.single().number)
        assertEquals("Water", plan(habits(glasses), "2026-11-03T09:00:00").first().title)
    }

    @Test fun aHabitAlreadyDoneTodayIsNotRemindedTodayButStillTomorrow() {
        val plan = plan(habits(daily, logs = listOf(HabitLogDto(7, "2026-11-03", 1))), "2026-11-03T09:00:00")
        assertEquals(listOf("2026-11-04"), plan.map { it.key.substringAfterLast(':') })
    }

    @Test fun halfDoneTargetsStillGetTheReminder() {
        val glasses = daily.copy(id = 8, target = 8)
        val plan = plan(habits(glasses, logs = listOf(HabitLogDto(8, "2026-11-03", 5))), "2026-11-03T09:00:00")
        assertEquals("2026-11-03", plan.first().key.substringAfterLast(':'))
    }

    @Test fun onlyOnTheDaysTheScheduleSays() {
        // 3 Nov 2026 is a Tuesday: a Mon/Wed/Fri habit is reminded on Wednesday only (tomorrow).
        val mwf = daily.copy(id = 9, schedule = HabitScheduleDto("days", days = listOf(1, 3, 5)))
        assertEquals(listOf("2026-11-04"), plan(habits(mwf), "2026-11-03T09:00:00").map { it.key.substringAfterLast(':') })
    }

    @Test fun archivedHabitsAndHabitsWithoutATimeAreSkippedAndTheSwitchTurnsThemOff() {
        assertTrue(plan(habits(daily.copy(archived = true), daily.copy(id = 10, remind_at = null)), "2026-11-03T09:00:00").isEmpty())
        val off = habits(daily).let { it.copy(notifications = it.notifications.copy(settings = it.notifications.settings.copy(habits = false))) }
        assertTrue(plan(off, "2026-11-03T09:00:00").isEmpty())
    }

    @Test fun theDayIsTheDevicesDayNotUtc() {
        // 23:30 on 3 Nov in Paris is already 4 Nov in Helsinki: the reminder is for the Helsinki day.
        val helsinki = java.time.ZoneId.of("Europe/Helsinki")
        val now = at("2026-11-03T23:30:00")
        val keys = planReminders(habits(daily), NotifyChoices(checkin = false, exam = false), now, helsinki).map { it.key.substringAfterLast(':') }
        assertEquals(listOf("2026-11-04", "2026-11-05"), keys.sorted().take(2))
    }

    @Test fun aReminderAtTheClockChangeKeepsItsWallClockTime() {
        val now = at("2026-10-25T09:00:00")
        val plan = plan(habits(daily), "2026-10-25T09:00:00")
        assertEquals(listOf("2026-10-25T20:30", "2026-10-26T20:30"), plan.map { it.at.iso() })
    }

    // ---- Done writes the whole day through the queue

    private val dir = Files.createTempDirectory("notify-habit").toFile()
    private val scope = CoroutineScope(Dispatchers.IO)
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively() }

    @Test fun habitDoneIsQueuedWithTheDayAndTheTarget() = runBlocking {
        val store = NotifyStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("n.preferences_pb") })
        var flushed = 0
        ReminderActions(testClock(at("2026-11-03T20:31:00")), store, FakeNotifier(), { flushed++ }, { null })
            .handle("habit-done", 7, "2026-11-03", 8, "me@example.com", "habit:7")
        assertEquals(PendingAction("me@example.com", "habit-done", 7, "2026-11-03", 8, at = at("2026-11-03T20:31:00")), store.pendingActions().single())
        assertEquals(1, flushed)
    }
}
