package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StatsGoldenTest {

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
    private data class SplitGroupGoldenItem(
        val input: String,
        val output: GroupSplit? = null,
    )

    @Serializable
    private data class ComputeStatsGoldenCase(
        val description: String,
        val threshold: Double,
        val unit: String,
        val now: Long,
        val result: AttendanceStats,
    )

    @Serializable
    private data class StatsByCourseGoldenCase(
        val threshold: Double,
        val unit: String,
        val now: Long,
        val result: StatsByCourseResult,
    )

    @Serializable
    private data class StatsGoldenData(
        val splitGroupResults: List<SplitGroupGoldenItem>,
        val computeStatsCases: List<ComputeStatsGoldenCase>,
        val statsByCourseCases: List<StatsByCourseGoldenCase>,
    )

    @Serializable
    private data class SemesterFixture(
        val courses: List<CourseDto>,
        val events: List<EventDto>,
    )

    @Test
    fun testSplitGroupGolden() {
        val golden = json.decodeFromString<StatsGoldenData>(loadResource("golden/stats_golden.json"))
        for (item in golden.splitGroupResults) {
            val actual = splitGroup(item.input)
            assertEquals("splitGroup(${item.input})", item.output, actual)
        }
    }

    @Test
    fun testComputeStatsGolden() {
        val golden = json.decodeFromString<StatsGoldenData>(loadResource("golden/stats_golden.json"))
        val semester = json.decodeFromString<SemesterFixture>(loadResource("fixtures/semester.json"))
        val coursesById = semester.courses.associateBy { it.id }

        for (case in golden.computeStatsCases) {
            val eventsToTest = when {
                case.description.startsWith("algo_events") -> semester.events.filter { it.course_id == 1L }
                case.description.startsWith("sisu_events") -> semester.events.filter { it.course_id == 6L }
                else -> semester.events
            }

            val actual = computeStats(
                events = eventsToTest,
                coursesById = coursesById,
                threshold = case.threshold,
                unit = case.unit,
                now = case.now,
            )

            assertJsonEquals(
                expected = json.encodeToJsonElement(case.result),
                actual = json.encodeToJsonElement(actual),
                path = case.description
            )
        }
    }

    @Test
    fun testStatsByCourseGolden() {
        val golden = json.decodeFromString<StatsGoldenData>(loadResource("golden/stats_golden.json"))
        val semester = json.decodeFromString<SemesterFixture>(loadResource("fixtures/semester.json"))

        for (case in golden.statsByCourseCases) {
            val actual = statsByCourse(
                courses = semester.courses,
                events = semester.events,
                threshold = case.threshold,
                unit = case.unit,
                now = case.now,
            )

            assertJsonEquals(
                expected = json.encodeToJsonElement(case.result),
                actual = json.encodeToJsonElement(actual),
                path = "statsByCourse(threshold=${case.threshold}, unit=${case.unit})"
            )
        }
    }
}
