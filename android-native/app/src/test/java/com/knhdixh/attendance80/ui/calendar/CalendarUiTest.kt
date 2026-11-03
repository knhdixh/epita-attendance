package com.knhdixh.attendance80.ui.calendar

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import kotlinx.serialization.json.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Native graphics: the bottom panel's top-rounded clip is hit-tested with Path.contains, which legacy Robolectric
// graphics doesn't implement, so touches on the panel would be lost in the test only.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class CalendarUiTest {
    @get:Rule val compose = createComposeRule()
    private var ui by mutableStateOf(CalendarUi())
    private val actions = FakeCalendarActions()
    private val sent = mutableListOf<PaletteAction>()
    private val routes = mutableListOf<String>()
    private var pending by mutableStateOf<PaletteAction.OpenForm?>(null)
    private fun show(data: AppData = calData()) {
        compose.setContent { Attendance80Theme { CalendarScreen(data, calNow, calZone, ui, { ui = it }, actions, { sent += it }, { routes += it }, { routes += "focus:$it" },
            pending, { pending = null }) } }
    }
    private fun idle(cond: () -> Boolean) = compose.waitUntil(5_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); cond() }
    // Grid geometry at mdpi on a phone: 44 px an hour from 7:00.
    // Localised times (newer JDKs put a narrow no-break space before AM/PM).
    private fun t(ms: Long) = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).format(java.time.Instant.ofEpochMilli(ms).atZone(calZone))
    private fun yAt(h: Int, m: Int = 0) = ((h - 7) * 60 + m) / 60f * 44f + 2f

    @Test fun opensOnThreeDaysAndMovesBetweenPeriods() {
        show()
        compose.onNodeWithTag("calendar-view-3day").assertIsSelected()
        compose.onNodeWithTag("calendar-title").assertTextEquals("November 2026")
        compose.onNodeWithTag("calendar-day-2026-11-04").assertExists()
        compose.onNodeWithTag("calendar-day-2026-11-06").assertExists()
        compose.onNodeWithTag("calendar-now").assertExists()
        compose.onNodeWithTag("calendar-next").performClick()
        compose.onNodeWithTag("calendar-day-2026-11-07").assertExists()
        compose.onNodeWithTag("calendar-day-2026-11-04").assertDoesNotExist()
        compose.onNodeWithTag("calendar-today").performClick()
        compose.onNodeWithTag("calendar-day-2026-11-04").assertExists()
        compose.onNodeWithTag("calendar-view-week").performClick()
        compose.onNodeWithTag("calendar-day-2026-11-02").assertExists()
        compose.onNodeWithTag("calendar-day-2026-11-08").assertExists()
        compose.onNodeWithTag("calendar-view-month").performClick()
        compose.onNodeWithTag("calendar-cell-2026-11-30").assertExists()
        compose.onNodeWithTag("calendar-cell-2026-11-05").performClick()
        compose.onNodeWithTag("calendar-view-day").assertIsSelected()
        compose.onNodeWithTag("calendar-title").assertTextEquals("Thursday 5 November")
    }

    @Test fun aSessionIsMarkedFromItsSheet() {
        show()
        compose.onNodeWithTag("calendar-ev-2").assertContentDescriptionEquals("Physique, ${t(at("2026-11-04", 10))}–${t(at("2026-11-04", 12))}, unmarked")
        compose.onNodeWithTag("calendar-ev-2").performClick()
        compose.onNodeWithText("Overlaps Algorithmique").assertExists()
        compose.onNodeWithTag("calendar-status-present").performClick()
        compose.runOnIdle { assertEquals(PaletteAction.SessionStatus(2, "present"), sent.single()) }
        compose.onNodeWithText("Note, tag and catch-up").performClick()
        compose.runOnIdle { assertEquals("session/2", routes.single()) }
    }

    @Test fun anEmptySlotAddsAPlannedToDoOrADeadline() {
        show()
        compose.onNodeWithTag("calendar-col-2026-11-04").performTouchInput { click(Offset(centerX, yAt(14))) }
        compose.onNodeWithTag("calendar-sel").assertExists()
        compose.onNodeWithText("Wednesday 4 Nov · ${t(at("2026-11-04", 14))}–${t(at("2026-11-04", 15))}").assertExists()
        compose.onNodeWithTag("calendar-new-title").performTextInput("Revise")
        compose.onNodeWithTag("calendar-new-add").performClick()
        idle { actions.created != null }
        val body = actions.created!!
        assertEquals(at("2026-11-04", 14), body["planned_at"]!!.jsonPrimitive.long)
        assertEquals(60, body["planned_minutes"]!!.jsonPrimitive.int)
        assertEquals(60, body["effort_minutes"]!!.jsonPrimitive.int)
        assertEquals("2026-11-04", body["due_date"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["due_time"])
        assertEquals("Europe/Paris", body["tz"]!!.jsonPrimitive.content)
        assertEquals(listOf("Planned “Revise”."), actions.messages)

        actions.created = null
        compose.onNodeWithTag("calendar-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -10_000f) }
        compose.onNodeWithTag("calendar-col-2026-11-05").performTouchInput { click(Offset(centerX, yAt(9, 20))) }
        compose.onNodeWithTag("calendar-new-title").performTextInput("Hand in")
        compose.onNodeWithTag("calendar-new-due").performClick()
        idle { actions.created != null }
        assertEquals("09:15", actions.created!!["due_time"]!!.jsonPrimitive.content)
        assertEquals(1440, actions.created!!["remind_minutes"]!!.jsonPrimitive.int)
        assertNull(actions.created!!["planned_at"])
    }

    @Test fun aToDoIsPlacedByTappingItThenATime() {
        show()
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasTestTag("calendar-todo-12"))
        compose.onNodeWithTag("calendar-todo-12").performClick()
        compose.runOnIdle { assertEquals(12L, ui.placing) }
        compose.onNodeWithTag("calendar-placing").assertExists()
        // Back to the top of the grid, where 10:00 is in view.
        compose.onNodeWithTag("calendar-list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -10_000f) }
        compose.onNodeWithTag("calendar-col-2026-11-05").performTouchInput { click(Offset(centerX, yAt(10))) }
        idle { actions.calls.isNotEmpty() }
        assertEquals("plan 12 ${at("2026-11-05", 10)} 90", actions.calls.single())
        assertNull(ui.placing)
    }

    @Test fun aPlannedBlockHasLengthMoveAndUnplan() {
        show()
        compose.onNodeWithTag("calendar-plan-10").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick)).performClick()
        compose.onNodeWithText("Thursday 5 November, ${t(at("2026-11-05", 16, 30))}–${t(at("2026-11-05", 17, 30))}").assertExists()
        compose.onNodeWithTag("calendar-plan-length").performClick()
        compose.onNodeWithTag("calendar-length-30").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("plan 10 ${at("2026-11-05", 16, 30)} 30", actions.calls.removeAt(0))
        compose.onNodeWithTag("calendar-plan-focus").performClick()
        compose.runOnIdle { assertEquals("focus:10", routes.single()) }
        compose.onNodeWithTag("calendar-plan-10").performClick()
        compose.onNodeWithTag("calendar-plan-unplan").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("plan 10 null null", actions.calls.single())
        compose.onNodeWithTag("calendar-plan-10").performClick()
        compose.onNodeWithTag("calendar-plan-move").performClick()
        compose.runOnIdle { assertEquals(10L, ui.placing) }
    }

    @Test fun longPressDragMovesABlockAndTheGripResizesIt() {
        show()
        compose.onNodeWithTag("calendar-plan-10").performTouchInput {
            down(center); advanceEventTime(1_000); moveBy(Offset(0f, 22f)); moveBy(Offset(0f, 22f)); up()
        }
        idle { actions.calls.isNotEmpty() }
        assertEquals("one hour later", "plan 10 ${at("2026-11-05", 17, 30)} 60", actions.calls.removeAt(0))
        // Long-press on the bottom edge, then drag down 22 px (half an hour).
        compose.onNodeWithTag("calendar-plan-10").performTouchInput { down(bottomCenter - Offset(0f, 3f)); advanceEventTime(1_000); moveBy(Offset(0f, 12f)); moveBy(Offset(0f, 10f)); up() }
        idle { actions.calls.isNotEmpty() }
        assertEquals("half an hour longer", "plan 10 ${at("2026-11-05", 16, 30)} 90", actions.calls.single())
    }

    @Test fun planMyWeekProposesThenKeeps() {
        show()
        compose.onNodeWithTag("calendar-plan").performClick()
        compose.onNodeWithTag("calendar-proposal").assertExists()
        compose.onNodeWithText("Planned 2 to-dos in your free time, each before it's due.").assertExists()
        val ids = ui.proposals!!.map { it.id }
        // Tapping a proposal leaves it out.
        compose.onNodeWithTag("calendar-ghost-${ids.first()}").performClick()
        compose.runOnIdle { assertEquals(ids.drop(1), ui.proposals!!.map { it.id }) }
        compose.onNodeWithTag("calendar-keep").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("keep ${ids.drop(1)}", actions.calls.single())
        assertNull(ui.proposals)
        assertEquals(listOf("Planned 1 to-do."), actions.messages)
    }

    @Test fun layersAndPaletteRequests() {
        show()
        compose.onNodeWithContentDescription("Due ${t(at("2026-11-06", 18))}: Planned report, Read chapter").assertExists()
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasTestTag("calendar-layer-deadlines"))
        compose.onNodeWithTag("calendar-layer-deadlines").performClick()
        compose.onNodeWithContentDescription("Due ${t(at("2026-11-06", 18))}: Planned report, Read chapter").assertDoesNotExist()
        compose.runOnIdle { pending = PaletteAction.OpenForm("calendar-view", input = "month") }
        idle { pending == null }
        compose.onNodeWithTag("calendar-view-month").assertIsSelected()
        compose.runOnIdle { pending = PaletteAction.OpenForm("calendar-show", input = at("2026-11-20", 9).toString()) }
        idle { pending == null }
        compose.onNodeWithTag("calendar-view-3day").assertIsSelected()
        compose.onNodeWithTag("calendar-day-2026-11-20").assertExists()
        compose.runOnIdle { pending = PaletteAction.OpenForm("calendar-plan") }
        idle { pending == null }
        compose.onNodeWithTag("calendar-proposal").assertExists()
    }

    @Test fun nothingToPlanSaysSo() {
        show(calData().copy(homework = emptyList()))
        compose.onNodeWithTag("calendar-plan").performClick()
        compose.runOnIdle { assertEquals(listOf("Every open to-do already has a time."), actions.messages) }
        compose.onNodeWithTag("calendar-list").performScrollToNode(hasText("Every open to-do has a time. Tap an empty slot to add one."))
    }

    @Test fun theViewAndLayersOutliveTheSession() {
        val dir = java.nio.file.Files.createTempDirectory("uiprefs").toFile()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        try {
            val prefs = com.knhdixh.attendance80.data.UiPrefs(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("ui.preferences_pb") })
            var first by androidx.compose.runtime.mutableStateOf(true)
            compose.setContent { Attendance80Theme { androidx.compose.runtime.key(first) {
                Shell(ShellState(data = calData(), session = "a"), {}, {}, {}, initialRoute = "calendar", now = calNow, zone = calZone, uiPrefs = prefs)
            } } }
            compose.onNodeWithTag("calendar-view-week").performClick()
            idle { kotlinx.coroutines.runBlocking { prefs.calendar(calData().user.email!!).first() }?.view == "week" }
            first = false // a new session: nothing is remembered in the screen itself
            idle { runCatching { compose.onNodeWithTag("calendar-view-week").assertIsSelected() }.isSuccess }
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun shellOpensTheCalendar() {
        compose.setContent { Attendance80Theme { Shell(ShellState(data = calData(), session = "a"), {}, {}, {}, initialRoute = "calendar", now = calNow, zone = calZone) } }
        compose.onNodeWithTag("calendar-list").assertExists()
        compose.onNodeWithTag("shell-title").assertTextEquals("Calendar")
    }
}

// Native graphics: the bottom panel's top-rounded clip is hit-tested with Path.contains, which legacy Robolectric
// graphics doesn't implement, so touches on the panel would be lost in the test only.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class CalendarTabletUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun opensOnTheWeekWithTheTrayBeside() {
        compose.setContent { Attendance80Theme { CalendarScreen(calData(), calNow, calZone, CalendarUi(), {}, NoCalendarActions, {}, {}, {}) } }
        compose.onNodeWithTag("calendar-view-week").assertIsSelected()
        compose.onNodeWithTag("calendar-day-2026-11-02").assertExists()
        compose.onNodeWithTag("calendar-tray").assertIsDisplayed()
    }
}
