package com.knhdixh.attendance80.ui.breaks

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.knhdixh.attendance80.net.BreakDto
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class BreaksUiTest {
    @get:Rule val ui = createComposeRule()
    private fun waitList() = ui.waitUntil(10_000) { ui.onAllNodesWithTag("breaks-list").fetchSemanticsNodes().isNotEmpty() }

    @Test fun emptyCalendarSaysNoSafeBreak() {
        ui.setContent { Attendance80Theme { Shell(ShellState(data = breaksEmpty()), {}, {}, {}, initialRoute = "breaks", now = breaksNow, zone = breaksZone) } }
        waitList()
        ui.onNodeWithTag("breaks-list").performScrollToNode(hasText("No safe 7-day break left in your calendar."))
        ui.onNodeWithText("Saved breaks").assertDoesNotExist()
        ui.onNodeWithText("Tap your first day off on the calendar.").assertExists()
    }

    @Test fun pickARangeSaveAndRemove() {
        var pick by mutableStateOf(BreakPick())
        var saved: BreakPick? = null
        var removed: BreakDto? = null
        ui.setContent { Attendance80Theme { BreaksScreen(breaksData(), breaksNow, breaksZone, pick, { pick = it }, { saved = it; true }, { removed = it }) } }
        waitList()
        ui.onNodeWithTag("breaks-month").assertTextContains("November 2026")
        ui.onNodeWithTag("break-day-2026-11-04").assertContentDescriptionContains("safe to miss", substring = true)
        ui.onNodeWithTag("break-day-2026-11-13").assertContentDescriptionContains("can't miss", substring = true)
        ui.onNodeWithTag("break-day-2026-11-04").performClick()
        ui.onNodeWithText("Now tap your last day off, or keep it to one day.").assertExists()
        ui.onNodeWithTag("break-day-2026-11-05").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("break-verdict").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("2 days · skips 3 sessions").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("break-verdict").assertTextEquals("Safe")
        ui.onNodeWithTag("breaks-list").performScrollToNode(hasTestTag("break-label"))
        ui.onNodeWithTag("break-label").performTextInput("Dentist")
        ui.onNodeWithTag("break-save").performScrollTo().performClick()
        ui.waitUntil(5_000) { saved != null }
        assertEquals(Triple("2026-11-04", "2026-11-05", "Dentist"), Triple(saved!!.from, saved!!.to, saved!!.label))
        ui.runOnIdle { assertEquals(BreakPick(month = ""), pick.copy(month = "")) }

        ui.onNodeWithTag("breaks-list").performScrollToNode(hasTestTag("saved-break-5"))
        ui.onNodeWithContentDescription("Remove Trip home").performClick()
        ui.onNodeWithText("Remove \"Trip home\"? Its sessions go back to how they were marked before.").assertExists()
        ui.onNodeWithText("Remove").performClick()
        ui.waitUntil(5_000) { removed != null }
        assertEquals(5L, removed!!.id)
    }

    @Test fun aBreakThatFailsAsksFirst() {
        var pick by mutableStateOf(BreakPick(from = "2026-11-12", to = "2026-11-13"))
        var saved = 0
        ui.setContent { Attendance80Theme { BreaksScreen(breaksData(), breaksNow, breaksZone, pick, { pick = it }, { saved++; true }, {}) } }
        waitList()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("break-verdict").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("break-verdict").assertTextEquals("Not possible")
        ui.onNodeWithText("Can't miss:").assertExists()
        ui.onNodeWithTag("breaks-list").performScrollToNode(hasTestTag("break-save"))
        ui.onNodeWithTag("break-save").performClick()
        ui.onNodeWithText("This break would drop a course below the minimum or miss an exam/mandatory session. Save it anyway?").assertExists()
        ui.onNodeWithText("Cancel").performClick()
        ui.runOnIdle { assertEquals(0, saved) }
        ui.onNodeWithTag("break-save").performClick()
        ui.onAllNodesWithText("Save break").onLast().performClick()
        ui.waitUntil(5_000) { saved == 1 }
    }

    @Test fun lengthChipsChangeSuggestionsAndASuggestionFillsTheRange() {
        var pick by mutableStateOf(BreakPick())
        ui.setContent { Attendance80Theme { BreaksScreen(breaksData(), breaksNow, breaksZone, pick, { pick = it }, { true }, {}) } }
        waitList()
        ui.onNodeWithTag("breaks-list").performScrollToNode(hasTestTag("break-len-3"))
        ui.onNodeWithTag("break-len-3").performClick().assertIsSelected()
        ui.runOnIdle { assertEquals(3, pick.len) }
        val best = com.knhdixh.attendance80.logic.findBreakWindows(BreaksViewModel.derive(breaksData(), breaksNow, breaksZone).model, 3, limit = 4).windows.first()
        // The suggestions are worked out off the main thread after the length changes.
        ui.waitUntil(10_000) { runCatching { ui.onNodeWithTag("breaks-list").performScrollToNode(hasTestTag("break-window-${best.from}")) }.isSuccess }
        ui.onNodeWithTag("break-window-${best.from}").performClick()
        ui.runOnIdle { assertEquals(Triple(best.from, best.to, best.from.take(7)), Triple(pick.from, pick.to, pick.month)) }
    }

    @Test fun monthNavigationStaysInsideTheCalendarAtLargeText() {
        var pick by mutableStateOf(BreakPick())
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Attendance80Theme { BreaksScreen(breaksData(), breaksNow, breaksZone, pick, { pick = it }, { true }, {}) }
            }
        }
        waitList()
        ui.onNodeWithContentDescription("Previous month").assertIsNotEnabled()
        ui.onNodeWithContentDescription("Next month").performClick()
        ui.onNodeWithTag("breaks-month").assertTextContains("December 2026")
        ui.onNodeWithContentDescription("Next month").assertIsNotEnabled()
        ui.onNodeWithTag("break-day-2026-12-14").assertContentDescriptionContains("Saved break: Trip home", substring = true)
        ui.onNodeWithText("Today").performClick()
        ui.onNodeWithTag("breaks-month").assertTextContains("November 2026")
    }
}
