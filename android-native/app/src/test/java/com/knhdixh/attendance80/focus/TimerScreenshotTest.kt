package com.knhdixh.attendance80.focus

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.logic.*
import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.ui.shell.FocusTimerState
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneId

abstract class TimerScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun lightIdle() = capture(false, 1f, "idle")
    @Test fun darkIdle() = capture(true, 1f, "idle")
    @Test fun lightRunning() = capture(false, 1f, "running")
    @Test fun darkRunning() = capture(true, 1f, "running")
    @Test fun lightPaused() = capture(false, 1f, "paused")
    @Test fun darkPaused() = capture(true, 1f, "paused")
    @Test fun lightBreak() = capture(false, 1f, "break")
    @Test fun lightRunning2() = capture(false, 2f, "running")
    @Test fun darkRunning2() = capture(true, 2f, "running")
    @Test fun lightSheet() = capture(false, 1f, "sheet")
    @Test fun darkSheet() = capture(true, 1f, "sheet")
    @Test fun lightSheet2() = capture(false, 2f, "sheet")

    private fun capture(dark: Boolean, scale: Float, kind: String) {
        val now = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
        val base = screenshotData(); val data = base.copy(focus = base.focus.copy(since = 0))
        val settings = FocusSettings(tags = listOf("study", "lab"), tasks = listOf("Thesis: literature review"))
        val target = FocusTarget(homework_id = 1, course_id = 1, label = "Maths Exercise 4", tags = listOf("study"))
        val running = FocusTimer(phase = "focus", next = "focus", run = "r", startedAt = now - 8 * 60_000L, endsAt = now + 17 * 60_000L, done = 2, target = target)
        val timer = when (kind) {
            "running", "sheet" -> running
            "paused" -> running.copy(pausedAt = now)
            "break" -> FocusTimer(phase = "short", next = "short", run = "b", startedAt = now - 2 * 60_000L, endsAt = now + 3 * 60_000L, done = 3)
            else -> idle(done = 1)
        }
        val actions = RecordingActions()
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            Attendance80Theme(darkTheme = dark) {
                if (kind == "sheet") com.knhdixh.attendance80.focus.QuickFocusSheet(timer, settings, data, emptyList(), { now }, actions, {})
                else Shell(ShellState(data = data, session = "test"), {}, {}, {}, initialRoute = "focus", now = now, zone = ZoneId.of("Europe/Paris"),
                    focusTimer = FocusTimerState(timer, settings, emptyList()), timerActions = actions, timerNow = { now })
            }
        } }
        val tag = if (kind == "sheet") "quick-focus" else "timer-panel"
        ui.waitUntil(10000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        ui.onRoot().captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_timer_${kind}_${scale.toInt()}.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneTimerScreenshotTest : TimerScreenshotTest() { override val device = "phone411x891" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletTimerScreenshotTest : TimerScreenshotTest() { override val device = "tablet840x1000" }
