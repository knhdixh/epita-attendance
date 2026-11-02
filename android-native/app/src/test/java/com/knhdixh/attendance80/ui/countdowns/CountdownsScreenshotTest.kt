package com.knhdixh.attendance80.ui.countdowns

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
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

abstract class CountdownsScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun lightList() = capture(false, 1f, "list")
    @Test fun darkList() = capture(true, 1f, "list")
    @Test fun lightList2() = capture(false, 2f, "list")
    @Test fun lightQuick() = capture(false, 1f, "quick")
    @Test fun lightEditor() = capture(false, 1f, "editor")
    @Test fun darkEditor() = capture(true, 1f, "editor")
    @Test fun lightEditor2() = capture(false, 2f, "editor")
    @Test fun lightEmpty() = capture(false, 1f, "empty")
    private fun capture(dark: Boolean, scale: Float, kind: String) {
        val data = if (kind == "empty") cdData().copy(countdowns = emptyList(), events = emptyList()) else cdData()
        val route = if (kind == "editor") "countdowns/1" else "countdowns"
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = data, session = "test"), {}, {}, {}, initialRoute = route, now = cdNow, zone = cdZone) }
        } }
        if (kind == "quick") {
            ui.onNodeWithTag("countdowns-list").performScrollToNode(hasTestTag("countdowns-add"))
            ui.onNodeWithTag("countdowns-add").performClick()
            ui.onNodeWithTag("countdowns-quick-input").performTextInput("Algo exam 12 dec 9:00")
        }
        ui.waitForIdle()
        ui.onRoot().captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_countdowns_${kind}_${scale.toInt()}.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneCountdownsScreenshotTest : CountdownsScreenshotTest() { override val device = "phone411x891" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletCountdownsScreenshotTest : CountdownsScreenshotTest() { override val device = "tablet840x1000" }
