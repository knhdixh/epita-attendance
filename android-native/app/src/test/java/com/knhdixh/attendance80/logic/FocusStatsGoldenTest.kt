package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.FocusSessionDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.math.abs

class FocusStatsGoldenTest {

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

    private fun assertClose(expected: Double?, actual: Double?, msg: String = "") {
        if (expected == null && actual == null) return
        if (expected == null || actual == null) {
            throw AssertionError("Mismatch: expected=$expected, actual=$actual. $msg")
        }
        assertTrue("Expected $expected but got $actual (diff ${abs(expected - actual)} > 1e-9). $msg", abs(expected - actual) <= 1e-9)
    }

    @Serializable
    private data class FocusStatsRun(
        val zone: String,
        val dailyTotals: Map<String, DayTotal>,
        val minutesByHomework: Map<String, Double>,
        val focusStats7: FocusStatsResult,
        val focusStats30: FocusStatsResult,
        val focusStats365: FocusStatsResult,
    )

    @Serializable
    private data class FocusStatsGoldenData(
        val zone: String = "Europe/Paris",
        val dailyTotals: Map<String, DayTotal>,
        val minutesByHomework: Map<String, Double>,
        val focusStats7: FocusStatsResult,
        val focusStats30: FocusStatsResult,
        val focusStats365: FocusStatsResult,
        val focusSummaryParis: FocusSummaryResult,
        val focusSummaryHelsinki: FocusSummaryResult,
        val runs: List<FocusStatsRun> = emptyList(),
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

        val runs = golden.runs.ifEmpty {
            listOf(
                FocusStatsRun(
                    zone = golden.zone,
                    dailyTotals = golden.dailyTotals,
                    minutesByHomework = golden.minutesByHomework,
                    focusStats7 = golden.focusStats7,
                    focusStats30 = golden.focusStats30,
                    focusStats365 = golden.focusStats365,
                )
            )
        }

        for (run in runs) {
            val zoneId = ZoneId.of(run.zone)
            val actTotals = dailyTotals(fixture.yearSessions, zoneId)
            assertJsonEquals(
                expected = json.encodeToJsonElement(run.dailyTotals),
                actual = json.encodeToJsonElement(actTotals),
                path = "dailyTotals[${run.zone}]"
            )

            val actHw = minutesByHomework(fixture.yearSessions).mapKeys { it.key.toString() }
            assertJsonEquals(
                expected = json.encodeToJsonElement(run.minutesByHomework),
                actual = json.encodeToJsonElement(actHw),
                path = "minutesByHomework[${run.zone}]"
            )
        }
    }

    @Test
    fun testFocusStatsGolden() {
        val golden = json.decodeFromString<FocusStatsGoldenData>(loadResource("golden/focus_stats_golden.json"))
        val fixture = json.decodeFromString<FocusFixture>(loadResource("fixtures/focus.json"))

        val runs = golden.runs.ifEmpty {
            listOf(
                FocusStatsRun(
                    zone = golden.zone,
                    dailyTotals = golden.dailyTotals,
                    minutesByHomework = golden.minutesByHomework,
                    focusStats7 = golden.focusStats7,
                    focusStats30 = golden.focusStats30,
                    focusStats365 = golden.focusStats365,
                )
            )
        }

        for (run in runs) {
            val zoneId = ZoneId.of(run.zone)
            for ((range, exp) in listOf(7 to run.focusStats7, 30 to run.focusStats30, 365 to run.focusStats365)) {
                val act = focusStats(fixture.yearSessions, now = fixture.refNow, range = range, zoneId = zoneId)
                assertJsonEquals(
                    expected = json.encodeToJsonElement(exp),
                    actual = json.encodeToJsonElement(act),
                    path = "focusStats(zone=${run.zone}, range=$range)"
                )
            }
        }
    }

    @Test
    fun testFocusSummaryGolden() {
        val golden = json.decodeFromString<FocusStatsGoldenData>(loadResource("golden/focus_stats_golden.json"))
        val fixture = json.decodeFromString<FocusFixture>(loadResource("fixtures/focus.json"))

        for ((tz, exp) in listOf("Europe/Paris" to golden.focusSummaryParis, "Europe/Helsinki" to golden.focusSummaryHelsinki)) {
            val act = focusSummary(fixture.yearSessions, now = fixture.refNow, tz = tz)
            assertJsonEquals(
                expected = json.encodeToJsonElement(exp),
                actual = json.encodeToJsonElement(act),
                path = "focusSummary(tz=$tz)"
            )
        }
    }

    @Test
    fun testDayGolden() {
        val golden = json.decodeFromString<FocusStatsGoldenData>(loadResource("golden/focus_stats_golden.json"))
        val fixture = json.decodeFromString<FocusFixture>(loadResource("fixtures/focus.json"))
        val zoneId = ZoneId.of(golden.zone)
        val totals = dailyTotals(fixture.yearSessions, zoneId)

        val testTimestamps = listOf(
            fixture.refNow,
            fixture.refNow - 86400000L,
            fixture.yearSessions.first().started_at,
            fixture.yearSessions.last().started_at,
            fixture.refNow + 30 * 86400000L
        )

        for (ts in testTimestamps) {
            val expected = totals[dayKey(ts, zoneId)] ?: DayTotal(0.0, 0)
            val actual = day(ts, fixture.yearSessions, zoneId)
            assertEquals("day($ts) minutes", expected.minutes, actual.minutes, 1e-9)
            assertEquals("day($ts) count", expected.count, actual.count)
        }
    }
}
