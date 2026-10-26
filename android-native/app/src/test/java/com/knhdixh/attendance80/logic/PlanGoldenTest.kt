package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import com.knhdixh.attendance80.net.HomeworkDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlanGoldenTest {

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
    private data class LocalMidnightItem(
        val name: String,
        val tz: String,
        val now: Long,
        val midnight: Long,
    )

    @Serializable
    private data class EffortOfItem(
        val id: Long,
        val title: String,
        val effort: Int,
    )

    @Serializable
    private data class BuildPlanCase(
        val name: String,
        val now: Long,
        val tz: String,
        val plan: DayPlan,
    )

    @Serializable
    private data class PlanGoldenData(
        val localMidnightResults: List<LocalMidnightItem>,
        val effortOfResults: List<EffortOfItem>,
        val buildPlanCases: List<BuildPlanCase>,
    )

    @Serializable
    private data class SemesterFixture(
        val courses: List<CourseDto>,
        val events: List<EventDto>,
        val homework: List<HomeworkDto>,
    )

    @Test
    fun testLocalMidnightGolden() {
        val golden = json.decodeFromString<PlanGoldenData>(loadResource("golden/plan_golden.json"))
        for (item in golden.localMidnightResults) {
            val act = localMidnight(item.now, item.tz)
            assertEquals("localMidnight(${item.name}, ${item.tz})", item.midnight, act)
        }
    }

    @Test
    fun testEffortOfGolden() {
        val golden = json.decodeFromString<PlanGoldenData>(loadResource("golden/plan_golden.json"))
        val semester = json.decodeFromString<SemesterFixture>(loadResource("fixtures/semester.json"))
        val hwById = semester.homework.associateBy { it.id }

        for (item in golden.effortOfResults) {
            val hw = hwById[item.id]!!
            val act = effortOf(hw)
            assertEquals("effortOf(${item.title})", item.effort, act)
        }
    }

    @Test
    fun testBuildPlanGolden() {
        val golden = json.decodeFromString<PlanGoldenData>(loadResource("golden/plan_golden.json"))
        val semester = json.decodeFromString<SemesterFixture>(loadResource("fixtures/semester.json"))

        for (case in golden.buildPlanCases) {
            val act = buildPlan(
                courses = semester.courses,
                events = semester.events,
                homework = semester.homework,
                now = case.now,
                tz = case.tz,
            )
            val exp = case.plan
            val tag = case.name

            assertEquals("$tag: now", exp.now, act.now)
            assertEquals("$tag: tz", exp.tz, act.tz)
            assertEquals("$tag: today", exp.today, act.today)
            assertEquals("$tag: current", exp.current, act.current)
            assertEquals("$tag: next", exp.next, act.next)
            assertEquals("$tag: todaySessions", exp.todaySessions, act.todaySessions)
            assertEquals("$tag: tomorrowFirst", exp.tomorrowFirst, act.tomorrowFirst)
            assertEquals("$tag: later", exp.later, act.later)

            // Gaps
            assertEquals("$tag: gaps count", exp.gaps.size, act.gaps.size)
            for (i in exp.gaps.indices) {
                val eg = exp.gaps[i]
                val ag = act.gaps[i]
                assertEquals("$tag: gap[$i] start", eg.start, ag.start)
                assertEquals("$tag: gap[$i] end", eg.end, ag.end)
                assertEquals("$tag: gap[$i] minutes", eg.minutes, ag.minutes)
                assertEquals("$tag: gap[$i] left", eg.left, ag.left)
                assertEquals("$tag: gap[$i] fits count", eg.fits.size, ag.fits.size)
                for (fi in eg.fits.indices) {
                    val ef = eg.fits[fi]
                    val af = ag.fits[fi]
                    assertEquals("$tag: gap[$i] fit[$fi] id", ef.id, af.id)
                    assertEquals("$tag: gap[$i] fit[$fi] minutes", ef.minutes, af.minutes)
                    assertEquals("$tag: gap[$i] fit[$fi] partial", ef.partial, af.partial)
                }
            }

            // Todo
            assertEquals("$tag: todo count", exp.todo.size, act.todo.size)
            for (i in exp.todo.indices) {
                val et = exp.todo[i]
                val at = act.todo[i]
                assertEquals("$tag: todo[$i] id", et.id, at.id)
                assertEquals("$tag: todo[$i] effort", et.effort, at.effort)
                assertEquals("$tag: todo[$i] latestStart", et.latestStart, at.latestStart)
                assertEquals("$tag: todo[$i] urgency", et.urgency, at.urgency)
                assertEquals("$tag: todo[$i] beforeSession", et.beforeSession, at.beforeSession)
            }

            // Week load
            assertClose(exp.week.classHours, act.week.classHours, "$tag: week classHours")
            assertClose(exp.week.workHours, act.week.workHours, "$tag: week workHours")
            assertClose(exp.week.freeHours, act.week.freeHours, "$tag: week freeHours")
            assertClose(exp.week.load, act.week.load, "$tag: week load")
            assertEquals("$tag: week clashes", exp.week.clashes, act.week.clashes)
        }
    }
}
