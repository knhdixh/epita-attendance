package com.knhdixh.attendance80.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.knhdixh.attendance80.navigation.PendingRouteViewModel
import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.ui.settings.FakeSettingsActions
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/** What the activity does with an incoming intent, through the real shell: the right screen, prefilled, nothing sent on its own. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedIntoShellTest {
    @get:Rule val ui = createComposeRule()
    private val zone = ZoneId.of("Europe/Paris")

    @Test fun sharedTextOpensAddHomeworkWithTheTitleAndNotes() {
        val route = PendingRouteViewModel(SavedStateHandle())
        route.shareText("Read chapter 3\nPages 10-20")
        assertEquals("homework/new", route.pending.value)
        var pending by mutableStateOf<String?>(route.pending.value)
        var text by mutableStateOf<String?>(route.sharedText.value)
        ui.setContent { Attendance80Theme {
            Shell(ShellState(data = screenshotData(), session = "a"), {}, {}, {}, pendingRoute = pending, onPendingConsumed = { route.clear(); pending = null },
                now = 1793600000000L, zone = zone, sharedText = text, onSharedTextHandled = { route.textHandled(); text = null })
        } }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("What's due").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("What's due").assertTextContains("Read chapter 3")
        ui.onNodeWithText("Notes").assertTextContains("Pages 10-20")
        assertNull(route.sharedText.value) // handled once: coming back to the form later is not refilled
    }

    @Test fun aSharedCalendarOpensSettingsWithTheFileReadyAndUploadsOnlyWhenAsked() {
        val route = PendingRouteViewModel(SavedStateHandle())
        route.shareFile(Incoming.CalendarFile("S3.ics", "BEGIN:VCALENDAR\nEND:VCALENDAR\n"))
        val actions = FakeSettingsActions()
        var pending by mutableStateOf<String?>(route.pending.value)
        var file by mutableStateOf<Incoming.CalendarFile?>(route.sharedFile.value)
        ui.setContent { Attendance80Theme {
            Shell(ShellState(data = screenshotData(), session = "a"), {}, {}, {}, pendingRoute = pending, onPendingConsumed = { route.clear(); pending = null },
                now = 1793600000000L, zone = zone, settingsActions = actions, sharedFile = file, onSharedFileHandled = { route.fileHandled(); file = null })
        } }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("calendar-shared-name").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("calendar-shared-name").assertTextEquals("S3.ics is ready to add.")
        assertTrue(actions.calls.none { it.startsWith("upload") })
        ui.onNodeWithTag("calendar-shared-add").performClick()
        ui.waitUntil(10_000) { actions.calls.any { it.startsWith("upload") } }
        assertEquals("upload S3.ics null BEGIN:VCALENDAR\nEND:VCALENDAR\n", actions.calls.single { it.startsWith("upload") })
        ui.waitUntil(10_000) { file == null }
        assertNull(route.sharedFile.value)
    }

    @Test fun anUnreadableFileShowsItsErrorAndIsDropped() {
        val route = PendingRouteViewModel(SavedStateHandle())
        route.shareFile(Incoming.CalendarFile("S3.ics", null, "That file is over 5 MB."))
        val actions = FakeSettingsActions()
        var pending by mutableStateOf<String?>(route.pending.value)
        var file by mutableStateOf<Incoming.CalendarFile?>(route.sharedFile.value)
        ui.setContent { Attendance80Theme {
            Shell(ShellState(data = screenshotData(), session = "a"), {}, {}, {}, pendingRoute = pending, onPendingConsumed = { route.clear(); pending = null },
                now = 1793600000000L, zone = zone, settingsActions = actions, sharedFile = file, onSharedFileHandled = { route.fileHandled(); file = null })
        } }
        ui.waitUntil(10_000) { actions.messages.isNotEmpty() }
        assertEquals(listOf("That file is over 5 MB."), actions.messages)
        ui.waitUntil(10_000) { file == null }
    }
}
