package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class BreaksGoldenTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    private fun loadResource(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: throw IllegalArgumentException("Resource not found: $name")
        return stream.bufferedReader().use { it.readText() }
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
                val simTag = "$tag: sim [${simCase.from}, ${simCase.to}]"
                assertJsonEquals(
                    expected = json.encodeToJsonElement(simCase.result),
                    actual = json.encodeToJsonElement(act),
                    path = simTag
                )
            }

            // Test window searches
            for (wsCase in case.windowSearches) {
                val act = findBreakWindows(m, days = wsCase.params.days, from = wsCase.params.from, to = wsCase.params.to)
                val wsTag = "$tag: window search days=${wsCase.params.days}"
                assertJsonEquals(
                    expected = json.encodeToJsonElement(wsCase.result),
                    actual = json.encodeToJsonElement(act),
                    path = wsTag
                )
            }

            // Test longest safe
            val actLongest = longestSafe(m, "2026-10-15", "2026-12-04")
            assertJsonEquals(
                expected = json.encodeToJsonElement(case.longest),
                actual = json.encodeToJsonElement(actLongest),
                path = "$tag: longestSafe"
            )

            // Test weekOverview (4 and 12 weeks)
            val actWo4 = weekOverview(m, 4)
            assertJsonEquals(
                expected = json.encodeToJsonElement(case.weekOverview4),
                actual = json.encodeToJsonElement(actWo4),
                path = "$tag: weekOverview4"
            )
            val actWo12 = weekOverview(m, 12)
            assertJsonEquals(
                expected = json.encodeToJsonElement(case.weekOverview12),
                actual = json.encodeToJsonElement(actWo12),
                path = "$tag: weekOverview12"
            )

            // Test startDayGrid (3 and 7 days)
            val actGrid3 = startDayGrid(m, 3)
            assertJsonEquals(
                expected = json.encodeToJsonElement(case.grid3),
                actual = json.encodeToJsonElement(actGrid3),
                path = "$tag: grid3"
            )
            val actGrid7 = startDayGrid(m, 7)
            assertJsonEquals(
                expected = json.encodeToJsonElement(case.grid7),
                actual = json.encodeToJsonElement(actGrid7),
                path = "$tag: grid7"
            )
        }
    }
}
