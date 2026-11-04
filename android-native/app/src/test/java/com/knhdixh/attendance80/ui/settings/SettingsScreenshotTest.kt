package com.knhdixh.attendance80.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.net.PersonalTokenDto
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class SettingsScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun lightTop() = capture(false, 1f, "top")
    @Test fun darkTop() = capture(true, 1f, "top")
    @Test fun lightTop2() = capture(false, 2f, "top")
    @Test fun darkTop2() = capture(true, 2f, "top")
    @Test fun lightCalendars() = capture(false, 1f, "calendars")
    @Test fun darkCalendars() = capture(true, 1f, "calendars")
    @Test fun lightAdd() = capture(false, 1f, "add")
    @Test fun darkAdd() = capture(true, 1f, "add")
    @Test fun lightAccess() = capture(false, 1f, "access")
    @Test fun darkAccess() = capture(true, 1f, "access")
    @Test fun lightFeeds() = capture(false, 1f, "feeds")
    @Test fun darkFeeds() = capture(true, 1f, "feeds")
    @Test fun lightDanger() = capture(false, 1f, "danger")
    @Test fun darkDanger() = capture(true, 1f, "danger")
    @Test fun lightDanger2() = capture(false, 2f, "danger")
    @Test fun lightNotifications() = capture(false, 1f, "notifications")
    @Test fun darkNotifications() = capture(true, 1f, "notifications")
    @Test fun lightNotifications2() = capture(false, 2f, "notifications")
    private fun capture(dark: Boolean, scale: Float, kind: String) {
        val data = settingsData()
        val actions = FakeSettingsActions(listOf(PersonalTokenDto(3, "MacBook Claude Code", "write", settingsNow - 86400000L * 3, settingsNow - 3600000)))
        // Task reminders on, and the daily digest on with its time.
        if (kind == "notifications") actions.device.value = com.knhdixh.attendance80.notify.NotifyChoices(tasks = true, digestAt = "08:00")
        ui.setContent { val density = LocalDensity.current; CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = data, session = "test"), {}, {}, {}, initialRoute = "settings", now = settingsNow, zone = settingsZone, settingsActions = actions) }
        } }
        val list = ui.onNodeWithTag("settings-list")
        when (kind) {
            "notifications" -> list.performScrollToNode(hasTestTag("notify-digest-time"))
            "calendars" -> list.performScrollToNode(hasTestTag("calendar-2"))
            "add" -> { list.performScrollToNode(hasTestTag("calendar-add-open")); ui.onNodeWithTag("calendar-add-open").performClick(); list.performScrollToNode(hasTestTag("calendar-link-url")) }
            "access" -> list.performScrollToNode(hasTestTag("token-3"))
            "feeds" -> list.performScrollToNode(hasTestTag("feed-homework"))
            "danger" -> list.performScrollToNode(hasTestTag("settings-delete-account"))
        }
        ui.waitForIdle()
        ui.onRoot().captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_settings_${kind}_${scale.toInt()}.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneSettingsScreenshotTest : SettingsScreenshotTest() { override val device = "phone411x891" }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletSettingsScreenshotTest : SettingsScreenshotTest() { override val device = "tablet840x1000" }
