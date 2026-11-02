package com.knhdixh.attendance80.ui.focus

import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.knhdixh.attendance80.data.FocusHistoryStatus
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class FocusUiTest {
    @get:Rule val ui = createComposeRule()
    private val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    private val base = ApiClient.json.decodeFromString<AppData>(javaClass.getResource("/api/data.json")!!.readText())
    @Test fun emptyAndHistoryFailure() {
        val data = base.copy(focus = base.focus.copy(sessions = emptyList(), since = now))
        ui.setContent { Attendance80Theme { FocusScreen(data, "a", now, zone, FocusHistoryStatus("a", 1, failed = true), rememberLazyListState(), null, {}, {}, { false }, { false }, {}, null, {}) } }
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Couldn’t load the year."))
        ui.onNodeWithText("Couldn’t load the year.").assertIsDisplayed()
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Your focus sessions will show here."))
    }
    @Test fun populatedWeekHasAccessibleDayTotalsAndRangeSelection() {
        val data = base.copy(focus = base.focus.copy(since = 0))
        ui.setContent { Attendance80Theme { FocusScreen(data, "a", now, zone, FocusHistoryStatus(), rememberLazyListState(), null, {}, {}, { false }, { false }, {}, null, {}) } }
        ui.onNode(hasContentDescription("Wed", substring = true) and hasContentDescription("2 pomodoros", substring = true)).assertExists()
        ui.onNodeWithTag("focus-range-30").performClick().assertIsSelected()
        ui.onNodeWithTag("focus-range-365").performClick().assertIsSelected()
        ui.onNodeWithTag("focus-list").performScrollToNode(hasTestTag("focus-heatmap"))
        ui.onNodeWithTag("focus-heatmap").assertExists()
    }
    @Test fun populatedSettingsAndDeleteAtLargeText() {
        var draft by mutableStateOf<String?>(null); var submitted: JsonObject? = null; var deleted: Long? = null
        val data = base.copy(focus = base.focus.copy(since = 0))
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { Attendance80Theme {
            FocusScreen(data, "a", now, zone, FocusHistoryStatus(), rememberLazyListState(), draft, { draft = it }, {}, { submitted = it; true }, { deleted = it; true }, {}, null, {})
        } } }
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Recent sessions"))
        ui.onAllNodesWithText("Delete").onFirst().performClick()
        ui.onNodeWithText("Delete this focus session from your stats?").assertExists()
        ui.onAllNodesWithText("Delete").onLast().performClick()
        ui.runOnIdle { assertNotNull(deleted) }
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Daily goal"))
        ui.onNodeWithText("Daily goal").performTextClearance(); ui.onNodeWithText("Daily goal").performTextInput("0")
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Save"))
        ui.onNodeWithText("Save").assertIsNotEnabled()
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Daily goal"))
        ui.onNodeWithText("Daily goal").performTextClearance(); ui.onNodeWithText("Daily goal").performTextInput("3")
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Save")); ui.onNodeWithText("Save").performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); submitted != null }; assertEquals(3, submitted!!["goal"]!!.jsonPrimitive.int)
    }
    @Test fun shellRetainsTouchedDraftAndViewportForOwnerAndClearsOnReplacement() {
        val data = base.copy(focus = base.focus.copy(since = 0))
        var state by mutableStateOf(ShellState(data = data, session = "a"))
        var owner by mutableStateOf("a")
        ui.setContent { Attendance80Theme { Shell(state, {}, {}, {}, initialRoute = "focus", now = now, zone = zone, accountKey = owner) } }
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Daily goal"))
        ui.onNodeWithText("Daily goal").performScrollTo().performTextReplacement("3")
        ui.runOnIdle { state = ShellState(data = null, session = "a") }
        ui.runOnIdle { state = ShellState(data = data, session = "a") }
        ui.onNodeWithText("Daily goal").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("3")))
        ui.onNodeWithTag("nav-homework").performClick(); ui.onNodeWithTag("nav-focus").performClick()
        ui.onNodeWithText("Daily goal").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("3")))
        ui.runOnIdle { owner = "b"; state = ShellState(data = data, session = "b") }
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Daily goal"))
        ui.onNodeWithText("Daily goal").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(data.focus.settings.goal.toString())))
    }
    @Test fun taskAndTagEditorsSendCleanedListsAndApplyCanonicalResponse() {
        var draft by mutableStateOf<String?>(null)
        var saved = base.focus.settings
        var writes = 0
        val data = base.copy(focus = base.focus.copy(since = 0))
        ui.setContent { Attendance80Theme { FocusScreen(data, "a", now, zone, FocusHistoryStatus(), rememberLazyListState(), draft, { draft = it }, {}, { false }, { false }, {}, null, {}, { body ->
            writes++
            body["tasks"]?.let { saved = saved.copy(tasks = it.jsonArray.map { v -> v.jsonPrimitive.content }) }
            body["tags"]?.let { saved = saved.copy(tags = it.jsonArray.map { v -> v.jsonPrimitive.content }) }
            saved
        }) } }
        ui.onNodeWithTag("focus-list").performScrollToNode(hasText("New task"))
        ui.onNodeWithText("New task").performScrollTo().performTextInput("  Read   paper  ")
        ui.onNodeWithText("Add task").performScrollTo().performClick()
        ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); writes == 1 }
        ui.onNodeWithText("Read paper").assertExists()
        ui.onNodeWithText("New tag").performScrollTo().performTextInput(" ##Study  Notes ")
        ui.onNodeWithText("Add tag").performScrollTo().performClick()
        ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); writes == 2 }
        ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); ui.onAllNodesWithText("#study notes").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("#study notes").assertExists()
        ui.onNodeWithContentDescription("Remove Read paper").performScrollTo().performClick()
        ui.waitUntil(5000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); writes == 3 }
        assertTrue(saved.tasks.isEmpty()); assertEquals(listOf("study notes"), saved.tags)
    }
}
