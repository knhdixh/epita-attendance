package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.FocusSessionDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.math.abs

class FocusStatsGoldenTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadResource(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: throw IllegalArgumentException("Resource not found: $name")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun assertClose(expected: Double?, actual: Double?, msg: String = "") {
        if (expected == null && actual == null) return
        if (expected == null || actual == null) {
            throw AssertionError("Mismatch: expected=$expected, actual=$actual. $msg")
        }
        assertTrue("Expected $expected but got $actual (diff ${abs(expected - actual)} > 1e-9). $msg", abs(expected - actual) <= 1e-9)
    }

    @Serializable
    private data class FocusStatsGoldenData(
        val dailyTotals: Map<String, DayTotal>,
        val minutesByHomework: Map<String, Double>,
        val focusStats7: FocusStatsResult,
        val focusStats30: FocusStatsResult,
        val focusStats365: FocusStatsResult,
        val focusSummaryParis: FocusSummaryResult,
        val focusSummaryHelsinki: FocusSummaryResult,
    )

    @Serializable
    private data class FocusFixture(
        val yearSessions: List<FocusSessionDto>,
        val refNow: Long,
    )

    @Test
    fun testDailyTotalsAndMinutesByHomeworkGolden() {
        val golden = json.decodeFromString<FocusStatsGoldenData>(loadResource("golden/focus_stats_golden.json"))
        val fixture = json.decodeFromString<FocusFixture>(loadResource("fixtures/focus.json"))

        // Daily totals
        val actTotals = dailyTotals(fixture.yearSessions, ZoneId.systemDefault())
        for ((k, exp) in golden.dailyTotals) {
            val act = actTotals[k]
            assertTrue("Key $k should be in dailyTotals", act != null)
            assertClose(exp.minutes, act!!.minutes, "dailyTotal($k) minutes")
            assertEquals("dailyTotal($k) count", exp.count, act.count)
        }

        // Minutes by homework
        val actHw = minutesByHomework(fixture.yearSessions)
        for ((kStr, expMin) in golden.minutesByHomework) {
            val k = kStr.toLong()
            val actMin = actHw[k]
            assertTrue("Homework $k should be in minutesByHomework", actMin != null)
            assertClose(expMin, actMin, "minutesByHomework($k)")
        }
    }

    @Test
    fun testFocusStatsGolden() {
        val golden = json.decodeFromString<FocusStatsGoldenData>(loadResource("golden/focus_stats_golden.json"))
        val fixture = json.decodeFromString<FocusFixture>(loadResource("fixtures/focus.json"))

        for ((range, exp) in listOf(7 to golden.focusStats7, 30 to golden.focusStats30, 365 to golden.focusStats365)) {
            val act = focusStats(fixture.yearSessions, now = fixture.refNow, range = range, zoneId = ZoneId.systemDefault())
            val tag = "range_$range"

            assertClose(exp.today.minutes, act.today.minutes, "$tag: today minutes")
            assertEquals("$tag: today count", exp.today.count, act.today.count)
            assertEquals("$tag: streak", exp.streak, act.streak)
            assertClose(exp.thisWeek, act.thisWeek, "$tag: thisWeek")
            assertClose(exp.lastWeek, act.lastWeek, "$tag: lastWeek")

            // Week
            assertEquals("$tag: week size", exp.week.size, act.week.size)
            for (i in exp.week.indices) {
                val ew = exp.week[i]
                val aw = act.week[i]
                assertEquals("$tag: week[$i] ms", ew.ms, aw.ms)
                assertClose(ew.minutes, aw.minutes, "$tag: week[$i] minutes")
                assertEquals("$tag: week[$i] count", ew.count, aw.count)
                assertEquals("$tag: week[$i] future", ew.future, aw.future)
            }

            // Range breakdown
            val er = exp.range
            val ar = act.range
            assertEquals("$tag: range days", er.days, ar.days)
            assertClose(er.minutes, ar.minutes, "$tag: range minutes")
            assertEquals("$tag: range count", er.count, ar.count)
            assertEquals("$tag: range activeDays", er.activeDays, ar.activeDays)
            assertClose(er.perActiveDay, ar.perActiveDay, "$tag: range perActiveDay")
            assertEquals("$tag: range peakHour", er.peakHour, ar.peakHour)

            // Best day
            if (exp.best != null) {
                assertEquals("$tag: best key", exp.best.key, act.best?.key)
                assertClose(exp.best.minutes, act.best?.minutes, "$tag: best minutes")
                assertEquals("$tag: best count", exp.best.count, act.best?.count)
            }
        }
    }

    @Test
    fun testFocusSummaryGolden() {
        val golden = json.decodeFromString<FocusStatsGoldenData>(loadResource("golden/focus_stats_golden.json"))
        val fixture = json.decodeFromString<FocusFixture>(loadResource("fixtures/focus.json"))

        for ((tz, exp) in listOf("Europe/Paris" to golden.focusSummaryParis, "Europe/Helsinki" to golden.focusSummaryHelsinki)) {
            val act = focusSummary(fixture.yearSessions, now = fixture.refNow, tz = tz)
            val tag = "tz_$tz"

            assertEquals("$tag: today minutes", exp.today.minutes, act.today.minutes)
            assertEquals("$tag: today pomodoros", exp.today.pomodoros, act.today.pomodoros)
            assertEquals("$tag: streak", exp.streak, act.streak)
            assertEquals("$tag: last7 minutes", exp.last7.minutes, act.last7.minutes)
            assertEquals("$tag: last7 pomodoros", exp.last7.pomodoros, act.last7.pomodoros)

            assertEquals("$tag: last7 byDay size", exp.last7.byDay.size, act.last7.byDay.size)
            for (i in exp.last7.byDay.indices) {
                assertEquals("$tag: last7 byDay[$i] date", exp.last7.byDay[i].date, act.last7.byDay[i].date)
                assertEquals("$tag: last7 byDay[$i] minutes", exp.last7.byDay[i].minutes, act.last7.byDay[i].minutes)
            }

            assertEquals("$tag: last7 byCourse size", exp.last7.byCourse.size, act.last7.byCourse.size)
            for (i in exp.last7.byCourse.indices) {
                assertEquals("$tag: last7 byCourse[$i] id", exp.last7.byCourse[i].course_id, act.last7.byCourse[i].course_id)
                assertEquals("$tag: last7 byCourse[$i] minutes", exp.last7.byCourse[i].minutes, act.last7.byCourse[i].minutes)
            }
        }
    }
}
