package com.knhdixh.attendance80.ui.tasks

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.knhdixh.attendance80.net.*
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
class FiltersScreenTest {
    @get:Rule val ui = createComposeRule()
    private val data = taskData(task(1, "Pay rent", due = TODAY, priority = 4), task(2, "Email Sam", due = TODAY), task(3, "Later", due = "2026-12-01", priority = 4),
        labels = listOf(LabelDto(7, "errands")), filters = listOf(FilterDto(3, "Urgent", "today & p1", favorite = true)))

    @Test fun previewCountsWhatMatchesAndExplainsWhatDoesNotParse() {
        val ok = previewFilter("today & p1", data, TODAY)
        assertTrue(ok.ok); assertEquals(1, ok.count); assertEquals(listOf("Pay rent"), ok.titles)
        assertEquals(2, previewFilter("today", data, TODAY).count)
        val bad = previewFilter("today &", data, TODAY)
        assertFalse(bad.ok); assertNotNull(bad.error)
        assertEquals(0, previewFilter("", data, TODAY).count)
    }

    @Test fun listsFiltersAndLabelsAndEditsThemThroughTheActions() {
        val a = RecordingActions()
        ui.setContent { Attendance80Theme { FiltersScreen(TaskEnv(data, TODAY, taskZone), a, onNavigate = {}) } }
        ui.onNodeWithTag("filter-row-3").assertExists(); ui.onNodeWithTag("label-row-7").assertExists()
        ui.onNodeWithTag("filter-menu-3").performClick(); ui.onNodeWithTag("filter-favourite-3").performClick()
        assertEquals(Triple("filter", 3L, buildJsonObject { put("favorite", false) }), a.entityUpdates.single())
        ui.onNodeWithTag("label-menu-7").performClick(); ui.onNodeWithTag("label-delete-7").performClick()
        assertEquals("label" to 7L, a.entityDeletes.single())
    }

    @Test fun theEditorShowsALivePreviewAndSavesAValidQuery() {
        val a = RecordingActions()
        ui.setContent { Attendance80Theme { FiltersScreen(TaskEnv(data, TODAY, taskZone), a, onNavigate = {}) } }
        ui.onNodeWithTag("filters-add").performClick()
        // SetText doesn't focus: a focused field blinks its cursor for ever and the test never gets idle.
        fun type(tag: String, text: String) = ui.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
        type("filter-name", "Today only")
        type("filter-query", "today &")
        ui.onNodeWithTag("filter-preview-error").assertExists()
        ui.onNodeWithTag("filter-save").assertIsNotEnabled()
        type("filter-query", "today")
        ui.onNodeWithTag("filter-preview-count").assertTextEquals("2 matching tasks")
        ui.onNodeWithTag("filter-save").performClick()
        assertEquals("filter" to buildJsonObject { put("name", "Today only"); put("query", "today") }, a.entities.single())
    }
}
