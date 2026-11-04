package com.knhdixh.attendance80.ui.tasks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.logic.QuickAddParser
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records what the sheet asks for; ids are handed out like the outbox's temporary ones. */
class RecordingActions : TaskActions by NoTaskActions {
    val created = mutableListOf<JsonObject>()
    val entities = mutableListOf<Pair<String, JsonObject>>()
    val reminders = mutableListOf<Pair<Long, JsonObject>>()
    private var next = -1L
    override suspend fun create(body: JsonObject): Long? { created += body; return next-- }
    override suspend fun createEntity(noun: String, body: JsonObject): Long? { entities += noun to body; return next-- }
    override suspend fun addReminder(taskId: Long, body: JsonObject): Boolean { reminders += taskId to body; return true }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class QuickAddSheetTest {
    @get:Rule val ui = createComposeRule()
    private val data = taskData(projects = listOf(ProjectDto(5, name = "Home")), labels = listOf(LabelDto(7, "errands")),
        sections = listOf(SectionDto(9, 5, "Week 1")))

    @Test fun whatTheParserReadIsHighlightedInItsColour() {
        val text = "Buy milk tomorrow p1 #Home @errands"
        val q = QuickAddParser.parseQuickAdd(text, TODAY, QuickAddPlan.names(data))
        val s = QuickAddPlan.highlight(text, q.ranges)
        val coloured = s.spanStyles.map { text.substring(it.start, it.end) to it.item.color }
        assertEquals(listOf("tomorrow", "p1", "#Home", "@errands"), coloured.map { it.first })
        assertTrue(coloured.all { it.second != Color.Unspecified })
        assertEquals("Buy milk", q.title)
    }

    @Test fun chipsEditTheTextByReplacingWhatWasThere() {
        val text = "Read p2 tomorrow"
        val q = QuickAddParser.parseQuickAdd(text, TODAY, QuickAddPlan.names(data))
        assertEquals("Read tomorrow p1", QuickAddPlan.replace(text, q.ranges, "priority", "p1"))
        assertEquals("Read p2", QuickAddPlan.replace(text, q.ranges, "date", ""))
    }

    @Test fun planReadsTheLineIntoTheTaskFields() {
        val q = QuickAddParser.parseQuickAdd("Report tomorrow 9am p1 #Home /Week 1 @errands @new !30m for 45m every week", TODAY, QuickAddPlan.names(data))
        val plan = QuickAddPlan.plan(q, data, taskZone)
        val b = plan.body
        assertEquals("Report", b["title"]!!.jsonPrimitive.content)
        assertEquals("2026-11-05", b["due_date"]!!.jsonPrimitive.content)
        assertEquals("09:00", b["due_time"]!!.jsonPrimitive.content)
        assertEquals(4, b["priority"]!!.jsonPrimitive.int)
        assertEquals(5, b["project_id"]!!.jsonPrimitive.int); assertEquals(9, b["section_id"]!!.jsonPrimitive.int)
        assertEquals(45, b["effort_minutes"]!!.jsonPrimitive.int)
        assertEquals(listOf("new"), plan.newLabels)
        assertEquals(30, plan.reminder)
        assertNotNull(b["recurrence"])
    }

    @Test fun typingHighlightsAndSavingCreatesTheTaskWithItsFieldsAndKeepsTheKeyboardUp() {
        val actions = RecordingActions()
        ui.setContent { Attendance80Theme { QuickAddSheet(data, TODAY, taskZone, QuickDefaults(), actions, onDismiss = {}) } }
        ui.onNodeWithTag("quick-add-input").performTextInput("Buy milk tomorrow p1 #Home @errands @fresh !30m")
        ui.onNodeWithTag("quick-chip-priority").assertTextContains("p1")
        ui.onNodeWithTag("quick-chip-date").assertTextContains("Tomorrow")
        ui.onNodeWithTag("quick-chips").performScrollToNode(hasTestTag("quick-chip-project"))
        ui.onNodeWithTag("quick-chip-project").assertTextContains("Home")
        ui.onNodeWithTag("quick-chips").performScrollToNode(hasTestTag("quick-chip-reminder"))
        ui.onNodeWithTag("quick-chip-reminder").assertTextContains("30 min before")
        ui.onNodeWithTag("quick-add-save").performClick()
        ui.waitUntil(5_000) { actions.created.isNotEmpty() }
        val b = actions.created.single()
        assertEquals("Buy milk", b["title"]!!.jsonPrimitive.content)
        assertEquals("2026-11-05", b["due_date"]!!.jsonPrimitive.content)
        assertEquals(4, b["priority"]!!.jsonPrimitive.int)
        assertEquals(5, b["project_id"]!!.jsonPrimitive.int)
        // The label that doesn't exist yet is made first, and the task names it by the temporary id it got.
        assertEquals("label", actions.entities.single().first)
        assertEquals(listOf(7L, -1L), (b["label_ids"] as JsonArray).map { it.jsonPrimitive.long })
        assertEquals(30, actions.reminders.single().second["before_minutes"]!!.jsonPrimitive.int)
        // Ready for the next one: the line is empty and the field still has focus (so the keyboard stays up).
        ui.onNodeWithTag("quick-add-input").assertIsFocused()
        ui.onNodeWithTag("quick-add-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        ui.onNodeWithTag("quick-add-input").performTextInput("Second task")
        ui.onNodeWithTag("quick-add-save").performClick()
        ui.waitUntil(5_000) { actions.created.size == 2 }
        assertEquals("Second task", actions.created[1]["title"]!!.jsonPrimitive.content)
    }

    @Test fun aChipPickWritesItsTokenIntoTheLine() {
        ui.setContent { Attendance80Theme { QuickAddSheet(data, TODAY, taskZone, QuickDefaults(), RecordingActions(), onDismiss = {}) } }
        ui.onNodeWithTag("quick-add-input").performTextInput("Call")
        ui.onNodeWithTag("quick-chip-priority").performClick()
        ui.onNodeWithTag("quick-pick-p2").performClick()
        ui.onNodeWithTag("quick-chip-priority").assertTextContains("p2")
        ui.onNodeWithTag("quick-add-input").assert(hasText("Call p2"))
    }
}
