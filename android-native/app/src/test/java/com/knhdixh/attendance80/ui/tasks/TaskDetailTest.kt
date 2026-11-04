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
class TaskDetailTest {
    @get:Rule val ui = createComposeRule()
    private val data = taskData(
        task(1, "Write report", due = "2026-11-05").copy(reminders = listOf(TaskReminderDto(40, before_minutes = 30)), created_at = 1793700000000),
        task(2, "Outline", parent = 1, position = 0), task(3, "Draft", parent = 1, position = 1),
        labels = listOf(LabelDto(7, "deep work")), projects = listOf(ProjectDto(5, name = "Home")))

    private fun show(actions: RecordingActions, opened: MutableList<String> = mutableListOf()) {
        val env = TaskEnv(data, TODAY, taskZone)
        ui.setContent { Attendance80Theme { TaskDetailSheet(1, env, actions, onFocus = { opened += "focus$it" }, onPlan = { opened += "plan${it.id}" }, onDelete = {}, onOpen = {}, onDismiss = {}) } }
        ui.waitForIdle()
    }
    private fun scrollTo(tag: String) { ui.onNodeWithTag("task-detail").performScrollToNode(hasTestTag(tag)) }

    @Test fun showsTheTaskWithItsFieldsSubtasksAndDates() {
        show(RecordingActions())
        ui.onNodeWithTag("detail-title").assert(hasText("Write report"))
        ui.onNodeWithTag("detail-date").assertTextContains("Tomorrow")
        ui.onNodeWithTag("detail-project").assertTextContains("Inbox")
        scrollTo("task-row-3"); ui.onNodeWithTag("task-row-2").assertExists(); ui.onNodeWithTag("task-row-3").assertExists()
        scrollTo("detail-dates"); ui.onNodeWithTag("detail-dates").assertTextContains("Created", substring = true)
    }

    @Test fun priorityLabelsAndRemindersAreSavedAsTheyAreChanged() {
        val a = RecordingActions()
        show(a)
        scrollTo("detail-priority-p1"); ui.onNodeWithTag("detail-priority-p1").performClick()
        assertEquals(4, a.updates.last().second["priority"]!!.jsonPrimitive.int)
        scrollTo("detail-label-7"); ui.onNodeWithTag("detail-label-7").performClick()
        assertEquals(listOf(7L), (a.updates.last().second["label_ids"] as JsonArray).map { it.jsonPrimitive.long })
        scrollTo("detail-add-reminder-60"); ui.onNodeWithTag("detail-add-reminder-60").performClick()
        assertEquals(60, a.reminders.single().second["before_minutes"]!!.jsonPrimitive.int)
        ui.onNodeWithTag("detail-add-reminder-30").assertDoesNotExist()   // already there
        scrollTo("detail-reminder-delete-40"); ui.onNodeWithTag("detail-reminder-delete-40").performClick()
        assertEquals(listOf(40L), a.reminderDeletes)
    }

    @Test fun subtasksAreAddedAndReordered() {
        val a = RecordingActions()
        show(a)
        scrollTo("detail-add-subtask"); ui.onNodeWithTag("detail-add-subtask").performTextInput("Proofread")
        ui.onNodeWithTag("detail-add-subtask-go").performClick()
        assertEquals(1L, a.created.single()["parent_id"]!!.jsonPrimitive.long)
        assertEquals("Proofread", a.created.single()["title"]!!.jsonPrimitive.content)
        scrollTo("subtask-up-3"); ui.onNodeWithTag("subtask-up-3").performClick()
        assertEquals(3L, a.moves.single()["ids"]!!.jsonArray.single().jsonPrimitive.long)
        assertEquals(0, a.moves.single()["position"]!!.jsonPrimitive.int)
        ui.onNodeWithTag("subtask-up-2").assertIsNotEnabled()
    }

    @Test fun commentsAreListedAddedAndFocusAndPlanAreOffered() {
        val a = RecordingActions().apply { existingComments = listOf(CommentDto(1, 1, "Ask **Sam**")) }
        val opened = mutableListOf<String>()
        show(a, opened)
        scrollTo("comment-1"); ui.onNodeWithTag("comment-1").assertTextContains("Ask Sam")
        ui.onNodeWithTag("detail-add-comment").performTextInput("Done by Friday")
        ui.onNodeWithTag("detail-add-comment-go").performClick()
        assertEquals(1L to "Done by Friday", a.commentsAdded.single())
        scrollTo("detail-focus"); ui.onNodeWithTag("detail-focus").performClick()
        ui.onNodeWithTag("detail-plan").performClick()
        assertEquals(listOf("focus1", "plan1"), opened)
    }
}
