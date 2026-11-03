package com.knhdixh.attendance80.notify

import com.knhdixh.attendance80.net.*
import org.junit.Assert.*
import org.junit.Test

class ClassReminderTest {
    private val maths = course(1, "Mathématiques")
    private val skipped = course(2, "Not mine", tracked = false)
    private fun classes(vararg e: EventDto, lead: Int = 10, on: Boolean = true) =
        emptyData(courses = listOf(maths, skipped), events = e.toList(), notify = NotifySettingsDto(classes = on, classLead = lead, habits = false, tz = "Europe/Paris"))
    private fun plan(d: AppData, now: String) = planReminders(d, NotifyChoices(checkin = false, exam = false), at(now), PARIS)

    // ---- classes (task 6)

    @Test fun aClassReminderGoesLeadMinutesBeforeTheStart() {
        val r = plan(classes(event(1, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00", location = "Room B12")), "2026-11-03T08:00:00").single()
        assertEquals(at("2026-11-03T08:50:00"), r.at)
        assertEquals("Mathématiques in 10 min", r.title)
        assertEquals("09:00–11:00 · Room B12", r.body)
        assertEquals("a80://session/1", r.route)
        assertEquals("class:1:${at("2026-11-03T09:00:00")}", r.key)
    }

    @Test fun theLeadComesFromTheAccountsSetting() {
        val e = event(1, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00")
        assertEquals(at("2026-11-03T08:15:00"), plan(classes(e, lead = 45), "2026-11-03T07:00:00").single().at)
        assertEquals("Mathématiques starts now", plan(classes(e, lead = 0), "2026-11-03T09:00:00").single().title)
        assertTrue(plan(classes(e, on = false), "2026-11-03T08:00:00").isEmpty())
    }

    @Test fun trackedCoursesOnlyAndOnlyPresentOrUnmarkedSessions() {
        val d = classes(
            event(1, 1, "2026-11-03T09:00:00", "2026-11-03T10:00:00"),
            event(2, 1, "2026-11-03T10:00:00", "2026-11-03T11:00:00", status = "present"),
            event(3, 1, "2026-11-03T11:00:00", "2026-11-03T12:00:00", status = "absent"),
            event(4, 1, "2026-11-03T12:00:00", "2026-11-03T13:00:00", status = "excused"),
            event(5, 2, "2026-11-03T13:00:00", "2026-11-03T14:00:00"),
        )
        assertEquals(listOf(1L, 2L), plan(d, "2026-11-03T07:00:00").map { it.ref })
    }

    @Test fun aLateAlarmCountsTheMinutesLeftAndAReminderIsDroppedOnceTheClassHasStarted() {
        val d = classes(event(1, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00"))
        assertEquals("Mathématiques in 7 min", plan(d, "2026-11-03T08:53:00").single().title)
        assertEquals("Mathématiques in 6 min", plan(d, "2026-11-03T08:54:00").single().title)
        assertEquals("Mathématiques in 4 min", plan(d, "2026-11-03T08:56:00").single().title) // 6 min after its time: still useful before the start
        assertEquals("Mathématiques starts now", plan(d, "2026-11-03T09:00:00").single().title)
        assertTrue(plan(d, "2026-11-03T09:01:00").isEmpty()) // after the start: gone
    }

    @Test fun aReminderWithNoLeadKeepsItsFiveMinuteGrace() {
        val d = classes(event(1, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00"), lead = 0)
        assertEquals("Mathématiques starts now", plan(d, "2026-11-03T09:05:00").single().title)
        assertTrue(plan(d, "2026-11-03T09:06:00").isEmpty())
    }

    @Test fun theLeadStaysRightAcrossAClockChange() {
        // First Monday class after the clocks go back (Sun 25 Oct 2026): the lead is real minutes, so 10 min before 09:00 is 08:50.
        val r = plan(classes(event(1, 1, "2026-10-26T09:00:00", "2026-10-26T10:00:00")), "2026-10-26T07:00:00").single()
        assertEquals("2026-10-26T08:50", r.at.iso())
    }
}
