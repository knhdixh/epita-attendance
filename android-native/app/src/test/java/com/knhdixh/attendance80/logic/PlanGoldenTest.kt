package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import com.knhdixh.attendance80.net.HomeworkDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanGoldenTest {

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
            assertJsonEquals(
                expected = json.encodeToJsonElement(case.plan),
                actual = json.encodeToJsonElement(act),
                path = "buildPlan(${case.name})"
            )
        }
    }
}
