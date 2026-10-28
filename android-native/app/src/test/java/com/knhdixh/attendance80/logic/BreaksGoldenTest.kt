package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BreaksGoldenTest {

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
    private data class IsDateItem(val input: String, val output: Boolean)

    @Serializable
    private data class AddDaysItem(val date: String, val n: Int, val output: String)

    @Serializable
    private data class WeekdayOfItem(val input: String, val output: Int)

    @Serializable
    private data class DayDiffItem(val a: String, val b: String, val output: Int)

    @Serializable
    private data class MakeDayKeyItem(val tz: String, val ms: Long, val output: String)

    @Serializable
    private data class DateHelperResults(
        val isDate: List<IsDateItem>,
        val addDays: List<AddDaysItem>,
        val weekdayOf: List<WeekdayOfItem>,
        val dayDiff: List<DayDiffItem>,
        val makeDayKey: List<MakeDayKeyItem>,
    )

    @Serializable
    private data class ModelSummary(
        val today: String,
        val lastDay: String,
        val holidays: List<String>,
        val noSkipCourseIds: List<Long>,
    )

    @Serializable
    private data class SimItem(
        val from: String,
        val to: String,
        val result: BreakSimulation,
    )

    @Serializable
    private data class WindowSearchParams(
        val days: Int,
        val from: String? = null,
        val to: String? = null,
    )

    @Serializable
    private data class WindowSearchItem(
        val params: WindowSearchParams,
        val result: BreakWindowsResult,
    )

    @Serializable
    private data class BreakCase(
        val timePoint: String,
        val tz: String,
        val now: Long,
        val threshold: Double,
        val unit: String,
        val modelSummary: ModelSummary,
        val simulations: List<SimItem>,
        val windowSearches: List<WindowSearchItem>,
        val longest: LongestSafeWindow? = null,
        val weekOverview4: List<WeekOverviewItem>,
        val weekOverview12: List<WeekOverviewItem>,
        val grid3: List<List<StartDayGridItem>>,
        val grid7: List<List<StartDayGridItem>>,
    )

    @Serializable
    private data class HolidayReItem(val input: String, val output: Boolean)

    @Serializable
    private data class BreaksGoldenData(
        val dateHelperResults: DateHelperResults,
        val holidayReCases: List<HolidayReItem> = emptyList(),
        val breaksCases: List<BreakCase>,
    )

    @Serializable
    private data class SemesterFixture(
        val courses: List<CourseDto>,
        val events: List<EventDto>,
    )

    @Test
    fun testDateHelpersGolden() {
        val golden = json.decodeFromString<BreaksGoldenData>(loadResource("golden/breaks_golden.json"))
        val helpers = golden.dateHelperResults

        for (item in helpers.isDate) {
            assertEquals("isDate(${item.input})", item.output, isDate(item.input))
        }

        for (item in helpers.addDays) {
            assertEquals("addDays(${item.date}, ${item.n})", item.output, addDays(item.date, item.n))
        }

        for (item in helpers.weekdayOf) {
            assertEquals("weekdayOf(${item.input})", item.output, weekdayOf(item.input))
        }

        for (item in helpers.dayDiff) {
            assertEquals("dayDiff(${item.a}, ${item.b})", item.output, dayDiff(item.a, item.b))
        }

        for (item in helpers.makeDayKey) {
            val keyFn = makeDayKey(item.tz)
            assertEquals("makeDayKey(${item.tz})(${item.ms})", item.output, keyFn(item.ms))
        }

        for (item in golden.holidayReCases) {
            assertEquals("HOLIDAY_RE(${item.input})", item.output, HOLIDAY_RE.containsMatchIn(item.input))
        }
    }

    @Test
    fun testBreaksCasesGolden() {
        val golden = json.decodeFromString<BreaksGoldenData>(loadResource("golden/breaks_golden.json"))
        val semester = json.decodeFromString<SemesterFixture>(loadResource("fixtures/semester.json"))

        for (case in golden.breaksCases) {
            val m = breakModel(
                courses = semester.courses,
                events = semester.events,
                threshold = case.threshold,
                unit = case.unit,
                now = case.now,
                tz = case.tz,
            )

            val tag = "${case.timePoint}_${case.threshold}_${case.unit}"
            assertEquals("$tag: today", case.modelSummary.today, m.today)
            assertEquals("$tag: lastDay", case.modelSummary.lastDay, m.lastDay)
            assertEquals("$tag: holidays", case.modelSummary.holidays.toSet(), m.holidays)
            assertEquals("$tag: noSkip", case.modelSummary.noSkipCourseIds.toSet(), m.noSkip.map { it.id }.toSet())

            // Test simulations
            for (simCase in case.simulations) {
                val act = simulateBreak(m, simCase.from, simCase.to)
                val exp = simCase.result
                val simTag = "$tag: sim [${simCase.from}, ${simCase.to}]"
                assertEquals("$simTag from", exp.from, act.from)
                assertEquals("$simTag to", exp.to, act.to)
                assertEquals("$simTag days", exp.days, act.days)
                assertEquals("$simTag level", exp.level, act.level)
                assertEquals("$simTag sessions count", exp.sessions.size, act.sessions.size)
                assertEquals("$simTag conflicts count", exp.conflicts.size, act.conflicts.size)
                assertEquals("$simTag holidayDays", exp.holidayDays, act.holidayDays)
                assertEquals("$simTag courses count", exp.courses.size, act.courses.size)
                for (ci in exp.courses.indices) {
                    val expC = exp.courses[ci]
                    val actC = act.courses[ci]
                    assertEquals("$simTag course ${expC.course.name} skipped", expC.skipped, actC.skipped)
                    assertEquals("$simTag course ${expC.course.name} level", expC.level, actC.level)
                    assertClose(expC.after.remaining, actC.after.remaining, "$simTag course ${expC.course.name} remaining")
                }
            }

            // Test window searches
            for (wsCase in case.windowSearches) {
                val act = findBreakWindows(m, days = wsCase.params.days, from = wsCase.params.from, to = wsCase.params.to)
                val exp = wsCase.result
                val wsTag = "$tag: window search days=${wsCase.params.days}"
                assertEquals("$wsTag windows count", exp.windows.size, act.windows.size)
                for (wi in exp.windows.indices) {
                    val expW = exp.windows[wi]
                    val actW = act.windows[wi]
                    assertEquals("$wsTag window $wi from", expW.from, actW.from)
                    assertEquals("$wsTag window $wi to", expW.to, actW.to)
                    assertEquals("$wsTag window $wi days", expW.days, actW.days)
                    assertEquals("$wsTag window $wi sessions", expW.sessions, actW.sessions)
                    assertEquals("$wsTag window $wi level", expW.level, actW.level)
                    if (expW.margin == null) {
                        assertTrue("$wsTag window $wi margin should be infinite", actW.margin == null || actW.margin == Double.POSITIVE_INFINITY)
                    } else {
                        assertClose(expW.margin, actW.margin, "$wsTag window $wi margin")
                    }
                }
                if (exp.longest != null) {
                    assertEquals("$wsTag longest days", exp.longest.days, act.longest?.days)
                    assertEquals("$wsTag longest from", exp.longest.from, act.longest?.from)
                    assertEquals("$wsTag longest to", exp.longest.to, act.longest?.to)
                }
            }

            // Test longest safe
            val actLongest = longestSafe(m, "2026-10-15", "2026-12-04")
            if (case.longest != null) {
                assertEquals("$tag longestSafe days", case.longest.days, actLongest?.days)
                assertEquals("$tag longestSafe from", case.longest.from, actLongest?.from)
                assertEquals("$tag longestSafe to", case.longest.to, actLongest?.to)
            }

            // Test weekOverview (4 and 12 weeks)
            val actWo4 = weekOverview(m, 4)
            assertEquals("$tag weekOverview4 size", case.weekOverview4.size, actWo4.size)
            for (wi in case.weekOverview4.indices) {
                val expW = case.weekOverview4[wi]
                val actW = actWo4[wi]
                assertEquals("$tag weekOverview4[$wi] from", expW.from, actW.from)
                assertEquals("$tag weekOverview4[$wi] level", expW.level, actW.level)
                assertEquals("$tag weekOverview4[$wi] sessions", expW.sessions, actW.sessions)
            }

            // Test startDayGrid
            val actGrid3 = startDayGrid(m, 3)
            assertEquals("$tag grid3 weeks", case.grid3.size, actGrid3.size)
            for (wi in case.grid3.indices) {
                for (di in case.grid3[wi].indices) {
                    val expCell = case.grid3[wi][di]
                    val actCell = actGrid3[wi][di]
                    assertEquals("$tag grid3[$wi][$di] date", expCell.date, actCell.date)
                    assertEquals("$tag grid3[$wi][$di] level", expCell.level, actCell.level)
                    assertEquals("$tag grid3[$wi][$di] sessions", expCell.sessions, actCell.sessions)
                    assertEquals("$tag grid3[$wi][$di] past", expCell.past, actCell.past)
                    assertEquals("$tag grid3[$wi][$di] beyond", expCell.beyond, actCell.beyond)
                }
            }
        }
    }
}
