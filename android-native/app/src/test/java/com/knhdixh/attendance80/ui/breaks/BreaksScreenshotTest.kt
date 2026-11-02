package com.knhdixh.attendance80.ui.breaks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class BreaksScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun lightMain() = capture(false, 1f, "main")
    @Test fun darkMain() = capture(true, 1f, "main")
    @Test fun lightMain2() = capture(false, 2f, "main")
    @Test fun darkMain2() = capture(true, 2f, "main")
    @Test fun lightPicked() = capture(false, 1f, "picked")
    @Test fun darkPicked() = capture(true, 1f, "picked")
    @Test fun lightFail() = capture(false, 1f, "fail")
    @Test fun darkFail() = capture(true, 1f, "fail")
    @Test fun lightSaved() = capture(false, 1f, "saved")
    @Test fun darkSaved() = capture(true, 1f, "saved")
    @Test fun lightEmpty() = capture(false, 1f, "empty")
    @Test fun darkEmpty() = capture(true, 1f, "empty")
    private fun capture(dark: Boolean, scale: Float, kind: String) {
        val data = if (kind == "empty") breaksEmpty() else breaksData()
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = data, session = "test"), {}, {}, {}, initialRoute = "breaks", now = breaksNow, zone = breaksZone) }
        } }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("breaks-list").fetchSemanticsNodes().isNotEmpty() }
        when (kind) {
            "picked", "fail" -> {
                ui.onNodeWithTag(if (kind == "picked") "break-day-2026-11-04" else "break-day-2026-11-12").performClick()
                ui.onNodeWithTag(if (kind == "picked") "break-day-2026-11-06" else "break-day-2026-11-13").performClick()
                // Wait for the verdict of this very range, not just any verdict.
                val range = if (kind == "picked") "2026-11-04 to 2026-11-06" else "2026-11-12 to 2026-11-13"
                ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("break-result") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, range)).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("breaks-list").performScrollToNode(hasTestTag("break-verdict"))
            }
            "saved" -> ui.onNodeWithTag("breaks-list").performScrollToNode(hasTestTag("saved-break-5"))
            "empty" -> ui.onNodeWithTag("breaks-list").performScrollToNode(hasText("No safe 7-day break left in your calendar."))
        }
        ui.onRoot().captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_breaks_${kind}_${scale.toInt()}.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneBreaksScreenshotTest : BreaksScreenshotTest() { override val device = "phone411x891" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletBreaksScreenshotTest : BreaksScreenshotTest() { override val device = "tablet840x1000" }
