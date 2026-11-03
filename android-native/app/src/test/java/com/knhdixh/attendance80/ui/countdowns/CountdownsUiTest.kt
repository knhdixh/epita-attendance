package com.knhdixh.attendance80.ui.countdowns

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.logic.Countdowns
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
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
class CountdownsUiTest {
    @get:Rule val compose = createComposeRule()
    private var ui by mutableStateOf(CountdownsUi())
    private val actions = FakeCountdownActions()
    private fun show(data: AppData = cdData(), pending: Long? = null) {
        compose.setContent { Attendance80Theme { CountdownsScreen(data, cdNow, cdZone, ui, { ui = it }, actions, pending) } }
    }
    private fun idle(cond: () -> Boolean) = compose.waitUntil(5_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); cond() }

    @Test fun sectionsFiguresAndPast() {
        show()
        compose.onNodeWithText("Coming up").assertExists()
        compose.onNodeWithTag("countdown-fig-1").assertTextEquals("45 days")
        compose.onNodeWithText("Book the train").assertExists()
        compose.onNodeWithText("Counting up").assertExists()
        compose.onNodeWithTag("countdown-fig-3").assertTextEquals("64 days")
        compose.onNodeWithTag("countdown-4").assertDoesNotExist()
        compose.onNodeWithTag("countdowns-list").performScrollToNode(hasTestTag("countdowns-past"))
        compose.onNodeWithTag("countdowns-past").performClick()
        compose.onNodeWithTag("countdowns-list").performScrollToNode(hasTestTag("countdown-4"))
    }

    @Test fun quickAddReadsTheSentence() {
        show()
        compose.onNodeWithTag("countdowns-list").performScrollToNode(hasTestTag("countdowns-add"))
        compose.onNodeWithTag("countdowns-add").performClick()
        compose.onNodeWithTag("countdowns-quick-input").performTextInput("nothing")
        compose.onNodeWithText("Add a date: “12 dec”, “friday 9am”, “in 3 weeks”…").assertExists()
        compose.onNodeWithTag("countdowns-quick-add").performClick()
        compose.runOnIdle { assertEquals(listOf("Add a date, like “12 dec”, “friday 9am” or “in 3 weeks”."), actions.messages) }
        compose.onNodeWithTag("countdowns-quick-input").performTextReplacement("Algo exam 12 dec 9:00")
        val parsed = Countdowns.parseCountdown("Algo exam 12 dec 9:00", cdNow, cdZone)!!
        val v = Countdowns.view(Countdowns.Countdown(parsed.at, parsed.all_day != 0, parsed.repeat, "auto", parsed.after), cdNow, cdZone)
        compose.onNode(hasText("${v.parts} to go") and hasAnyAncestor(hasTestTag("countdowns-quick-chips"))).assertExists()
        compose.onNodeWithTag("countdowns-quick-add").performClick()
        idle { actions.calls.isNotEmpty() }
        val body = actions.body!!
        assertEquals("Algo exam", body["title"]!!.jsonPrimitive.content)
        assertEquals(cdAt("2026-12-12", 9), body["at"]!!.jsonPrimitive.long)
        assertFalse(body["all_day"]!!.jsonPrimitive.boolean)
        assertTrue(actions.messages.last().startsWith("Added “Algo exam”: "))
        // The wording counts from the shell's clock (cdNow), not the wall clock.
        assertTrue(actions.messages.last(), actions.messages.last().contains(v.parts))
    }

    @Test fun editorFineTunesWithAPreviewAndSaves() {
        show()
        compose.onNodeWithText("Trip home").performClick()
        compose.onNodeWithTag("countdown-form-title").assertTextContains("Trip home")
        compose.onNodeWithTag("countdowns-list").performScrollToNode(hasTestTag("countdown-form-units-weeks"))
        compose.onNodeWithTag("countdown-form-units-weeks").performClick()
        compose.onNodeWithTag("countdowns-list").performScrollToNode(hasTestTag("countdown-form-preview"))
        compose.onNodeWithTag("countdown-form-preview").assert(hasAnyDescendant(hasText("6 w 3 d")))
        compose.onNodeWithTag("countdowns-list").performScrollToNode(hasTestTag("countdown-form-save"))
        compose.onNodeWithTag("countdown-form-save").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("update 1", actions.calls.single())
        val body = actions.body!!
        assertEquals("weeks", body["units"]!!.jsonPrimitive.content)
        assertEquals(cdAt("2026-10-01"), body["start_at"]!!.jsonPrimitive.long)
        assertTrue(body["pinned"]!!.jsonPrimitive.boolean)
        assertEquals("Book the train", body["note"]!!.jsonPrimitive.content)
        assertEquals(listOf("Countdown saved."), actions.messages)
    }

    @Test fun pinAndDelete() {
        show()
        compose.onNodeWithTag("countdown-pin-2").performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("update 2", actions.calls.removeAt(0))
        assertEquals(buildJsonObject { put("pinned", true) }, actions.body)
        assertEquals("Pinned “Gym” to Today and the sidebar.", actions.messages.removeAt(0))
        compose.onNodeWithContentDescription("Delete Gym").performClick()
        compose.onNodeWithText("Delete “Gym”?").assertExists()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        idle { actions.calls.isNotEmpty() }
        assertEquals("delete 2", actions.calls.single())
        assertEquals(listOf("Deleted “Gym”."), actions.messages)
    }

    @Test fun examsFromTheTimetableAreOfferedAndCanBeDismissed() {
        show()
        compose.onNodeWithTag("countdowns-list").performScrollToNode(hasTestTag("countdown-exam-1"))
        compose.onNodeWithTag("countdown-exam-1").performClick()
        idle { actions.calls.isNotEmpty() }
        val body = actions.body!!
        assertEquals("Algorithmique exam", body["title"]!!.jsonPrimitive.content)
        assertEquals(1, body["event_id"]!!.jsonPrimitive.int)
        assertTrue(body["pinned"]!!.jsonPrimitive.boolean)
        assertEquals("red", body["color"]!!.jsonPrimitive.content)
        compose.onNodeWithContentDescription("Don’t suggest Algorithmique exam").performClick()
        compose.runOnIdle { assertEquals(listOf(1L), ui.dismissed) }
        compose.onNodeWithTag("countdown-exam-1").assertDoesNotExist()
    }

    @Test fun aLinkFromTodayOpensItsEditor() {
        show(pending = 3)
        compose.onNodeWithTag("countdown-form-title").assertTextContains("No coffee")
    }

    @Test fun emptySaysHowToStart() {
        show(cdData().copy(countdowns = emptyList(), events = emptyList()))
        compose.onNodeWithText("Count down to what matters, or count up from when something began.").assertExists()
    }

    @Test fun shellRoutes() {
        compose.setContent { Attendance80Theme { Shell(ShellState(data = cdData(), session = "a"), {}, {}, {}, initialRoute = "countdowns/new", now = cdNow, zone = cdZone) } }
        compose.onNodeWithTag("countdowns-quick-input").assertExists()
    }
}

class CountdownsModelTest {
    @Test fun suggestionsBodiesAndFigures() {
        val d = cdData()
        assertEquals(listOf(1L to "Algorithmique exam"), suggestions(d, emptyList(), cdNow, cdZone).map { it.first.id to it.second })
        assertTrue(suggestions(d, listOf(1L), cdNow, cdZone).isEmpty())
        assertTrue("already linked", suggestions(d.copy(countdowns = d.countdowns + d.countdowns[0].copy(id = 8, event_id = 1)), emptyList(), cdNow, cdZone).isEmpty())
        assertEquals("""{"title":"Exam","at":5,"all_day":false,"repeat":"none","units":"auto","after":"up","start_at":null,"color":"blue","pinned":true,"note":"","event_id":3}""",
            draftBody(Draft(" Exam ", 5, false, pinned = true, eventId = 3)).toString())
        val v = Countdowns.view(d.countdowns[0], cdNow, cdZone)
        assertEquals("45 days", figure(v).text); assertEquals("to go", direction(v))
        assertEquals("6 w 3 d", figure(Countdowns.view(d.countdowns[0].copy(units = "weeks"), cdNow, cdZone)).text)
        assertEquals("Sat 19 Dec", whenLabel(cdAt("2026-12-19"), true, cdNow, cdZone))
        assertEquals("Fri 1 Jan 2027", whenLabel(cdAt("2027-01-01"), true, cdNow, cdZone))
    }
}
