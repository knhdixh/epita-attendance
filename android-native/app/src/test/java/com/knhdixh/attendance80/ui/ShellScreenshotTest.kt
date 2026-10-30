package com.knhdixh.attendance80.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
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

abstract class ShellScreenshotTest {
    abstract val device: String
    @get:Rule val compose = createComposeRule()
    @Test fun light_attendance() = capture("light", "attendance", 1f)
    @Test fun light_homework() = capture("light", "homework", 1f)
    @Test fun light_focus() = capture("light", "focus", 1f)
    @Test fun light_more() = capture("light", "more", 1f)
    @Test fun light_upcoming() = capture("light", "upcoming", 1f)
    @Test fun light_sessions() = capture("light", "sessions", 1f)
    @Test fun light_courses() = capture("light", "courses", 1f)
    @Test fun light_calendar() = capture("light", "calendar", 1f)
    @Test fun light_breaks() = capture("light", "breaks", 1f)
    @Test fun light_habits() = capture("light", "habits", 1f)
    @Test fun light_countdowns() = capture("light", "countdowns", 1f)
    @Test fun light_settings() = capture("light", "settings", 1f)
    @Test fun light_attendance_large() = capture("light", "attendance", 2f)
    @Test fun light_homework_large() = capture("light", "homework", 2f)
    @Test fun light_focus_large() = capture("light", "focus", 2f)
    @Test fun light_more_large() = capture("light", "more", 2f)
    @Test fun dark_attendance() = capture("dark", "attendance", 1f)
    @Test fun dark_homework() = capture("dark", "homework", 1f)
    @Test fun dark_focus() = capture("dark", "focus", 1f)
    @Test fun dark_more() = capture("dark", "more", 1f)
    @Test fun dark_upcoming() = capture("dark", "upcoming", 1f)
    @Test fun dark_sessions() = capture("dark", "sessions", 1f)
    @Test fun dark_courses() = capture("dark", "courses", 1f)
    @Test fun dark_calendar() = capture("dark", "calendar", 1f)
    @Test fun dark_breaks() = capture("dark", "breaks", 1f)
    @Test fun dark_habits() = capture("dark", "habits", 1f)
    @Test fun dark_countdowns() = capture("dark", "countdowns", 1f)
    @Test fun dark_settings() = capture("dark", "settings", 1f)
    @Test fun dark_attendance_large() = capture("dark", "attendance", 2f)
    @Test fun dark_homework_large() = capture("dark", "homework", 2f)
    @Test fun dark_focus_large() = capture("dark", "focus", 2f)
    @Test fun dark_more_large() = capture("dark", "more", 2f)
    private fun capture(theme: String, route: String, scale: Float) {
        val title = route.replaceFirstChar { it.uppercase() }
        var baseline = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Attendance80Theme(darkTheme = theme == "dark") {
                    baseline = rememberTextMeasurer().measure(title, style = MaterialTheme.typography.titleLarge,
                        density = Density(density.density, 1f)).size.height
                    Shell(ShellState(data = screenshotData()), {}, {}, {}, initialRoute = route,
                        now = 1793600000000, zone = java.time.ZoneId.of("Europe/Paris"))
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        if (scale == 2f) {
            val height = compose.onNodeWithTag("shell-title").fetchSemanticsNode().boundsInRoot.height
            assertTrue("200% font must increase title height ($height vs $baseline)", height > baseline)
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/${device}_${theme}_${route}_font${scale.toInt()}.png")
        if (scale == 2f && device.startsWith("phone")) compose.onNodeWithContentDescription("More").performClick()
        if (scale == 2f && device.startsWith("tablet")) compose.onNodeWithTag("nav-settings").performScrollTo().performClick()
    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneShellScreenshotTest : ShellScreenshotTest() { override val device = "phone411x891" }

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletShellScreenshotTest : ShellScreenshotTest() { override val device = "tablet840x1000" }
