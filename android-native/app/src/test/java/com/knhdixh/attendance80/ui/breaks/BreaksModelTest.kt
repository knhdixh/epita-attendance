package com.knhdixh.attendance80.ui.breaks

import com.knhdixh.attendance80.logic.findBreakWindows
import com.knhdixh.attendance80.logic.simulateBreak
import org.junit.Assert.*
import org.junit.Test

class BreaksModelTest {
    // The same steps as clickDay in public/breaks-ui.js.
    @Test fun tappingPicksFirstThenLastDay() {
        val one = BreakPick().tap("2026-11-10")
        assertEquals(BreakPick(from = "2026-11-10", to = "2026-11-10", phase = "end"), one)
        val two = one.tap("2026-11-12")
        assertEquals(Triple("2026-11-10", "2026-11-12", "start"), Triple(two.from, two.to, two.phase))
        // A last day before the first one starts over from there.
        val back = one.tap("2026-11-05")
        assertEquals(Triple("2026-11-05", "2026-11-05", "end"), Triple(back.from, back.to, back.phase))
        // A tap after a finished range starts a new break.
        val again = two.tap("2026-11-20")
        assertEquals(Triple("2026-11-20", "2026-11-20", "end"), Triple(again.from, again.to, again.phase))
    }
    @Test fun movingOnlyTheFirstDayKeepsALaterEnd() {
        val range = BreakPick(from = "2026-11-10", to = "2026-11-14")
        val moved = range.copy(phase = "start", keepEnd = true).tap("2026-11-09")
        assertEquals(BreakPick(from = "2026-11-09", to = "2026-11-14", phase = "start", keepEnd = false), moved)
        // Past the end, it starts a new one-day break instead.
        val past = range.copy(phase = "start", keepEnd = true).tap("2026-11-20")
        assertEquals(Triple("2026-11-20", "2026-11-20", "end"), Triple(past.from, past.to, past.phase))
    }
    @Test fun useAndClear() {
        val used = BreakPick(label = "Trip", phase = "end").use("2026-12-14", "2026-12-15")
        assertEquals(BreakPick(from = "2026-12-14", to = "2026-12-15", label = "Trip", month = "2026-12"), used)
        assertEquals(BreakPick(len = 3, month = "2026-12"), used.copy(len = 3).clear())
    }
    @Test fun deriveMatchesThePortedMaths() {
        val data = breaksData()
        val base = BreaksViewModel.derive(data, breaksNow, breaksZone)
        assertEquals("2026-11-02", base.model.today)
        assertEquals("2026-12-18", base.model.lastDay)
        // Missing one day: free at weekends, safe on a normal weekday, impossible on the exam day.
        assertEquals("free", base.days.getValue("2026-11-07").level)
        assertEquals("ok", base.days.getValue("2026-11-04").level)
        assertEquals("fail", base.days.getValue("2026-11-13").level)
        assertTrue(base.days.getValue("2026-11-13").toString(), base.days.getValue("2026-11-13").blockers.any { it.contains("exam") })
        assertFalse("past days are left out", base.days.containsKey("2026-11-01"))
        assertFalse("days after the calendar are left out", base.days.containsKey("2026-12-19"))

        val none = BreaksViewModel.state(base, 7, "", "")
        assertNull(none.sim)
        assertEquals(findBreakWindows(base.model, 7, limit = 4), none.windows)
        assertTrue(none.windows.windows.size in 1..4)

        val picked = BreaksViewModel.state(base, 3, "2026-11-09", "2026-11-13")
        assertEquals(simulateBreak(base.model, "2026-11-09", "2026-11-13"), picked.sim)
        assertEquals("fail", picked.sim!!.level)
        assertEquals(1, picked.sim!!.conflicts.size)
    }
}
