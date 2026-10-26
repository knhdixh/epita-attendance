package com.knhdixh.attendance80.logic

import com.knhdixh.attendance80.net.CalendarDto
import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.net.EventDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupsGoldenTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadResource(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(name)
            ?: throw IllegalArgumentException("Resource not found: $name")
        return stream.bufferedReader().use { it.readText() }
    }

    @Serializable
    private data class ApplyScopeItem(
        val name: String,
        val inputScope: List<Long>,
        val effectiveScope: List<Long>,
        val eventCount: Int,
        val eventIds: List<Long>,
    )

    @Serializable
    private data class GroupLabelItem(
        val name: String,
        val inputIds: List<Long>? = null,
        val label: String,
    )

    @Serializable
    private data class ScopeNoteItem(
        val name: String,
        val scope: List<Long>,
        val note: String,
    )

    @Serializable
    private data class ToggleStep(
        val action: String,
        val resultingScope: List<Long>,
    )

    @Serializable
    private data class ToggleSequenceItem(
        val sequence: List<String>,
        val steps: List<ToggleStep>,
    )

    @Serializable
    private data class GroupsGoldenData(
        val applyScopeResults: List<ApplyScopeItem>,
        val courseCalendarsResult: Map<String, List<Long>>,
        val groupLabelResults: List<GroupLabelItem>,
        val scopeNoteResults: List<ScopeNoteItem>,
        val toggleScopeResults: List<ToggleSequenceItem>,
    )

    @Serializable
    private data class GroupsFixture(
        val calendars: List<CalendarDto>,
        val courses: List<CourseDto>,
    )

    @Serializable
    private data class SemesterFixture(
        val events: List<EventDto>,
        val calendars: List<CalendarDto>,
    )

    @Test
    fun testGroupFamiliesGolden() {
        val rootObj = json.parseToJsonElement(loadResource("golden/groups_golden.json")).jsonObject
        val famArray = rootObj["groupFamiliesResult"]!!.jsonArray

        val fixture = json.decodeFromString<GroupsFixture>(loadResource("fixtures/groups.json"))
        val actFamilies = groupFamilies(fixture.courses)

        assertEquals("family count", famArray.size, actFamilies.size)
        for (i in actFamilies.indices) {
            val expTuple = famArray[i].jsonArray
            val expFamily = expTuple[0].jsonPrimitive.content
            val expCourses = json.decodeFromJsonElement<List<CourseWithGroup>>(expTuple[1])

            val act = actFamilies[i]
            assertEquals("family[$i] name", expFamily, act.family)
            assertEquals("family[$i] courses count", expCourses.size, act.courses.size)
            for (ci in expCourses.indices) {
                assertEquals("family[$i] course[$ci] id", expCourses[ci].course.id, act.courses[ci].course.id)
                assertEquals("family[$i] course[$ci] group", expCourses[ci].group, act.courses[ci].group)
            }
        }
    }

    @Test
    fun testApplyScopeGolden() {
        val golden = json.decodeFromString<GroupsGoldenData>(loadResource("golden/groups_golden.json"))
        val semester = json.decodeFromString<SemesterFixture>(loadResource("fixtures/semester.json"))

        for (item in golden.applyScopeResults) {
            val actEvents = applyScope(semester.events, semester.calendars, item.inputScope)
            assertEquals("${item.name}: eventCount", item.eventCount, actEvents.size)
            assertEquals("${item.name}: eventIds", item.eventIds, actEvents.map { it.id })

            val actSanitized = sanitizeScope(item.inputScope, semester.calendars)
            assertEquals("${item.name}: effectiveScope", item.effectiveScope, actSanitized)
        }
    }

    @Test
    fun testCourseCalendarsGolden() {
        val golden = json.decodeFromString<GroupsGoldenData>(loadResource("golden/groups_golden.json"))
        val semester = json.decodeFromString<SemesterFixture>(loadResource("fixtures/semester.json"))

        val actCals = courseCalendars(semester.events)
        for ((cidStr, expCals) in golden.courseCalendarsResult) {
            val cid = cidStr.toLong()
            val act = actCals[cid]?.toList()?.sorted() ?: emptyList()
            assertEquals("courseCalendars($cid)", expCals, act)
        }
    }

    @Test
    fun testGroupLabelGolden() {
        val golden = json.decodeFromString<GroupsGoldenData>(loadResource("golden/groups_golden.json"))
        val fixture = json.decodeFromString<GroupsFixture>(loadResource("fixtures/groups.json"))

        for (item in golden.groupLabelResults) {
            val act = groupLabel(item.inputIds, fixture.calendars)
            assertEquals("groupLabel(${item.name})", item.label, act)
        }
    }

    @Test
    fun testScopeNoteGolden() {
        val golden = json.decodeFromString<GroupsGoldenData>(loadResource("golden/groups_golden.json"))
        val semester = json.decodeFromString<SemesterFixture>(loadResource("fixtures/semester.json"))

        for (item in golden.scopeNoteResults) {
            val act = scopeNote(item.scope, semester.calendars)
            assertEquals("scopeNote(${item.name})", item.note, act)
        }
    }

    @Test
    fun testToggleScopeGolden() {
        val golden = json.decodeFromString<GroupsGoldenData>(loadResource("golden/groups_golden.json"))
        val semester = json.decodeFromString<SemesterFixture>(loadResource("fixtures/semester.json"))

        for (seqItem in golden.toggleScopeResults) {
            var currentScope = emptyList<Long>()
            for (step in seqItem.steps) {
                currentScope = toggleScope(step.action, currentScope, semester.calendars)
                assertEquals("toggleScope(${step.action}) resultingScope", step.resultingScope, currentScope)
            }
        }
    }
}
