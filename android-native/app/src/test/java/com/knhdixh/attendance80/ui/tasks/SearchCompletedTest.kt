package com.knhdixh.attendance80.ui.tasks

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SearchCompletedTest {
    @get:Rule val ui = createComposeRule()
    private val data = taskData(
        task(1, "Book the dentist").copy(comment_count = 1, notes = "bring the card"), task(2, "Dentist form", done = true).copy(completed_at = taskNow - 3_600_000),
        task(3, "Water plants", recurrence = "every day"), task(4, "Walk", done = true).copy(completed_at = taskNow - 86_400_000 * 2),
        projects = listOf(ProjectDto(5, name = "Dentist plan")), labels = listOf(LabelDto(7, "dentist-visit")),
    ).let { d -> d.copy(courses = listOf(CourseDto(9, "Dentistry 101")), events = emptyList()) }

    @Test fun searchFindsTasksCommentsProjectsLabelsAndCourses() {
        val comments = mapOf(1L to listOf(CommentDto(30, 1, "Ask about the dentist's hours")))
        val hits = SearchModel.run(data, "dentist", comments, taskZone)
        assertEquals(listOf("task", "task", "comment", "project", "label", "course"), hits.map { it.kind })
        assertEquals(listOf("Book the dentist", "Dentist form"), hits.filter { it.kind == "task" }.map { it.title })   // open before done
        assertEquals("On Book the dentist", hits.first { it.kind == "comment" }.detail)
        assertEquals(listOf("task"), SearchModel.run(data, "bring the card", emptyMap(), taskZone).map { it.kind })    // notes too
        assertTrue(SearchModel.run(data, "  ", emptyMap(), taskZone).isEmpty())
        assertTrue(SearchModel.run(data, "zzz", emptyMap(), taskZone).isEmpty())
    }

    @Test fun theTabListsHitsFetchesCommentsAndShowsRecentSearches() {
        val opened = mutableListOf<Long>(); val routes = mutableListOf<String>(); val searched = mutableListOf<String>()
        val a = object : TaskActions by NoTaskActions { override suspend fun comments(taskId: Long) = listOf(CommentDto(30, taskId, "Ask about the dentist's hours")) }
        val env = TaskEnv(data, TODAY, taskZone, onOpen = { opened += it })
        ui.setContent { Attendance80Theme { SearchScreen(env, a, listOf("old query"), { searched += it }, { routes += it }, autoFocus = false) } }
        ui.onNodeWithTag("search-recent").assertTextEquals("old query")
        ui.onNodeWithTag("search-tab-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("dentist")) }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("search-hit-c30:1").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("search-hit-t1").performClick()
        assertEquals(listOf(1L), opened); assertEquals(listOf("dentist"), searched)
        ui.onNodeWithTag("search-hit-p5").performClick()
        assertEquals(listOf("project/5"), routes)
        ui.onNodeWithTag("search-tab-input").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("zzz")) }
        ui.onNodeWithTag("search-tab-none").assertExists()
    }

    @Test fun completedGroupsByDayAndCountsRepeatOccurrencesOnce() {
        val completions = listOf(CompletionDto(1, 3, taskNow - 7_200_000), CompletionDto(2, 3, taskNow - 86_400_000 * 2 - 1_000), CompletionDto(3, 2, taskNow - 3_600_000))
        val days = CompletedModel.byDay(data, completions, taskZone)
        assertEquals(listOf("2026-11-04", "2026-11-02"), days.map { it.day })
        assertEquals(listOf("Dentist form", "Water plants"), days[0].items.map { it.title })    // the done task isn't listed twice
        assertEquals(listOf("Walk", "Water plants"), days[1].items.map { it.title }.sorted())
        assertTrue(days[0].items.first { it.title == "Water plants" }.repeating)
    }

    @Test fun completedScreenLoadsTheHistoryAndOpensATask() {
        val opened = mutableListOf<Long>()
        val a = object : TaskActions by NoTaskActions { override suspend fun completions(since: Long) = listOf(CompletionDto(1, 3, taskNow - 7_200_000)) }
        ui.setContent { Attendance80Theme { CompletedScreen(TaskEnv(data, TODAY, taskZone, onOpen = { opened += it }), a, taskNow) } }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("completed-c1").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("completed-day-2026-11-04").assertTextContains("Today", substring = true)
        ui.onNodeWithTag("completed-c1").performClick()
        assertEquals(listOf(3L), opened)
    }
}
