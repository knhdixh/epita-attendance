package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StatsGoldenTest {

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

            val exp = case.result
            val desc = case.description
            assertClose(exp.total, actual.total, "$desc: total")
            assertClose(exp.present, actual.present, "$desc: present")
            assertClose(exp.absent, actual.absent, "$desc: absent")
            assertClose(exp.plannedAbsent, actual.plannedAbsent, "$desc: plannedAbsent")
            assertClose(exp.excused, actual.excused, "$desc: excused")
            assertClose(exp.optional, actual.optional, "$desc: optional")
            assertClose(exp.pastMarked, actual.pastMarked, "$desc: pastMarked")
            assertClose(exp.pastPresent, actual.pastPresent, "$desc: pastPresent")
            assertEquals("$desc: unmarkedPast", exp.unmarkedPast, actual.unmarkedPast)
            assertEquals("$desc: upcoming", exp.upcoming, actual.upcoming)
            assertEquals("$desc: sessions", exp.sessions, actual.sessions)
            assertEquals("$desc: missedMustAttend count", exp.missedMustAttend.size, actual.missedMustAttend.size)
            assertClose(exp.maxWeight, actual.maxWeight, "$desc: maxWeight")
            assertClose(exp.allowedAbsence, actual.allowedAbsence, "$desc: allowedAbsence")
            assertClose(exp.remaining, actual.remaining, "$desc: remaining")
            assertClose(exp.remainingDisplay, actual.remainingDisplay, "$desc: remainingDisplay")
            assertClose(exp.projectedRate, actual.projectedRate, "$desc: projectedRate")
            assertClose(exp.currentRate, actual.currentRate, "$desc: currentRate")
            assertEquals("$desc: level", exp.level, actual.level)
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

            // Compare overall
            assertClose(case.result.overall.total, actual.overall.total, "overall: total")
            assertClose(case.result.overall.remaining, actual.overall.remaining, "overall: remaining")
            assertEquals("overall: level", case.result.overall.level, actual.overall.level)

            // Compare perCourse
            assertEquals("perCourse size", case.result.perCourse.size, actual.perCourse.size)
            for (i in case.result.perCourse.indices) {
                val exp = case.result.perCourse[i]
                val act = actual.perCourse[i]
                assertEquals("course id at $i", exp.course.id, act.course.id)
                assertClose(exp.stats.total, act.stats.total, "course ${exp.course.name}: total")
                assertClose(exp.stats.remaining, act.stats.remaining, "course ${exp.course.name}: remaining")
                assertEquals("course ${exp.course.name}: level", exp.stats.level, act.stats.level)
            }
        }
    }
}
