package com.knhdixh.attendance80.ui

import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellState
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AuthConfigResponse
import com.knhdixh.attendance80.ui.signin.SignInContent
import com.knhdixh.attendance80.ui.signin.SignInUiState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

abstract class ScreenScreenshotTest {
    abstract val device: String
    @get:Rule val compose = createComposeRule()

    @Test fun light_attendance_action_font1() = attendanceAction("light", 1f)
    @Test fun light_attendance_action_font2() = attendanceAction("light", 2f)
    @Test fun dark_attendance_action_font1() = attendanceAction("dark", 1f)
    @Test fun dark_attendance_action_font2() = attendanceAction("dark", 2f)
    private fun attendanceAction(theme: String, scale: Float) {
        val now = 1793600000000L
        val base = screenshotData()
        val data = base.copy(courses = base.courses + com.knhdixh.attendance80.net.CourseDto(900, "GR1 - Advanced language studies", tracked = false),
            events = base.events.mapIndexed { index, e -> if (index == 0) e.copy(start_at = now - 7200000, end_at = now - 3600000, status = null) else e } +
                (0 until 100).map { base.events.first().copy(id = 1000L + it, start_at = now - 8 * 86400000L, end_at = now - 8 * 86400000L + 3600000L, status = "absent") })
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Attendance80Theme(darkTheme = theme == "dark") { Surface {
                    com.knhdixh.attendance80.ui.attendance.AttendanceScreen(data, now, java.time.ZoneId.of("Europe/Paris"), {}, {})
                } }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Mark 1 as attended").fetchSemanticsNodes().isNotEmpty() }
        compose.onRoot().captureRoboImage("src/test/screenshots/${device}_${theme}_attendance_action_font${scale.toInt()}.png")
        if (scale == 2f) {
            compose.onNodeWithTag("attendance-list").performScrollToNode(hasText("Mark 1 as attended"))
            compose.onRoot().captureRoboImage("src/test/screenshots/${device}_${theme}_attendance_action_button_font2.png")
        }
    }
    @Test fun light_attendance_font1() = attendanceCapture("light", 1f, false, false)
    @Test fun light_attendance_font2() = attendanceCapture("light", 2f, false, false)
    @Test fun dark_attendance_font1() = attendanceCapture("dark", 1f, false, false)
    @Test fun dark_attendance_font2() = attendanceCapture("dark", 2f, false, false)
    @Test fun light_attendance_end_font1() = attendanceCapture("light", 1f, false, true)
    @Test fun light_attendance_end_font2() = attendanceCapture("light", 2f, false, true)
    @Test fun dark_attendance_end_font1() = attendanceCapture("dark", 1f, false, true)
    @Test fun dark_attendance_end_font2() = attendanceCapture("dark", 2f, false, true)
    @Test fun light_attendance_empty() = attendanceCapture("light", 1f, true, false)
    @Test fun dark_attendance_empty() = attendanceCapture("dark", 1f, true, false)
    private fun attendanceCapture(theme: String, scale: Float, empty: Boolean, end: Boolean) {
        val data = if (empty) ApiClient.json.decodeFromString<com.knhdixh.attendance80.net.AppData>(javaClass.getResource("/api/data_empty.json")!!.readText()) else todayScreenshotData()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Attendance80Theme(darkTheme = theme == "dark") {
                    Surface { com.knhdixh.attendance80.ui.attendance.AttendanceScreen(data, 1793600000000, java.time.ZoneId.of("Europe/Paris"), {}, {}) }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("attendance-list").fetchSemanticsNodes().isNotEmpty() }
        if (end) compose.onNodeWithTag("attendance-list").performScrollToNode(hasTestTag("attendance-course-${data.courses.last().id}"))
        compose.onRoot().captureRoboImage("src/test/screenshots/${device}_${theme}_attendance_${if (empty) "empty" else if (end) "end" else "main"}_font${scale.toInt()}.png")
    }
    @Test fun light_today_end_font1() = captureTodayLower("light", 1f, true)
    @Test fun light_today_end_font2() = captureTodayLower("light", 2f, true)
    @Test fun dark_today_end_font1() = captureTodayLower("dark", 1f, true)
    @Test fun dark_today_end_font2() = captureTodayLower("dark", 2f, true)
    @Test fun light_today_lower_font1() = captureTodayLower("light", 1f)
    @Test fun light_today_lower_font2() = captureTodayLower("light", 2f)
    @Test fun dark_today_lower_font1() = captureTodayLower("dark", 1f)
    @Test fun dark_today_lower_font2() = captureTodayLower("dark", 2f)
    private fun captureTodayLower(theme: String, scale: Float, end: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Attendance80Theme(darkTheme = theme == "dark") {
                    Shell(ShellState(data = todayScreenshotData()), {}, {}, {}, now = 1793600000000, zone = java.time.ZoneId.of("Europe/Paris"))
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("today-list").performScrollToNode(if (end) hasText("of class,", substring = true) else hasText("Today's sessions"))
        compose.onRoot().captureRoboImage("src/test/screenshots/${device}_${theme}_today_${if (end) "end" else "lower"}_font${scale.toInt()}.png")
    }
    @Test fun light_signin_font1() = capture("light", "signin", 1f)
    @Test fun light_signin_font2() = capture("light", "signin", 2f)
    @Test fun light_loading_font1() = capture("light", "loading", 1f)
    @Test fun light_shell_font1() = capture("light", "shell", 1f)
    @Test fun light_shell_font2() = capture("light", "shell", 2f)
    @Test fun light_dialog_font1() = capture("light", "dialog", 1f)
    @Test fun dark_signin_font1() = capture("dark", "signin", 1f)
    @Test fun dark_signin_font2() = capture("dark", "signin", 2f)
    @Test fun dark_loading_font1() = capture("dark", "loading", 1f)
    @Test fun dark_shell_font1() = capture("dark", "shell", 1f)
    @Test fun dark_shell_font2() = capture("dark", "shell", 2f)
    @Test fun dark_dialog_font1() = capture("dark", "dialog", 1f)

    private fun capture(theme: String, screen: String, scale: Float) {
        val dark = theme == "dark"
        var normalSignInHeight = 0
        var normalShellHeight = 0
        var action = ""
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                key(scale) {
                Attendance80Theme(darkTheme = dark) {
                    val measurer = rememberTextMeasurer()
                    normalSignInHeight = measurer.measure("Attendance80", style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold), density = Density(density.density, 1f)).size.height
                    normalShellHeight = measurer.measure("Today", style = MaterialTheme.typography.titleLarge, density = Density(density.density, 1f)).size.height
                    Surface {
                        if (screen == "shell") Shell(ShellState(data = todayScreenshotData()), {}, {}, {}, now = 1793600000000, zone = java.time.ZoneId.of("Europe/Paris"))
                        else SignInContent(
                            SignInUiState(config = AuthConfigResponse(google = true, signup = true, dev = true),
                                isLoading = screen == "loading", showCreateAccountDialog = screen == "dialog"),
                            {}, {}, { action = "dev:$it" }, { action = "create" }, {}, {}, {},
                        )
                    }
                }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        if (screen == "shell") { compose.mainClock.autoAdvance = true; compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-list").fetchSemanticsNodes().isNotEmpty() }; compose.mainClock.autoAdvance = false }
        if (screen == "signin" || screen == "shell") {
            val label = if (screen == "signin") "Attendance80" else "Today"
            val height = if (screen == "shell") compose.onNodeWithTag("shell-title").fetchSemanticsNode().boundsInRoot.height else compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height
            val baseline = if (screen == "signin") normalSignInHeight else normalShellHeight
            if (scale == 2f) assertTrue("200% font must increase text height ($height vs $baseline)", height > baseline)
        }
        val file = "src/test/screenshots/${device}_${theme}_${screen}_font${scale.toInt()}.png"
        if (screen == "dialog") compose.onNode(isDialog()).captureRoboImage(file)
        else compose.onRoot().captureRoboImage(file)
        if (screen == "shell" && scale == 2f && device.startsWith("phone")) {
            compose.onNodeWithContentDescription("More").performClick()
        }
        if (screen == "signin" && scale == 2f) {
            compose.onNodeWithText("Create an account with a passkey").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("create", action) }
            compose.onNodeWithText("Sign in with dev account").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("dev:dev@example.com", action) }
        }

    }
}

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneScreenshotTest : ScreenScreenshotTest() { override val device = "phone411x891" }

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletScreenshotTest : ScreenScreenshotTest() { override val device = "tablet840x1000" }

fun screenshotData(): com.knhdixh.attendance80.net.AppData = ApiClient.json.decodeFromString(
    object {}.javaClass.getResource("/api/data.json")!!.readText()
)

fun todayScreenshotData(): com.knhdixh.attendance80.net.AppData {
    val data = screenshotData()
    val now = 1793600000000L
    return data.copy(events = data.events.map { it.copy(status = null) },
        homework = data.homework.mapIndexed { index, h -> h.copy(due_date = "2026-11-02", due_time = "09:13", due_at = now + (index + 2) * 3600000L, done = false) })
}
