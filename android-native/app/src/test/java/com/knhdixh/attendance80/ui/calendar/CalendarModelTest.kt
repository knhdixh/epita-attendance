package com.knhdixh.attendance80.ui.calendar

import com.knhdixh.attendance80.logic.CalendarMath
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CalendarModelTest {
    private val week = CalendarMath.period("week", calNow, calZone)

    @Test fun collectSplitsADayLikeTheWeb() {
        val d = collect(calData(), CalendarUi(), week.start, week.end, calZone)
        assertEquals("untracked courses are left out", listOf(1L, 2L, 3L, 4L), d.sessions.map { it.id })
        val wed = dayItems(d, at("2026-11-04", 0), calZone)
        assertEquals(listOf(1L, 2L), wed.timed.map { it.id })
        assertEquals(setOf(1L, 2L), clashes(wed.timed))
        assertEquals("a done to-do's block shows while Done is on", listOf(13L), wed.blocks.map { it.h.id })
        val fri = dayItems(d, at("2026-11-06", 0), calZone)
        assertEquals(listOf(4L), fri.allDay.map { it.id })
        assertEquals(listOf(10L, 12L), fri.dueTimed.map { it.id })
        assertEquals(listOf(30L), fri.cds.map { it.cd.id })
        assertEquals(listOf(20L), dayItems(d, at("2026-11-03", 0), calZone).fx.map { it.id })
        // Breaks appear on their days (the next week here).
        val next = CalendarMath.period("week", at("2026-11-09", 12), calZone)
        assertEquals(listOf(40L), dayItems(collect(calData(), CalendarUi(), next.start, next.end, calZone), at("2026-11-09", 0), calZone).brks.map { it.id })
    }

    @Test fun layersHideWhatTheySay() {
        val off = CalendarUi(layers = LAYERS.associate { it.first to false })
        val d = collect(calData(), off, week.start, week.end, calZone)
        assertTrue(d.due.isEmpty() && d.fx.isEmpty() && d.cds.isEmpty() && d.brks.isEmpty())
        assertEquals("the done to-do's block is hidden", listOf(10L), d.blocks.map { it.h.id })
        assertEquals("sessions always show", 4, d.sessions.size)
    }

    @Test fun proposalsReplaceBlocksAsGhosts() {
        val ui = CalendarUi(proposals = listOf(Proposal(10, at("2026-11-04", 17), 45)))
        val d = collect(calData(), ui, week.start, week.end, calZone)
        assertFalse(d.blocks.any { it.h.id == 10L })
        assertEquals(listOf(Triple(10L, at("2026-11-04", 17), at("2026-11-04", 17, 45))), d.ghosts.map { Triple(it.h.id, it.start, it.end) })
    }

    @Test fun toPlanIsOverdueFirstThenByLatestStart() {
        // Planned report has a block that hasn't passed, so it's not here; Done task is done.
        assertEquals(listOf(11L, 12L), unplannedTodos(calData().homework, calNow).map { it.id })
        // Once its block has passed without it being done, it's back.
        assertEquals(listOf(11L, 12L, 10L), unplannedTodos(calData().homework, at("2026-11-05", 18)).map { it.id })
        assertEquals(90, dropLength(calData().homework[2]))
        assertEquals(120, dropLength(calData().homework[2].copy(effort_minutes = 300)))
    }

    @Test fun planMyWeekUsesThePortedMaths() {
        val r = autoPlanNow(calData(), calNow, calZone) as AutoPlanResult.Planned
        assertEquals(setOf(11L, 12L), r.proposals.map { it.id }.toSet())
        r.proposals.forEach { p ->
            assertTrue("starts after now", p.start >= calNow)
            assertTrue("not in Wednesday's classes (plus 10 min)", p.start >= at("2026-11-04", 12, 10) || p.start + p.minutes * MIN <= at("2026-11-04", 8, 50))
        }
        assertEquals(90, r.proposals.first { it.id == 12L }.minutes)
        val none = calData().copy(homework = emptyList())
        assertEquals(AutoPlanResult.Nothing("Every open to-do already has a time."), autoPlanNow(none, calNow, calZone))
        val far = calData().copy(homework = listOf(calData().homework[2].copy(due_at = at("2026-12-20", 18), due_date = "2026-12-20")))
        assertEquals(AutoPlanResult.Nothing("Nothing due in the next two weeks needs a time."), autoPlanNow(far, calNow, calZone))
    }

    @Test fun gridHoursTitlesAndBodies() {
        val d = collect(calData(), CalendarUi(), week.start, week.end, calZone)
        assertEquals(7 to 22, hourRange(week.days.map { dayItems(d, it, calZone) }, calZone))
        val late = calData().copy(events = calData().events + com.knhdixh.attendance80.net.EventDto(9, 1, "Late", start_at = at("2026-11-04", 21), end_at = at("2026-11-04", 23, 30)))
        val d2 = collect(late, CalendarUi(), week.start, week.end, calZone)
        assertEquals(7 to 24, hourRange(week.days.map { dayItems(d2, it, calZone) }, calZone))
        assertEquals("November 2026", calendarTitle("week", calNow, week, calZone))
        assertEquals("Oct – Nov 2026", calendarTitle("3day", at("2026-10-30", 12), CalendarMath.period("3day", at("2026-10-30", 12), calZone), calZone))
        assertEquals("30 min", lenLabel(30)); assertEquals("1.5 h", lenLabel(90)); assertEquals("2 h", lenLabel(120))
        assertEquals(buildJsonObject { put("planned_at", JsonNull); put("planned_minutes", JsonNull) }, planBody(null, 60))
        assertEquals(buildJsonObject { put("planned_at", 5L); put("planned_minutes", 30) }, planBody(5L, 30))
        assertEquals("""{"items":[{"id":1,"planned_at":2,"planned_minutes":3}]}""", keepBody(listOf(Proposal(1, 2, 3))).toString())
    }
}
