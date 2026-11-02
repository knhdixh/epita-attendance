package com.knhdixh.attendance80.ui.focus

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.data.FocusHistoryStatus
import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId

abstract class FocusScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun lightMain() = capture(false, 1f, "main")
    @Test fun darkMain() = capture(true, 1f, "main")
    @Test fun lightMain2() = capture(false, 2f, "main")
    @Test fun darkMain2() = capture(true, 2f, "main")
    @Test fun lightEmpty() = capture(false, 1f, "empty")
    @Test fun darkEmpty() = capture(true, 1f, "empty")
    @Test fun lightYear() = capture(false, 1f, "year")
    @Test fun darkYear() = capture(true, 1f, "year")
    @Test fun lightYear2() = capture(false, 2f, "year")
    @Test fun darkYear2() = capture(true, 2f, "year")
    @Test fun lightSettings() = capture(false, 1f, "settings")
    @Test fun darkSettings() = capture(true, 1f, "settings")
    @Test fun lightSettings2() = capture(false, 2f, "settings")
    @Test fun darkSettings2() = capture(true, 2f, "settings")
    @Test fun lightSettingsBottom() = capture(false, 2f, "bottom")
    @Test fun darkSettingsBottom() = capture(true, 2f, "bottom")
    @Test fun lightDelete() = capture(false, 1f, "delete")
    @Test fun darkDelete() = capture(true, 1f, "delete")
    @Test fun lightError() = capture(false, 1f, "error")
    @Test fun darkError() = capture(true, 1f, "error")
    private fun capture(dark: Boolean, scale: Float, kind: String) {
        val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
        val base = screenshotData(); val data = base.copy(focus = base.focus.copy(since = if (kind == "error") now else 0, sessions = if (kind == "empty") emptyList() else base.focus.sessions))
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = data, session = "test"), {}, {}, {}, initialRoute = "focus", now = now, zone = ZoneId.of("Europe/Paris"), focusHistory = FocusHistoryStatus("test", now, failed = true)) }
        } }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("focus-list").fetchSemanticsNodes().isNotEmpty() }
        when (kind) {
            "year" -> { ui.onNodeWithTag("focus-list").performScrollToNode(hasTestTag("focus-heatmap")); ui.onNodeWithTag("focus-heatmap").performScrollTo() }
            "settings" -> ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Timer settings"))
            "bottom" -> { ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Save")); ui.onNodeWithText("Save").performScrollTo() }
            "delete" -> { ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Recent sessions")); ui.onAllNodesWithText("Delete").onFirst().performScrollTo().performClick() }
            "error" -> ui.onNodeWithTag("focus-list").performScrollToNode(hasText("Couldn’t load the year."))
        }
        val target = if (kind == "delete") ui.onNode(isDialog()) else ui.onRoot()
        target.captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_focus_${kind}_${scale.toInt()}.png")
    }
}
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneFocusScreenshotTest : FocusScreenshotTest() { override val device = "phone411x891" }
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletFocusScreenshotTest : FocusScreenshotTest() { override val device = "tablet840x1000" }
