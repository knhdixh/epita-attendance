package com.knhdixh.attendance80.ui.sessions

import com.knhdixh.attendance80.ui.openFromBrowse
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.*
import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import java.time.ZoneId
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionSearchNavigationTest {
    @get:Rule val ui = createComposeRule()
    @Test fun consumedSearchDoesNotOverrideEditedQueryOnRestoration() {
        val restoration = StateRestorationTester(ui)
        restoration.setContent { Attendance80Theme { Shell(ShellState(data = screenshotData(), session = "owner"), {}, {}, {}, now = 1793600000000L, zone = ZoneId.of("Europe/Paris")) } }
        ui.onNodeWithContentDescription("Search").performClick()
        ui.onNodeWithTag("search-query").performTextInput("unusual search")
        ui.onNodeWithTag("result-fallback:sessions").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("unusual search").performTextReplacement("new query")
        ui.onNodeWithTag("nav-today").performClick()
        ui.openFromBrowse("sessions")
        ui.waitUntil(10000) { ui.onAllNodesWithText("new query").fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()
        ui.waitUntil(10000) { ui.onAllNodesWithText("new query").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("unusual search").assertCountEquals(0)
    }
    @Test fun equalLoadingSnapshotRetainsFilterAndAccountChangeClearsIt() {
        val data = screenshotData()
        var state by mutableStateOf(ShellState(data = data, session = "a"))
        ui.setContent { Attendance80Theme { Shell(state, {}, {}, {}, initialRoute = "sessions", now = 1793600000000L, zone = ZoneId.of("Europe/Paris")) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("sessions-list"))).performTextInput("private query")
        ui.runOnIdle { state = state.copy(data = data.copy(), loading = true) }
        ui.waitUntil(10000) { ui.onAllNodesWithText("private query").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("private query").assertExists()
        ui.runOnIdle { state = ShellState(data = null, session = null) }
        ui.runOnIdle { state = ShellState(data = data.copy(), session = "b") }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("private query").assertCountEquals(0)
    }
    @Test fun scopedListAndOwnAccountDeepLinkUseDifferentEventSets() {
        val base = screenshotData()
        val own = base.events.first().copy(id = 9701, summary = "Scoped out session")
        val visible = base.events.first().copy(id = 9702, summary = "Scoped session")
        var shell by mutableStateOf(ShellState(data = base.copy(events = listOf(visible)), allEvents = listOf(visible, own), session = "a"))
        var pending by mutableStateOf<String?>(null)
        ui.setContent { Attendance80Theme { Shell(shell, {}, {}, {}, initialRoute = "sessions", pendingRoute = pending, onPendingConsumed = { pending = null }, now = 1793600000000L, zone = ZoneId.of("Europe/Paris")) } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("sessions-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Scoped out session").assertCountEquals(0)
        ui.runOnIdle { pending = "session/9701" }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Scoped out session").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle { pending = null; shell = ShellState(data = base.copy(events = listOf(visible)), allEvents = listOf(visible), session = "b") }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Session not found.").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Scoped out session").assertCountEquals(0)
    }
}
