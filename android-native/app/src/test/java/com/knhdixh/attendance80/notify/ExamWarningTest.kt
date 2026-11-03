package com.knhdixh.attendance80.notify

import org.junit.Assert.*
import org.junit.Test

class ExamWarningTest {
    private val maths = course(1, "Mathématiques")
    private val physics = course(2, "Physique", tag = "mandatory")
    private val sisu = course(3, "Sisu", attendance = false, tag = "exam")
    private val off = course(4, "Untracked", tracked = false, tag = "exam")

    private fun data(vararg e: com.knhdixh.attendance80.net.EventDto) = emptyData(courses = listOf(maths, physics, sisu, off), events = e.toList())
    private val morning = at("2026-11-03T07:00:00")

    @Test fun anExamDayGetsOneWarningAt0800() {
        val d = data(event(1, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00", tag = "exam", location = "Amphi 2"))
        val r = examWarnings(d, PARIS).single()
        assertEquals(at("2026-11-03T08:00:00"), r.at)
        assertEquals("Exam today", r.title)
        assertEquals("09:00–11:00 Mathématiques (exam) · Amphi 2", r.body)
        assertEquals("exam:2026-11-03", r.key)
        assertEquals("a80://today", r.route)
        assertTrue(r.actions.isEmpty())
    }

    @Test fun theCourseTagCountsToo() {
        val r = examWarnings(data(event(2, 2, "2026-11-03T14:00:00", "2026-11-03T16:00:00")), PARIS).single()
        assertEquals("Mandatory session today", r.title)
        assertEquals("14:00–16:00 Physique (mandatory)", r.body)
    }

    @Test fun twoOnTheSameDayAreOneNotificationListedInTimeOrder() {
        val d = data(
            event(3, 2, "2026-11-03T14:00:00", "2026-11-03T16:00:00"),
            event(4, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00", tag = "exam"),
            event(5, 1, "2026-11-03T17:00:00", "2026-11-03T18:00:00", tag = "exam"),
        )
        val r = examWarnings(d, PARIS).single()
        assertEquals("3 sessions you can't miss today", r.title)
        assertEquals(listOf("09:00–11:00 Mathématiques (exam)", "14:00–16:00 Physique (mandatory)", "17:00–18:00 Mathématiques (exam)"), r.body.lines())
        assertEquals("2 exams today", examWarnings(data(
            event(4, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00", tag = "exam"), event(5, 1, "2026-11-03T13:00:00", "2026-11-03T15:00:00", tag = "exam")), PARIS).single().title)
    }

    @Test fun normalOptionalExcusedUntrackedAndNoAttendanceSessionsAreNotWarnedAbout() {
        val d = data(
            event(6, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00"),
            event(7, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00", tag = "optional"),
            event(8, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00", tag = "exam", status = "excused"),
            event(9, 3, "2026-11-03T09:00:00", "2026-11-03T11:00:00"),
            event(10, 4, "2026-11-03T09:00:00", "2026-11-03T11:00:00"),
        )
        assertTrue(examWarnings(d, PARIS).isEmpty())
    }

    @Test fun aPresentOrAbsentExamStillCountsAsTheDayOfTheExam() {
        val d = data(event(11, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00", tag = "exam", status = "present"))
        assertEquals(1, examWarnings(d, PARIS).size)
    }

    @Test fun eachDayHasItsOwnWarning() {
        val d = data(event(12, 1, "2026-11-03T09:00:00", "2026-11-03T11:00:00", tag = "exam"), event(13, 1, "2026-11-04T09:00:00", "2026-11-04T11:00:00", tag = "exam"))
        assertEquals(listOf(at("2026-11-03T08:00:00"), at("2026-11-04T08:00:00")), examWarnings(d, PARIS).sortedBy { it.at }.map { it.at })
    }

    @Test fun theDayIsTheDevicesDayAndTheWarningStaysAt0800AcrossAClockChange() {
        // Clocks go back on 25 Oct 2026: 08:00 that morning is still 08:00 local (summer time at that hour is over: 03:00 → 02:00).
        val r = examWarnings(data(event(14, 1, "2026-10-26T09:00:00", "2026-10-26T11:00:00", tag = "exam")), PARIS).single()
        assertEquals("2026-10-26T08:00", r.at.iso())
        val sunday = examWarnings(data(event(15, 1, "2026-10-25T09:00:00", "2026-10-25T11:00:00", tag = "exam")), PARIS).single()
        assertEquals("2026-10-25T08:00", sunday.at.iso())
        // An evening exam in Paris is the next day in Helsinki: its warning goes on the morning of the day it is in the device's zone.
        val helsinki = java.time.ZoneId.of("Europe/Helsinki")
        val late = event(16, 1, "2026-11-03T23:30:00", "2026-11-04T00:30:00", tag = "exam")
        assertEquals("2026-11-04T08:00", examWarnings(data(late), helsinki).single().at.iso(helsinki))
    }

    @Test fun planKeepsTodayAndTomorrowAndLetsAMissedMorningStillGoForThreeHours() {
        val d = data(
            event(17, 1, "2026-11-03T14:00:00", "2026-11-03T16:00:00", tag = "exam"),
            event(18, 1, "2026-11-04T14:00:00", "2026-11-04T16:00:00", tag = "exam"),
            event(19, 1, "2026-11-05T14:00:00", "2026-11-05T16:00:00", tag = "exam"),
        )
        assertEquals(listOf("exam:2026-11-03", "exam:2026-11-04"), planReminders(d, NotifyChoices(checkin = false), morning, PARIS).map { it.key })
        assertEquals(listOf("exam:2026-11-03", "exam:2026-11-04"), planReminders(d, NotifyChoices(checkin = false), at("2026-11-03T10:30:00"), PARIS).map { it.key })
        assertEquals(listOf("exam:2026-11-04"), planReminders(d, NotifyChoices(checkin = false), at("2026-11-03T12:00:00"), PARIS).map { it.key })
        assertTrue(planReminders(d, NotifyChoices(checkin = false, exam = false), morning, PARIS).isEmpty())
    }
}
