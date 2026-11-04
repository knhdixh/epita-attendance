package com.knhdixh.attendance80.ui.tasks

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.HomeworkDto
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class TaskRowUiTest {
    @get:Rule val ui = createComposeRule()

    private fun show(d: com.knhdixh.attendance80.net.AppData, toggled: MutableList<Pair<Long, Boolean>> = mutableListOf(), scheduled: MutableList<Long> = mutableListOf(),
                     moves: MutableList<kotlinx.serialization.json.JsonObject> = mutableListOf()) {
        val env = TaskEnv(d, TODAY, taskZone, onToggle = { h: HomeworkDto, done -> toggled += h.id to done }, onSchedule = { scheduled += it.id })
        ui.setContent { Attendance80Theme { TaskLazyList(TaskModel.flatten(d, TaskModel.inbox(d)), env, onMove = { moves += it }) } }
    }

    @Test fun theRingCompletesAndSaysItsPriority() {
        val toggled = mutableListOf<Pair<Long, Boolean>>()
        show(taskData(task(1, "Write report", priority = 4)), toggled)
        ui.onNodeWithContentDescription("Done: Write report, p1").performClick()
        assertEquals(listOf(1L to true), toggled)
    }

    @Test fun swipeRightCompletesAndSwipeLeftSchedules() {
        val toggled = mutableListOf<Pair<Long, Boolean>>(); val scheduled = mutableListOf<Long>()
        show(taskData(task(1, "A"), task(2, "B")), toggled, scheduled)
        ui.onNodeWithTag("task-swipe-1").performTouchInput { swipeRight() }
        ui.waitForIdle()
        assertEquals(listOf(1L to true), toggled)
        ui.onNodeWithTag("task-swipe-2").performTouchInput { swipeLeft() }
        ui.waitForIdle()
        assertEquals(listOf(2L), scheduled)
    }

    @Test fun longPressAndDragDownMovesATaskBelowTheNextOne() {
        val moves = mutableListOf<kotlinx.serialization.json.JsonObject>()
        show(taskData(task(1, "First", position = 0), task(2, "Second", position = 1), task(3, "Third", position = 2)), moves = moves)
        ui.onNodeWithTag("task-row-1").performTouchInput { down(center); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(0f, 230f), 300); up() }
        ui.waitForIdle()
        assertEquals(1, moves.size)
        assertEquals(listOf(1L), moves[0]["ids"]!!.let { (it as kotlinx.serialization.json.JsonArray).map { e -> (e as kotlinx.serialization.json.JsonPrimitive).content.toLong() } })
        assertEquals(2, (moves[0]["position"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    @Test fun dragRightNestsUnderTheRowAbove() {
        val moves = mutableListOf<kotlinx.serialization.json.JsonObject>()
        show(taskData(task(1, "First", position = 0), task(2, "Second", position = 1)), moves = moves)
        ui.onNodeWithTag("task-row-2").performTouchInput { down(center); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(200f, 0f), 300); up() }
        ui.waitForIdle()
        assertEquals(1, moves.size)
        assertEquals("1", (moves[0]["parent_id"] as kotlinx.serialization.json.JsonPrimitive).content)
    }
}
