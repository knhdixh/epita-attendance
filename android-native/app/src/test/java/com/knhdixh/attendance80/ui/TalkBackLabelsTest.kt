package com.knhdixh.attendance80.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** TalkBack reads a button from its text or description. Every button on every top-level screen has one. */
abstract class TalkBackLabelsTest {
    @get:Rule val compose = createComposeRule()

    private fun check(route: String) {
        compose.setContent { Attendance80Theme { Shell(ShellState(data = screenshotData()), {}, {}, {}, initialRoute = route, now = 1793600000000, zone = java.time.ZoneId.of("Europe/Paris")) } }
        // The merged tree is what a screen reader sees: a button with no text and no description in it is unlabelled.
        val bad = compose.onAllNodes(hasClickAction() and hasRole(Role.Button)).fetchSemanticsNodes().filter { node ->
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
            text.isBlank() && description.isBlank()
        }
        assertTrue("$route has unlabelled buttons: ${bad.map { it.config }}", bad.isEmpty())
    }
    private fun hasRole(role: Role) = SemanticsMatcher("has role $role") { it.config.getOrNull(SemanticsProperties.Role) == role }

    @Test fun today() = check("today")
    @Test fun attendance() = check("attendance")
    @Test fun upcoming() = check("upcoming")
    @Test fun sessions() = check("sessions")
    @Test fun homework() = check("homework")
    @Test fun courses() = check("courses")
    @Test fun calendar() = check("calendar")
    @Test fun breaks() = check("breaks")
    @Test fun habits() = check("habits")
    @Test fun countdowns() = check("countdowns")
    @Test fun focus() = check("focus")
    @Test fun settings() = check("settings")
}
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneTalkBackLabelsTest : TalkBackLabelsTest()
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletTalkBackLabelsTest : TalkBackLabelsTest()
