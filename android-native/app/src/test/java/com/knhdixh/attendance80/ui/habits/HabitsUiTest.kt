package com.knhdixh.attendance80.ui.habits

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.net.HabitScheduleDto
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
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
class HabitsUiTest {
    @get:Rule val compose = createComposeRule()
    private var ui by mutableStateOf(HabitsUi())
    private val actions = FakeHabitActions()
    private val opened = mutableListOf<Long>()
    private fun show(data: AppData = hbData()) {
        compose.setContent { Attendance80Theme { HabitsScreen(data, hbNow, hbZone, ui, { ui = it }, actions, { opened += it }) } }
    }
    private fun idle(cond: () -> Boolean) = compose.waitUntil(5_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); cond() }

    @Test fun dueTodayRestAndArchived() {
        show()
        compose.onNodeWithText("Longest streak: Read, 6 days").assertExists()
        listOf(1L, 2L, 3L).forEach { compose.onNodeWithTag("habit-row-$it").assertExists() }
        compose.onNodeWithText("Not due today").assertExists()
        compose.onNodeWithTag("habits-list").performScrollToNode(hasTestTag("habit-row-4"))
        compose.onNodeWithTag("habit-row-4").assertExists()
        compose.onNodeWithTag("habits-list").performScrollToNode(hasTestTag("habits-archived"))
        compose.onNodeWithTag("habits-archived").performClick()
        compose.onNodeWithTag("habits-list").performScrollToNode(hasTestTag("habit-restore-5"))
        compose.onNodeWithTag("habit-restore-5").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("update 5", actions.calls.single())
        assertEquals(buildJsonObject { put("archived", false) }, actions.body)
        assertEquals(listOf("Restored “Old”."), actions.messages)
    }

    @Test fun checkingInCountsUpTakesOffAndCelebratesMilestones() {
        show()
        compose.onNodeWithTag("habit-check-1").assertContentDescriptionEquals("Read: not done today").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("count 1 $TODAY 1", actions.calls.removeAt(0))
        assertEquals(listOf("7 days in a row: Read. Keep going."), actions.messages)
        compose.onNodeWithTag("habit-check-2").assertContentDescriptionEquals("Water: 3 of 8 glasses today").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("count 2 $TODAY 4", actions.calls.removeAt(0))
        compose.onNodeWithTag("habit-check-2").performTouchInput { longClick() }
        idle { actions.calls.isNotEmpty() }
        assertEquals("count 2 $TODAY 2", actions.calls.removeAt(0))
        // A forgotten day in the week strip: done in one tap.
        compose.onNodeWithTag("habit-day-3-2026-11-02").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("count 3 2026-11-02 1", actions.calls.single())
    }

    @Test fun quickAddUnderstandsTheSentence() {
        show()
        compose.onNodeWithTag("habits-add").performClick()
        compose.onNodeWithTag("habits-quick-input").performTextInput("Gym mon wed fri")
        compose.onNode(hasText("Mon, Wed, Fri") and hasAnyAncestor(hasTestTag("habits-quick-chips"))).assertExists()
        compose.onNodeWithTag("habits-quick-add").performClick()
        idle { actions.calls.isNotEmpty() }
        val body = actions.body!!
        assertEquals("Gym", body["name"]!!.jsonPrimitive.content)
        assertEquals(buildJsonObject { put("type", "days"); put("days", JsonArray(listOf(1, 3, 5).map(::JsonPrimitive))) }, body["schedule"])
        assertFalse(ui.adding)
    }

    @Test fun moreOptionsOpensTheFormWithWhatWasTyped() {
        show()
        compose.onNodeWithTag("habits-add").performClick()
        compose.onNodeWithTag("habits-quick-input").performTextInput("Read 3x a week")
        compose.onNodeWithTag("habits-quick-more").performClick()
        compose.onNodeWithTag("habit-form-name").assertTextContains("Read")
        compose.onNodeWithTag("habit-form-type-weekly").assertIsSelected()
        compose.onNodeWithTag("habit-form-n").assertTextContains("3")
        compose.onNodeWithTag("habit-form-type-days").performClick()
        compose.onNodeWithTag("habits-list").performScrollToNode(hasTestTag("habit-form-save"))
        compose.onNodeWithTag("habit-form-save").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals(buildJsonObject { put("type", "days"); put("days", JsonArray(listOf(1, 2, 3, 4, 5).map(::JsonPrimitive))) }, actions.body!!["schedule"])
        assertEquals(JsonNull, actions.body!!["remind_at"])
    }

    @Test fun editReorderArchiveAndDeleteFromTheForm() {
        show()
        compose.onNodeWithContentDescription("Edit Water").performClick()
        compose.onNodeWithTag("habits-list").performScrollToNode(hasTestTag("habit-form-up"))
        compose.onNodeWithTag("habit-form-up").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("order [2, 1, 3, 4]", actions.calls.removeAt(0))
        compose.onNodeWithTag("habits-list").performScrollToNode(hasTestTag("habit-form-delete"))
        compose.onNodeWithTag("habit-form-delete").performClick()
        compose.onNodeWithText("Delete “Water” and all its check-ins? Archiving keeps the history instead.").assertExists()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("delete 2", actions.calls.single())
        assertEquals(listOf("Deleted “Water”."), actions.messages)
    }

    @Test fun gridViewAndEmptyState() {
        show()
        compose.onNodeWithTag("habits-view-grid").performClick()
        compose.onNodeWithTag("habit-card-1").assertExists()
        compose.onNodeWithTag("habit-cells-1").assertExists()
        compose.onNodeWithTag("habit-card-1").performTouchInput { } // rendered
    }

    @Test fun emptySaysHowToStart() {
        show(hbData().copy(habits = com.knhdixh.attendance80.net.HabitsDto()))
        compose.onNodeWithText("Build habits that stick. Add one, tick it off each day, and watch the grid fill in.").assertExists()
        compose.onNodeWithText("Try “Gym mon wed fri”, “Read 20 pages 3x a week” or “💧 Water 8 glasses a day”.").assertExists()
    }

    @Test fun habitPageStatsHeatmapAndActions() {
        var back = false
        compose.setContent { Attendance80Theme { HabitDetail(hbData(), 2, hbNow, hbZone, actions) { back = true } } }
        compose.onNodeWithText("3/8 glasses").assertExists()
        compose.onNodeWithText("Due today").assertExists()
        compose.onNodeWithTag("habit-minus").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("count 2 $TODAY 2", actions.calls.removeAt(0))
        compose.onNodeWithTag("habit-detail").performScrollToNode(hasText("By weekday"))
        compose.onNodeWithText("Days done per month").assertExists()
        compose.onNodeWithTag("habit-detail").performScrollToNode(hasTestTag("habit-delete"))
        compose.onNodeWithTag("habit-delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        idle { back }
        assertEquals("delete 2", actions.calls.single())
    }

    @Test fun habitPageEditsInPlace() {
        compose.setContent { Attendance80Theme { HabitDetail(hbData(), 1, hbNow, hbZone, actions) {} } }
        compose.onNodeWithTag("habit-detail").performScrollToNode(hasTestTag("habit-edit"))
        compose.onNodeWithTag("habit-edit").performClick()
        compose.onNodeWithTag("habit-form-name").performTextReplacement("Read more")
        compose.onNodeWithTag("habit-detail").performScrollToNode(hasTestTag("habit-form-save"))
        compose.onNodeWithTag("habit-form-save").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("update 1", actions.calls.single())
        assertEquals("Read more", actions.body!!["name"]!!.jsonPrimitive.content)
        assertEquals("21:00", actions.body!!["remind_at"]!!.jsonPrimitive.content)
        assertEquals(listOf("Habit saved."), actions.messages)
    }

    @Test fun shellRoutesOpenTheAddRowAndTheHabitPage() {
        compose.setContent { Attendance80Theme { Shell(ShellState(data = hbData(), session = "a"), {}, {}, {}, initialRoute = "habits/new", now = hbNow, zone = hbZone) } }
        compose.onNodeWithTag("habits-quick-input").assertExists()
        compose.onNodeWithTag("shell-title").assertTextEquals("New habit")
    }
}

class HabitsModelTest {
    @Test fun countsAndBodiesMatchTheWeb() {
        assertEquals(1, nextCount(0, 1)); assertEquals(0, nextCount(1, 1))
        assertEquals(4, nextCount(3, 8)); assertEquals(0, nextCount(8, 8)); assertEquals(2, nextCount(3, 8, back = true)); assertEquals(0, nextCount(0, 8, back = true))
        assertEquals(8, toggledCount(3, 8)); assertEquals(0, toggledCount(8, 8)); assertEquals(1, toggledCount(0, 0))
        assertEquals(mapOf(TODAY to 3), countsOf(hbData().habits)[2L])
        assertEquals("""{"name":"Read","icon":"","color":"blue","schedule":{"type":"weekly","n":3},"target":1,"unit":"pages","remind_at":"20:00"}""",
            habitBody(" Read ", "", "blue", HabitScheduleDto("weekly", n = 3), 1, "pages ", "20:00").toString())
        val s = statsOf(hbData().habits.list[0], countsOf(hbData().habits)[1L].orEmpty(), TODAY, hbZone)
        assertEquals(6, s.streak); assertTrue(s.due); assertFalse(s.todayDone)
        assertFalse(statsOf(hbData().habits.list[3], emptyMap(), TODAY, hbZone).due)
    }
}
