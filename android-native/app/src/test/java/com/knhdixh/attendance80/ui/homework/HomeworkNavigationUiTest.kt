package com.knhdixh.attendance80.ui.homework

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.ui.search.PaletteAction
import java.time.ZoneId
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class HomeworkNavigationUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun expandedDoneAndViewportSurviveNullAndChangedSnapshot() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val rows = (1L..12L).map { HomeworkDto(it, title = "Finished item $it", due_date = "2026-10-25", due_at = 1793198400000L - it, done = true) }
        var data by mutableStateOf<AppData?>(base.copy(homework = rows))
        var now by mutableLongStateOf(1793198400000L)
        ui.setContent { Attendance80Theme { Shell(ShellState(data = data, session = "a"), {}, {}, {}, initialRoute = "homework", now = now, zone = ZoneId.of("Europe/Paris"), accountKey = "a") } }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Done 12 ▾").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Done 12 ▾").performClick()
        ui.onNodeWithTag("homework-list").performScrollToNode(hasText("Finished item 12"))
        ui.onNodeWithText("Finished item 12").assertIsDisplayed()
        ui.runOnIdle { data = null }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("homework-list").fetchSemanticsNodes().isEmpty() }
        ui.runOnIdle { data = base.copy(homework = rows, courses = listOf(CourseDto(99, "Unrelated"))); now += 60000 }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("homework-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Finished item 12").assertIsDisplayed()
    }
    @Test fun editedDraftSurvivesNullAndChangedSnapshotButNotReplacementAccount() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val item = HomeworkDto(7, course_id = 1, title = "Original", notes = "Original notes", due_date = "2026-10-29", due_at = 1793270000000, remind_minutes = 60)
        var data by mutableStateOf<AppData?>(base.copy(courses = listOf(CourseDto(1, "Private course")), homework = listOf(item)))
        var now by mutableLongStateOf(1793198400000L)
        var owner by mutableStateOf("a")
        ui.setContent { Attendance80Theme { Shell(ShellState(data = data, session = owner), {}, {}, {}, initialRoute = "homework/7", now = now, zone = ZoneId.of("Europe/Paris"), accountKey = owner) } }
        ui.onNodeWithText("What's due").performTextReplacement("Private edited title")
        ui.onNodeWithText("Notes").performTextReplacement("Private edited notes")
        ui.onNodeWithText("Reminder: 1 hour before").performScrollTo().performClick()
        ui.onNodeWithText("30 min before").performClick()
        ui.runOnIdle { data = null }
        ui.waitUntil(10000) { ui.onAllNodesWithText("What's due").fetchSemanticsNodes().isEmpty() }
        ui.runOnIdle { data = base.copy(homework = listOf(item), courses = listOf(CourseDto(1, "Private course"), CourseDto(99, "Unrelated"))); now += 60000 }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Private edited title").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Private edited notes").assertExists()
        ui.onNodeWithText("Course: Private course").performScrollTo().assertExists()
        ui.onNodeWithText("Reminder: 30 min before").performScrollTo().assertExists()
        ui.runOnIdle { owner = "b"; data = base.copy(courses = listOf(CourseDto(2, "Account B course")), homework = listOf(item.copy(course_id = 2, title = "Account B item", notes = "Account B notes", due_date = "2026-11-03", remind_minutes = 1440))) }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Account B item").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Private edited title").assertCountEquals(0)
        ui.onNodeWithText("Account B notes").assertExists()
        ui.onNodeWithText("Course: Account B course").performScrollTo().assertExists()
        ui.onNodeWithTag("homework-date").performScrollTo().assertTextEquals("Due date: ${homeworkDateDisplay(java.time.LocalDate.parse("2026-11-03"))}")
        ui.onNodeWithText("Reminder: 1 day before").performScrollTo().assertExists()
    }
    @Test fun draftSurvivesRefreshNavigationAndRestorationButNotAccountChange() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        var data by mutableStateOf(base)
        var account by mutableStateOf("a")
        val restoration = StateRestorationTester(ui)
        restoration.setContent { Attendance80Theme { Shell(ShellState(data = data, session = account), {}, {}, {}, initialRoute = "homework/new", now = 1793198400000L, zone = ZoneId.of("Europe/Paris"), accountKey = account) } }
        ui.onNodeWithText("What's due").performTextInput("A private draft")
        ui.runOnIdle { data = data.copy() }
        ui.onNodeWithText("A private draft").assertExists()
        ui.onNodeWithTag("nav-today").performClick()
        ui.onNodeWithTag("nav-homework").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("homework-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasText("Add homework") and hasClickAction()).performClick()
        ui.onNodeWithText("A private draft").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("A private draft").assertExists()
        ui.runOnIdle { account = "b" }
        ui.onAllNodesWithText("A private draft").assertCountEquals(0)
        ui.onNodeWithText("What's due").assertExists()
    }
    @Test fun searchResultOpensRealEditorAndMissingDeepLinkHasBack() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        var data by mutableStateOf(base.copy(homework = listOf(HomeworkDto(7, title = "Unique assignment", due_date = "2026-10-29", due_at = 1793270000000))))
        val restoration = StateRestorationTester(ui)
        restoration.setContent { Attendance80Theme { Shell(ShellState(data = data, session = "a"), {}, {}, {}, initialRoute = "homework", now = 1793198400000L, zone = ZoneId.of("Europe/Paris")) } }
        ui.onNodeWithContentDescription("Search").performClick()
        ui.onNodeWithTag("search-query").performTextInput("Unique assignment")
        ui.onNodeWithTag("result-homework:7").performClick()
        ui.onNodeWithText("Unique assignment").assertExists()
        ui.onNodeWithText("What's due").assertExists()
        ui.onNodeWithText("What's due").performTextReplacement("Changed after search")
        ui.runOnIdle { data = data.copy(courses = listOf(CourseDto(91, "Changed elsewhere"))) }
        ui.onNodeWithText("Changed after search").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("Changed after search").assertExists()
        ui.onNodeWithText("Cancel").performScrollTo().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("homework-list").fetchSemanticsNodes().isNotEmpty() }
        Assert.assertEquals("homework/7", com.knhdixh.attendance80.navigation.internalRoute("a80://homework/7"))
    }
    @Test fun rowOpensRecordingAndCarriesFocusAndCalendarPayloads() {
        val now = 1793198400000L
        val zone = ZoneId.of("Europe/Paris")
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val item = HomeworkDto(17, title = "Catch-up", notes = "Recording: https://example.org/lecture", kind = "catchup", due_date = "2026-10-29", due_at = now + 86400000, planned_at = now + 3600000)
        var action: PaletteAction? = null
        var destination = ""
        ui.setContent { Attendance80Theme { HomeworkRow(item, data, data.events, now, zone, {}, {}, {}, { a, d -> action = a; destination = d }) } }
        ui.onNodeWithText("recording ↗").performClick()
        val intent = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        Assert.assertEquals(Intent.ACTION_VIEW, intent.action)
        Assert.assertEquals("https://example.org/lecture", intent.data.toString())
        ui.onNodeWithText("Focus on this").performClick()
        ui.runOnIdle { Assert.assertEquals(PaletteAction.Focus("target", homeworkId = 17), action); Assert.assertEquals("focus", destination) }
        ui.onNode(hasText("planned", substring = true) and hasClickAction()).performClick()
        ui.runOnIdle { Assert.assertEquals(PaletteAction.OpenForm("calendar-show", input = (now + 3600000).toString()), action); Assert.assertEquals("calendar", destination) }
    }
    @Test fun missingHomeworkDeepLinkOffersBackToList() {
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        ui.setContent { Attendance80Theme { Shell(ShellState(data = data, session = "a"), {}, {}, {}, initialRoute = "homework/999", now = 1793198400000L, zone = ZoneId.of("Europe/Paris")) } }
        ui.onNodeWithText("Homework not found.").assertExists()
        ui.onNodeWithText("Back").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("homework-list").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Nothing due. Press Q to add homework.").assertExists()
    }
    @Test fun focusAndCalendarRequestsRemainPendingAcrossRestoration() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val planned = 1793202000000L
        val data = base.copy(homework = listOf(HomeworkDto(17, title = "Focus target", due_date = "2026-10-29", due_at = 1793270000000, planned_at = planned)))
        lateinit var controller: NavHostController
        val restoration = StateRestorationTester(ui)
        restoration.setContent { Attendance80Theme {
            controller = rememberNavController()
            Shell(ShellState(data = data, session = "a"), {}, {}, {}, initialRoute = "homework", now = 1793198400000L, zone = ZoneId.of("Europe/Paris"), navController = controller)
        } }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Focus on this").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Focus on this").performClick()
        ui.onNodeWithTag("shell-title").assertTextEquals("Focus")
        ui.onNodeWithText("Focus request saved for the timer.").assertExists()
        ui.runOnIdle { Assert.assertEquals(PaletteAction.Focus("target", homeworkId = 17), ApiClient.json.decodeFromString<PaletteAction>(controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction")!!)) }
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("Focus request saved for the timer.").assertExists()
        ui.runOnIdle { Assert.assertEquals(PaletteAction.Focus("target", homeworkId = 17), ApiClient.json.decodeFromString<PaletteAction>(controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction")!!)) }
        ui.onNodeWithTag("nav-homework").performClick()
        ui.waitUntil(10000) { ui.onAllNodes(hasText("planned", substring = true) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasText("planned", substring = true) and hasClickAction()).performClick()
        ui.onNodeWithTag("shell-title").assertTextEquals("Calendar")
        // The Calendar acts on the request: it opens on the planned day and clears it, and keeps that day after restoration.
        val plannedDay = java.time.Instant.ofEpochMilli(planned).atZone(ZoneId.of("Europe/Paris")).toLocalDate().toString()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("calendar-day-$plannedDay").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle { Assert.assertNull(controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction")) }
        ui.onNodeWithTag("calendar-plan-17").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithTag("calendar-day-$plannedDay").assertExists()
        ui.runOnIdle { Assert.assertNull(controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction")) }
    }
    @Test fun longDeleteConfirmationKeepsActionsReachableAtDoubleTextSize() {
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        val item = HomeworkDto(31, title = "A".repeat(200), due_date = "2026-10-29", due_at = 1793270000000)
        var deleted = false
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
            Attendance80Theme { HomeworkRow(item, data, emptyList(), 1793198400000L, ZoneId.of("Europe/Paris"), {}, {}, { deleted = true }, { _, _ -> }) }
        } }
        ui.onNodeWithText("Delete").performClick()
        ui.onNodeWithText("Cancel").assertIsDisplayed()
        ui.onAllNodesWithText("Delete").onLast().assertIsDisplayed().performClick()
        ui.runOnIdle { Assert.assertTrue(deleted) }
    }
    @Test fun matchingSavedFormActionIsConsumedOnceWithoutClearingOtherOwners() {
        val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        var data by mutableStateOf(base)
        lateinit var controller: NavHostController
        val restoration = StateRestorationTester(ui)
        restoration.setContent { Attendance80Theme {
            controller = rememberNavController()
            Shell(ShellState(data = data, session = "a"), {}, {}, {}, initialRoute = "homework/new", now = 1793198400000L, zone = ZoneId.of("Europe/Paris"), navController = controller)
        } }
        val other = ApiClient.json.encodeToString<PaletteAction>(PaletteAction.OpenForm("habit", "unrelated"))
        ui.runOnIdle { controller.currentBackStackEntry!!.savedStateHandle["paletteAction"] = other }
        ui.runOnIdle { Assert.assertEquals(other, controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction")) }
        val matching = ApiClient.json.encodeToString<PaletteAction>(PaletteAction.OpenForm("homework", "Prefilled assignment"))
        ui.runOnIdle { controller.currentBackStackEntry!!.savedStateHandle["paletteAction"] = matching }
        ui.waitUntil(10000) { ui.onAllNodesWithText("Prefilled assignment").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle { Assert.assertNull(controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction")) }
        ui.onNodeWithText("What's due").performTextReplacement("Edited after prefill")
        ui.runOnIdle { data = data.copy(courses = listOf(CourseDto(99, "Unrelated"))) }
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithText("Edited after prefill").assertExists()
        ui.runOnIdle { Assert.assertNull(controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction")) }
        ui.onNodeWithText("What's due").performTextReplacement("")
        ui.onNodeWithText("Notes").performTextReplacement("Keep these notes")
        val later = ApiClient.json.encodeToString<PaletteAction>(PaletteAction.OpenForm("homework", "Another prefill"))
        ui.runOnIdle { controller.currentBackStackEntry!!.savedStateHandle["paletteAction"] = later }
        ui.waitUntil(10000) { controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction") == null }
        ui.onNodeWithText("What's due").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        ui.onNodeWithText("Keep these notes").assertExists()
    }
    @Test fun savedPrefillDoesNotReplaceNotesOnlyDraft() {
        val data = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data_empty.json")!!.readText())
        lateinit var controller: NavHostController
        ui.setContent { Attendance80Theme {
            controller = rememberNavController()
            Shell(ShellState(data = data, session = "a"), {}, {}, {}, initialRoute = "homework/new", now = 1793198400000L, zone = ZoneId.of("Europe/Paris"), navController = controller)
        } }
        ui.onNodeWithText("Notes").performTextInput("Notes already entered")
        val action = ApiClient.json.encodeToString<PaletteAction>(PaletteAction.OpenForm("homework", "Do not insert"))
        ui.runOnIdle { controller.currentBackStackEntry!!.savedStateHandle["paletteAction"] = action }
        ui.waitUntil(10000) { controller.currentBackStackEntry!!.savedStateHandle.get<String>("paletteAction") == null }
        ui.onNodeWithText("What's due").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        ui.onNodeWithText("Notes already entered").assertExists()
    }
}
