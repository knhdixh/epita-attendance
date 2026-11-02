package com.knhdixh.attendance80.ui.settings

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId

/** The palette's "Add calendar" and "Upload .ics file" reach Settings as saved OpenForm requests (review 4, finding 4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SettingsPaletteRequestTest {
    @get:Rule val ui = createComposeRule()
    private fun data(): AppData {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        return base.copy(calendars = listOf(CalendarDto(1, "link", "Zeus")))
    }
    private fun app() = ApplicationProvider.getApplicationContext<Application>()

    /** Chooses [label] in the real palette, from Today, and waits for Settings. */
    private fun choose(label: String, controller: () -> NavHostController) {
        ui.onNodeWithContentDescription("Search").performClick()
        ui.onNodeWithTag("search-query").performTextInput(label)
        ui.waitUntil(10_000) { ui.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag("search-results"))).fetchSemanticsNodes().isNotEmpty() || ui.onAllNodesWithText(label).fetchSemanticsNodes().size > 1 }
        ui.onAllNodesWithText(label).onLast().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun uploadFromThePaletteOpensThePickerOnceAndClearsTheRequest() {
        lateinit var controller: NavHostController
        val restoration = StateRestorationTester(ui)
        restoration.setContent { Attendance80Theme {
            controller = rememberNavController()
            Shell(ShellState(data = data(), session = "a"), {}, {}, {}, initialRoute = "today", now = 1793198400000L, zone = ZoneId.of("Europe/Paris"), navController = controller)
        } }
        choose("Upload .ics file") { controller }
        ui.waitUntil(10_000) { shadowOf(app()).peekNextStartedActivity() != null }
        val intent = shadowOf(app()).nextStartedActivity
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        ui.runOnIdle { assertNull(controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction")) }
        // Restoring the screen must not replay the picker.
        restoration.emulateSavedInstanceStateRestore()
        ui.waitForIdle()
        assertNull(shadowOf(app()).peekNextStartedActivity())
    }

    @Test fun addCalendarFromThePaletteOpensTheBoxFocusesTheLinkAndClearsTheRequest() {
        lateinit var controller: NavHostController
        val restoration = StateRestorationTester(ui)
        restoration.setContent { Attendance80Theme {
            controller = rememberNavController()
            Shell(ShellState(data = data(), session = "a"), {}, {}, {}, initialRoute = "today", now = 1793198400000L, zone = ZoneId.of("Europe/Paris"), navController = controller)
        } }
        choose("Add calendar") { controller }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("calendar-link-url").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("calendar-link-url").assertIsDisplayed().assertIsFocused()
        ui.runOnIdle { assertNull(controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction")) }
        assertNull(shadowOf(app()).peekNextStartedActivity())
        // The request is gone: restoring keeps the box the user left open and does not run it again.
        restoration.emulateSavedInstanceStateRestore()
        ui.waitForIdle()
        ui.onNodeWithTag("calendar-link-url").assertExists()
        assertNull(shadowOf(app()).peekNextStartedActivity())
    }
}
