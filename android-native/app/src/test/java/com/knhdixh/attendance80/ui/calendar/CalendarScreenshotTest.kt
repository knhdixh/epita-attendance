package com.knhdixh.attendance80.ui.calendar

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
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

abstract class CalendarScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun lightMain() = capture(false, 1f, "main")
    @Test fun darkMain() = capture(true, 1f, "main")
    @Test fun lightMain2() = capture(false, 2f, "main")
    @Test fun lightMonth() = capture(false, 1f, "month")
    @Test fun darkMonth() = capture(true, 1f, "month")
    @Test fun lightDay() = capture(false, 1f, "day")
    @Test fun lightProposal() = capture(false, 1f, "proposal")
    @Test fun darkProposal() = capture(true, 1f, "proposal")
    @Test fun lightTray() = capture(false, 1f, "tray")
    @Test fun lightSession() = capture(false, 1f, "session")
    @Test fun darkNew() = capture(true, 1f, "new")
    private fun capture(dark: Boolean, scale: Float, kind: String) {
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = calData(), session = "test"), {}, {}, {}, initialRoute = "calendar", now = calNow, zone = calZone) }
        } }
        when (kind) {
            "month" -> ui.onNodeWithTag("calendar-view-month").performClick()
            "day" -> ui.onNodeWithTag("calendar-view-day").performClick()
            "proposal" -> ui.onNodeWithTag("calendar-plan").performClick()
            "tray" -> ui.onNodeWithTag("calendar-list").performScrollToNode(hasTestTag("calendar-tray"))
            "session" -> ui.onNodeWithTag("calendar-ev-2").performClick()
            "new" -> ui.onNodeWithTag("calendar-col-2026-11-04").performTouchInput { click(Offset(centerX, (14 - 7) * 44f + 2f)) }
        }
        ui.waitForIdle()
        val name = "src/test/screenshots/${device}_${if (dark) "dark" else "light"}_calendar_${kind}_${scale.toInt()}.png"
        ui.onRoot().captureRoboImage(name)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneCalendarScreenshotTest : CalendarScreenshotTest() { override val device = "phone411x891" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletCalendarScreenshotTest : CalendarScreenshotTest() { override val device = "tablet840x1000" }
