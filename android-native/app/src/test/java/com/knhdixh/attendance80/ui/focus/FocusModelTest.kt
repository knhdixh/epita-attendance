package com.knhdixh.attendance80.ui.focus

import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.net.FocusSessionDto
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FocusModelTest {
    @Test fun fractionalMinutesElapsedWeekAndHeatLevels() {
        val zone = ZoneId.of("Europe/Paris"); val now = Instant.parse("2026-03-25T12:00:00Z").toEpochMilli()
        val today = dayStart(now, zone); val monday = weekStart(now, zone)
        fun session(id: String, at: Long, seconds: Int) = FocusSessionDto(client_id = id, started_at = at, ended_at = at + seconds * 1000L, planned_seconds = seconds, focus_seconds = seconds)
        val sessions = listOf(session("today", today, 91), session("last", addDays(monday, -7, zone), 120))
        val stats = focusStats(sessions, now, 7, zone)
        assertEquals(91.0 / 60.0, stats.today.minutes, 1e-8); assertEquals(91.0 / 60.0, stats.thisWeek, 1e-8); assertEquals(2.0, stats.lastWeek, 1e-8)
        val first = addDays(monday, -52 * 7, zone)
        assertEquals(monday, addDays(first, 52 * 7, zone)); assertEquals(53 * 7, (0 until 53).flatMap { w -> (0..6).map { addDays(first, w * 7 + it, zone) } }.distinct().size)
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(0.0, 24.99, 25.0, 50.0, 100.0).map { focusHeatLevel(it, 100) })
        assertTrue(stats.week.drop(3).all { it.future })
    }
}
