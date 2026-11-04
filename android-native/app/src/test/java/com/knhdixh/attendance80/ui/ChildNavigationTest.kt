package com.knhdixh.attendance80.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class ChildNavigationTest {
    abstract val wide: Boolean
    @get:Rule val compose = createComposeRule()
    private fun selected(route: String, parent: String) {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Attendance80Theme { Shell(ShellState(data = screenshotData()), {}, {}, {}, initialRoute = route) } }
        val node = if (wide) compose.onNodeWithTag("nav-$parent", useUnmergedTree = true) else compose.onNode(hasText("Browse") and hasClickAction())
        node.assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        node.assertIsSelected()
    }
    @Test fun sessionDetail() = selected("session/1", "sessions")
    @Test fun courseDetail() = selected("course/1", "courses")
    @Test fun homeworkNew() = selected("homework/new", "homework")
    @Test fun homeworkDetail() = selected("homework/1", "homework")
    @Test fun habitDetail() = selected("habits/1", "habits")
    @Test fun countdownDetail() = selected("countdowns/1", "countdowns")
    @Test fun countdownNew() = selected("countdowns/new", "countdowns")
    @Test fun childSelectionSurvivesRestorationAndBack() {
        val dispatcher = OnBackPressedDispatcher()
        val owner = object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = dispatcher
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
            Attendance80Theme { Shell(ShellState(data = screenshotData()), {}, {}, {}) }
        } }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithTag("search-query").performTextInput("new habit")
        compose.onNodeWithTag("result-new-habit").performClick()
        val nav = if (wide) compose.onNodeWithTag("nav-habits", useUnmergedTree = true) else compose.onNodeWithText("Browse")
        nav.assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        nav.assertIsSelected()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.onNodeWithTag("shell-title").assertTextEquals("Today")
        if (wide) compose.onNodeWithTag("nav-today", useUnmergedTree = true).assertIsSelected() else compose.onAllNodesWithText("Today").onLast().assertIsSelected()
    }
}
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneChildNavigationTest : ChildNavigationTest() { override val wide = false }
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1200dp-mdpi")
class TabletChildNavigationTest : ChildNavigationTest() { override val wide = true }
