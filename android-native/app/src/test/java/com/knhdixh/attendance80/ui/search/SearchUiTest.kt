package com.knhdixh.attendance80.ui.search

import androidx.compose.ui.test.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    @Test fun copiedFeedUsesNormalizedCount() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val original = screenshotData()
        var data by androidx.compose.runtime.mutableStateOf(original.copy(calendars = listOf(
            com.knhdixh.attendance80.net.CalendarDto(1, "link", "First"),
            com.knhdixh.attendance80.net.CalendarDto(2, "link", "Second")),
            integrations = original.integrations.copy(feedUrl = "https://example.test/feed.ics")))
        var selected by androidx.compose.runtime.mutableStateOf(listOf(1L, 1L, 99L))
        compose.setContent { Attendance80Theme { Shell(ShellState(data = data, selected = selected), {}, {}, {}) } }
        fun copy() {
            compose.onNodeWithContentDescription("Search").performClick()
            compose.onNodeWithTag("search-query").performTextInput("copy timetable")
            compose.onNodeWithTag("result-copy-feed").performClick()
        }
        copy()
        compose.runOnIdle { assertEquals("https://example.test/feed/timetable.ics?groups=1", clipboard.primaryClip!!.getItemAt(0).text.toString()); selected = listOf(1, 2, 99) }
        copy()
        compose.runOnIdle { assertEquals("https://example.test/feed/timetable.ics", clipboard.primaryClip!!.getItemAt(0).text.toString()); selected = listOf(99) }
        copy()
        compose.runOnIdle { assertEquals("https://example.test/feed/timetable.ics", clipboard.primaryClip!!.getItemAt(0).text.toString()); data = data.copy(calendars = emptyList()); selected = listOf(1) }
        copy()
        compose.runOnIdle { assertEquals("https://example.test/feed/timetable.ics", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
    }

}
