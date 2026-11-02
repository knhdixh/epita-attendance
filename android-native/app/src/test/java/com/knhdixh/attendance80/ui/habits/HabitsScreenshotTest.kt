package com.knhdixh.attendance80.ui.habits

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

abstract class HabitsScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun lightList() = capture(false, 1f, "list")
    @Test fun darkList() = capture(true, 1f, "list")
    @Test fun lightList2() = capture(false, 2f, "list")
    @Test fun lightGrid() = capture(false, 1f, "grid")
    @Test fun darkGrid() = capture(true, 1f, "grid")
    @Test fun lightQuick() = capture(false, 1f, "quick")
    @Test fun lightForm() = capture(false, 1f, "form")
    @Test fun darkForm() = capture(true, 1f, "form")
    @Test fun lightDetail() = capture(false, 1f, "detail")
    @Test fun darkDetail() = capture(true, 1f, "detail")
    @Test fun lightDetail2() = capture(false, 2f, "detail")
    @Test fun lightEmpty() = capture(false, 1f, "empty")
    private fun capture(dark: Boolean, scale: Float, kind: String) {
        val data = if (kind == "empty") hbData().copy(habits = com.knhdixh.attendance80.net.HabitsDto()) else hbData()
        val route = if (kind == "detail") "habits/2" else "habits"
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = data, session = "test"), {}, {}, {}, initialRoute = route, now = hbNow, zone = hbZone) }
        } }
        when (kind) {
            "grid" -> ui.onNodeWithTag("habits-view-grid").performClick()
            "quick" -> { ui.onNodeWithTag("habits-add").performClick(); ui.onNodeWithTag("habits-quick-input").performTextInput("💧 Water 8 glasses a day") }
            "form" -> ui.onNodeWithContentDescription("Edit Gym").performClick()
        }
        ui.waitForIdle()
        ui.onRoot().captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_habits_${kind}_${scale.toInt()}.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneHabitsScreenshotTest : HabitsScreenshotTest() { override val device = "phone411x891" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletHabitsScreenshotTest : HabitsScreenshotTest() { override val device = "tablet840x1000" }
