package com.knhdixh.attendance80.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Width >= 600dp: Sessions, Homework and Courses show the list and the opened item side by side. */
abstract class TwoPaneTest {
    abstract val wide: Boolean
    @get:Rule val compose = createComposeRule()
    private fun show(route: String) = compose.setContent { Attendance80Theme { Shell(ShellState(data = screenshotData()), {}, {}, {}, initialRoute = route) } }

    private fun listAlone(route: String) {
        show(route)
        if (wide) { compose.onNodeWithTag("pane-list").assertExists(); compose.onNodeWithTag("pane-placeholder").assertExists() }
        else { compose.onNodeWithTag("pane-list").assertDoesNotExist(); compose.onNodeWithTag("pane-placeholder").assertDoesNotExist() }
    }
    @Test fun sessionsListIsPairedOnTablets() = listAlone("sessions")
    @Test fun homeworkListIsPairedOnTablets() = listAlone("homework")
    @Test fun coursesListIsPairedOnTablets() = listAlone("courses")
    @Test fun anOpenedSessionShowsBesideItsList() {
        show("session/1")
        if (wide) { compose.onNodeWithTag("pane-list").assertExists(); compose.onNodeWithTag("pane-detail").assertExists(); compose.onNodeWithTag("pane-placeholder").assertDoesNotExist() }
        else compose.onNodeWithTag("pane-list").assertDoesNotExist()
    }
    @Test fun anOpenedCourseShowsBesideItsList() {
        show("course/1")
        if (wide) { compose.onNodeWithTag("pane-list").assertExists(); compose.onNodeWithTag("pane-placeholder").assertDoesNotExist() }
        else compose.onNodeWithTag("pane-list").assertDoesNotExist()
    }
    @Test fun anOpenedHomeworkEditsBesideItsList() {
        show("homework/1")
        if (wide) { compose.onNodeWithTag("pane-list").assertExists(); compose.onNodeWithTag("pane-placeholder").assertDoesNotExist() }
        else compose.onNodeWithTag("pane-list").assertDoesNotExist()
    }
    @Test fun addingHomeworkStaysAFullPage() {
        show("homework/new")
        compose.onNodeWithTag("pane-list").assertDoesNotExist()
    }
    @Test fun tappingAnItemOpensItBesideTheListWithoutLeavingTheScreen() {
        if (!wide) return
        show("homework")
        compose.onAllNodesWithText("Edit").onFirst().performClick()
        compose.onNodeWithTag("pane-placeholder").assertDoesNotExist()
        compose.onNodeWithTag("pane-list").assertExists()
        compose.onNodeWithTag("shell-title").assertTextEquals("Homework")
    }
}
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneTwoPaneTest : TwoPaneTest() { override val wide = false }
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1200dp-mdpi")
class TabletTwoPaneTest : TwoPaneTest() { override val wide = true }
