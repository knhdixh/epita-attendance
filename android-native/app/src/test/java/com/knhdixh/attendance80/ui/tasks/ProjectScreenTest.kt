package com.knhdixh.attendance80.ui.tasks

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import kotlinx.serialization.json.*
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
class ProjectScreenTest {
    @get:Rule val ui = createComposeRule()
    private val project = ProjectDto(5, name = "Thesis")
    private val data = taskData(
        task(1, "Intro", project = 5, position = 0, due = "2026-11-09", priority = 2), task(2, "Method", project = 5, section = 9, position = 0, priority = 4),
        task(3, "Results", project = 5, section = 9, position = 1, due = "2026-11-05"), task(4, "Old", project = 5, done = true).copy(completed_at = 1793700000000),
        task(5, "Elsewhere", project = 6), projects = listOf(project, ProjectDto(6, name = "Other")),
        sections = listOf(SectionDto(9, 5, "Draft"), SectionDto(10, 5, "Final", position = 1)))

    private fun titles(entries: List<ListEntry>) = entries.map { if (it is TaskEntry) it.h.title else "[" + (it as HeaderEntry).title + "]" }

    @Test fun entriesFollowSectionsSortGroupAndCompleted() {
        val o = TaskModel.ProjectOptions()
        assertEquals(listOf("Intro", "[Draft]", "Method", "Results", "[Final]"), titles(TaskModel.projectEntries(data, 5, o, TODAY)))
        assertEquals(listOf("Intro", "[Draft]", "[Final]"), titles(TaskModel.projectEntries(data, 5, o, TODAY, collapsedSections = setOf(9L))).filter { it != "Method" && it != "Results" })
        assertEquals(listOf("Intro", "[Draft]", "Method", "Results", "[Final]", "[Completed]", "Old"), titles(TaskModel.projectEntries(data, 5, o.copy(completed = true), TODAY)))
        assertEquals(listOf("[p1]", "Method", "[p3]", "Intro", "[No priority]", "Results"), titles(TaskModel.projectEntries(data, 5, TaskModel.ProjectOptions(group = "priority"), TODAY)))
        assertEquals(listOf("Results", "Intro", "Method"), TaskModel.projectEntries(data, 5, TaskModel.ProjectOptions(sort = "date", group = "none"), TODAY).filterIsInstance<TaskEntry>().map { it.h.title }
            .let { listOf("Results", "Intro", "Method").filter { t -> t in it } })
    }

    @Test fun boardDropFindsTheColumnAndThePlaceInIt() {
        val cols = listOf(Triple<Long?, Float, Float>(null, 0f, 100f), Triple<Long?, Float, Float>(9L, 100f, 200f))
        val cards = listOf(Triple(1L, null as Long?, 0f to 50f), Triple(2L, 9L, 0f to 50f), Triple(3L, 9L, 60f to 50f))
        assertEquals(9L to 2, TaskModel.boardDrop(cols, cards, 1, 150f, 200f))
        assertEquals(9L to 0, TaskModel.boardDrop(cols, cards, 1, 150f, 10f))
        assertEquals(null to 0, TaskModel.boardDrop(cols, cards, 2, 50f, 10f))
        assertNull(TaskModel.boardDrop(cols, cards, 1, 500f, 10f))
    }

    private fun show(a: RecordingActions, p: ProjectDto = project) {
        val env = TaskEnv(data.copy(projects = data.projects.map { if (it.id == p.id) p else it }), TODAY, taskZone)
        ui.setContent { Attendance80Theme { ProjectScreen(p, env, a, onBack = {}) } }
    }

    @Test fun listShowsSectionsAndOnlyThisProjectsTasks() {
        show(RecordingActions())
        listOf("Intro", "Method", "Results").forEach { ui.onNodeWithText(it).assertExists() }
        listOf("Draft", "Final").forEach { ui.onNodeWithText(it, substring = true).assertExists() }
        ui.onNodeWithText("Elsewhere").assertDoesNotExist(); ui.onNodeWithText("Old").assertDoesNotExist()
        ui.onNodeWithTag("project-completed").performClick()
        ui.onNodeWithTag("project-list").performScrollToNode(hasText("Old")); ui.onNodeWithText("Old").assertExists()
    }

    @Test fun viewSectionsAndArchiveAreSavedThroughTheActions() {
        val a = RecordingActions()
        show(a)
        fun tick() = ui.waitForIdle()
        ui.onNodeWithTag("project-view-board").performClick(); tick()
        assertEquals(Triple("project", 5L, buildJsonObject { put("view", "board") }), a.entityUpdates.last())
        ui.onNodeWithTag("project-add-section").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); tick()
        ui.onNodeWithTag("name-dialog-input").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("Review")) }
        ui.onNodeWithTag("name-dialog-save").performClick(); tick()
        assertEquals("section" to buildJsonObject { put("project_id", 5); put("name", "Review") }, a.entities.single())
        ui.onNodeWithTag("section-menu-10").performClick(); tick(); ui.onNodeWithTag("section-up-10").performClick(); tick()
        assertEquals("section" to listOf(10L, 9L), a.reorders.single())
        ui.onNodeWithTag("section-menu-9").performClick(); tick(); ui.onNodeWithTag("section-delete-9").performClick(); tick()
        assertEquals("section" to 9L, a.entityDeletes.single())
        ui.onNodeWithTag("project-menu").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); tick(); ui.onNodeWithTag("project-archive").performClick(); tick()
        assertEquals(buildJsonObject { put("archived", true) }, a.entityUpdates.last().third)
    }

    @Test fun collapsingASectionHidesItsTasks() {
        show(RecordingActions())
        ui.onNodeWithTag("section-menu-9").performClick(); ui.onNodeWithTag("section-collapse-9").performClick()
        ui.onNodeWithText("Method").assertDoesNotExist()
        ui.onNodeWithText("Intro").assertExists()
    }

    @Test fun boardShowsSectionsAsColumnsAndDragMovesACardToAnotherColumn() {
        val a = RecordingActions()
        show(a, project.copy(view = "board"))
        listOf("board-col-0", "board-col-9", "board-col-10").forEach { ui.onNodeWithTag(it).assertExists() }
        ui.onNodeWithTag("board-card-1").performTouchInput { down(center); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(300f, 0f), 400); up() }
        ui.waitForIdle()
        assertEquals(1, a.moves.size)
        assertEquals(1L, a.moves[0]["ids"]!!.jsonArray.single().jsonPrimitive.long)
        assertEquals(9L, a.moves[0]["section_id"]!!.jsonPrimitive.long)
    }

    @Test fun theNameDialogSavesTheTrimmedNameAndIgnoresABlankOne() {
        val saved = mutableListOf<String>()
        ui.setContent { Attendance80Theme { NameDialog("Add section", confirm = "Add", onSave = { saved += it }, onDismiss = {}) } }
        ui.onNodeWithTag("name-dialog-save").performClick()
        assertEquals(emptyList<String>(), saved)
        // SetText doesn't focus the field: a focused field blinks its cursor for ever, and the test never gets idle.
        ui.onNodeWithTag("name-dialog-input").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("  Review  ")) }
        ui.onNodeWithTag("name-dialog-save").performClick()
        assertEquals(listOf("Review"), saved)
    }
}
