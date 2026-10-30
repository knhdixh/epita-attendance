package com.knhdixh.attendance80.ui.search

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
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
class SearchUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun searchResultOpensItsRoute() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Attendance80Theme { Shell(ShellState(data = screenshotData()), {}, {}, {}) } }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("search-query").performTextInput("read")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("result-habit:1").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("shell-title").assertTextEquals("Habits")
        compose.onAllNodesWithText("Read").onLast().assertExists()
    }
    @Test fun fallbackSelectionDispatchesParsedAction() {
        var chosen: PaletteAction? = null
        compose.setContent { Attendance80Theme { SearchSheet(screenshotData(), {}, {}, {}, onAction = { action, _ -> chosen = action }, now = 1793600000000) } }
        compose.onNodeWithTag("search-query").performTextInput("Read 3x a week")
        compose.onNodeWithTag("result-fallback:habit").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(PaletteAction.AddHabit("Read 3x a week"), chosen) }
    }
    @Test fun emptySnapshotAndClose() {
        compose.mainClock.autoAdvance = false
        var dismissed = false
        compose.setContent { Attendance80Theme { SearchSheet(null, { dismissed = true }, {}, {}) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Nothing found.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(true, dismissed) }
    }
    @Test fun emptyQueryNoMatchesAndSignOutAction() {
        compose.mainClock.autoAdvance = false
        var signedOut = false
        compose.setContent { Attendance80Theme { SearchSheet(screenshotData(), {}, {}, { signedOut = true }) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Go to Today").assertIsDisplayed()
        compose.onNodeWithTag("search-query").performTextInput("does not exist")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Search all sessions for “does not exist”").assertIsDisplayed()
        compose.onNodeWithTag("search-query").performTextClearance()
        compose.onNodeWithTag("search-query").performTextInput("sign out")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("result-logout").performClick()
        compose.runOnIdle { assertEquals(true, signedOut) }
    }
}
