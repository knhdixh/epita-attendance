package com.knhdixh.attendance80.widget

import com.knhdixh.attendance80.logic.FocusSettings
import com.knhdixh.attendance80.logic.FocusTarget
import com.knhdixh.attendance80.logic.FocusTimer
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AppData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.ZoneId

class WidgetSnapshotTest {
    @get:Rule val folder = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Paris")
    // The fixture marks every session absent; the plan hides future absent ones, so start from unmarked sessions.
    private val fixture: AppData = ApiClient.json.decodeFromString(javaClass.getResource("/api/data.json")!!.readText())
    private val base: AppData = fixture.copy(events = fixture.events.map { it.copy(status = null) })
    private fun at(s: String) = Instant.parse(s).toEpochMilli()

    // Fixture sessions: Maths Mon 2 Nov 08:00-10:00 UTC (id 1), Algorithmique Tue 3 Nov 13:00-15:00 (id 3), Maths Wed 4 Nov (id 2).
    @Test fun signedOutHasNothingInIt() {
        val s = buildWidgetPlan(null, 1L, zone)
        assertFalse(s.signedIn)
        assertTrue(s.classes.isEmpty() && s.todo.isEmpty())
    }

    @Test fun classesAreUpcomingSessionsInOrderWithCourseNameAndPlace() {
        val s = buildWidgetPlan(base, at("2026-11-02T07:00:00Z"), zone)
        assertTrue(s.signedIn)
        assertEquals(listOf(1L, 3L, 2L), s.classes.map { it.id })
        assertEquals(WidgetClass(1, "Mathématiques", at("2026-11-02T08:00:00Z"), at("2026-11-02T10:00:00Z"), "Amphi 1"), s.classes[0])
        assertEquals("Algorithmique", s.classes[1].title)
        assertEquals("Salle 302", s.classes[1].where)
    }

    @Test fun theClassYouAreInComesFirstAndFinishedOnesAreLeftOut() {
        val during = buildWidgetPlan(base, at("2026-11-02T09:00:00Z"), zone)
        assertEquals(1L, during.currentClass(at("2026-11-02T09:00:00Z"))?.id)
        val after = buildWidgetPlan(base, at("2026-11-02T10:00:00Z"), zone)
        assertEquals(listOf(3L, 2L), after.classes.map { it.id })
        // The stored list moves on by itself: the same snapshot, a later time.
        assertEquals(3L, during.currentClass(at("2026-11-02T10:00:01Z"))?.id)
        assertNull(during.currentClass(at("2026-11-05T00:00:00Z")))
    }

    @Test fun listCoversTheNextDaysBeyondEightSessionsWithinTheWindow() {
        val now = at("2026-11-02T07:00:00Z")
        val template = base.events.first()
        // Six sessions a day for ten days: 60 sessions; only the next 8 days (up to 24) are kept.
        val many = (0 until 60).map { i ->
            val start = now + (i / 6) * 86_400_000L + (i % 6) * 3_600_000L + 3_600_000L
            template.copy(id = 1000L + i, start_at = start, end_at = start + 3_000_000L)
        }
        val s = buildWidgetPlan(base.copy(events = many), now, zone)
        assertEquals(24, s.classes.size)
        assertTrue("covers the third day", s.classes.last().start >= now + 3 * 86_400_000L)
        val sparse = many.filter { it.start_at < now + 9 * 86_400_000L && (it.id % 6L) == 0L }
        assertTrue(buildWidgetPlan(base.copy(events = sparse), now, zone).classes.all { it.start < now + 8 * 86_400_000L })
    }

    @Test fun untrackedExcusedAndFutureAbsentSessionsAreLeftOutLikeThePlan() {
        val now = at("2026-11-02T07:00:00Z")
        val untracked = base.copy(courses = base.courses.map { if (it.id == 1L) it.copy(tracked = false) else it })
        assertEquals(listOf(3L), buildWidgetPlan(untracked, now, zone).classes.map { it.id })
        val excused = base.copy(events = base.events.map { if (it.id == 3L) it.copy(status = "excused") else it })
        assertEquals(listOf(1L, 2L), buildWidgetPlan(excused, now, zone).classes.map { it.id })
    }

    @Test fun futureAbsentSessionsAreHiddenButOneInProgressStays() {
        val absent = fixture // every session absent
        assertTrue(buildWidgetPlan(absent, at("2026-11-02T07:00:00Z"), zone).classes.isEmpty())
        assertEquals(listOf(1L), buildWidgetPlan(absent, at("2026-11-02T09:00:00Z"), zone).classes.map { it.id })
    }

    @Test fun allDaySessionsAreNotClasses() {
        val allDay = base.events.first().copy(id = 99, start_at = at("2026-11-02T00:00:00Z"), end_at = at("2026-11-03T00:00:00Z"))
        val s = buildWidgetPlan(base.copy(events = base.events + allDay), at("2026-11-02T07:00:00Z"), zone)
        assertFalse(99L in s.classes.map { it.id })
    }

    @Test fun doNextIsThePlansTopThreeInItsOrder() {
        val now = at("2026-11-02T07:00:00Z")
        val extra = (10L..14L).map { i -> base.homework.first().copy(id = i, title = "Task $i", due_at = at("2026-11-0${3 + (i - 10) % 3}T12:00:00Z"), due_date = null, due_time = null) }
        val data = base.copy(homework = base.homework + extra)
        val s = buildWidgetPlan(data, now, zone)
        val plan = com.knhdixh.attendance80.logic.buildPlan(data.courses, data.events, data.homework, now, zone.id)
        assertEquals(3, s.todo.size)
        assertEquals(plan.todo.take(3).map { it.id }, s.todo.map { it.id })
        assertEquals(plan.todo.take(3).map { it.title }, s.todo.map { it.title })
        assertEquals(plan.todo.take(3).map { it.due_at }, s.todo.map { it.dueAt })
    }

    @Test fun doneHomeworkIsNotListed() {
        val data = base.copy(homework = base.homework.map { it.copy(done = true) })
        assertTrue(buildWidgetPlan(data, at("2026-11-02T07:00:00Z"), zone).todo.isEmpty())
    }

    @Test fun timerWhileIdleSaysWhatStartsNext() {
        val s = FocusSettings(focus = 25, short = 5, long = 15)
        assertEquals(WidgetTimer(nextMinutes = 25), buildWidgetTimer(FocusTimer(), s, 1000))
        assertEquals(5, buildWidgetTimer(FocusTimer(next = "short"), s, 1000).nextMinutes)
        assertEquals(40, buildWidgetTimer(FocusTimer(custom = 40), s, 1000).nextMinutes)
    }

    @Test fun timerWhileRunningHasItsEndAndLabel() {
        val t = FocusTimer(phase = "focus", startedAt = 1_000, endsAt = 1_501_000, target = FocusTarget(label = "Maths Exercise 4"))
        val w = buildWidgetTimer(t, FocusSettings(), 60_000)
        assertTrue(w.running); assertFalse(w.paused)
        assertEquals(1_501_000L, w.endsAt)
        assertEquals("Maths Exercise 4", w.label)
    }

    @Test fun timerWhilePausedKeepsWhatIsLeft() {
        val t = FocusTimer(phase = "focus", startedAt = 0, endsAt = 1_500_000, pausedAt = 600_000)
        val w = buildWidgetTimer(t, FocusSettings(), 900_000)
        assertTrue(w.paused)
        assertEquals(900_000L, w.leftMs)
    }

    @Test fun storeKeepsPlanAndTimerSeparately() {
        val store = WidgetStore(File(folder.root, "snapshots/widget.json"))
        assertEquals(WidgetSnapshot(), store.read())
        store.updateTimer(WidgetTimer(phase = "focus", running = true, endsAt = 5))
        store.updatePlan(buildWidgetPlan(base, at("2026-11-02T07:00:00Z"), zone))
        val s = store.read()
        assertTrue(s.signedIn); assertEquals(3, s.classes.size); assertEquals(5L, s.timer.endsAt)
        store.updateTimer(WidgetTimer())
        assertEquals(3, store.read().classes.size)
        store.updatePlan(buildWidgetPlan(null, 9, zone))
        assertTrue(store.read().classes.isEmpty())
        assertFalse(store.read().signedIn)
    }

    @Test fun storeSurvivesAGarbledFile() {
        val f = File(folder.root, "widget.json").also { it.writeText("{nope") }
        assertEquals(WidgetSnapshot(), WidgetStore(f).read())
    }

    @Test fun refreshIsAtTheClassBoundaryOrMidnightOrADueTime() {
        val s = buildWidgetPlan(base, at("2026-11-02T07:00:00Z"), zone).copy(todo = emptyList())
        assertEquals(at("2026-11-02T08:00:00Z") + 1000, nextWidgetRefresh(s, at("2026-11-02T07:00:00Z"), zone))
        assertEquals(at("2026-11-02T10:00:00Z") + 1000, nextWidgetRefresh(s, at("2026-11-02T09:00:00Z"), zone))
        // Nothing sooner than midnight in the user's zone when no class is left.
        val none = s.copy(classes = emptyList())
        assertEquals(at("2026-11-02T23:00:00Z") + 1000, nextWidgetRefresh(none, at("2026-11-02T12:00:00Z"), zone))
        val due = none.copy(todo = listOf(WidgetTodo(1, "x", at("2026-11-02T13:00:00Z"))))
        assertEquals(at("2026-11-02T13:00:00Z") + 1000, nextWidgetRefresh(due, at("2026-11-02T12:00:00Z"), zone))
    }
}
