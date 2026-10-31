package com.knhdixh.attendance80.ui.courses

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.net.CourseDto
import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class CoursesScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun light_main() = capture(false, 1f, "main")
    @Test fun dark_main() = capture(true, 1f, "main")
    @Test fun light_main2() = capture(false, 2f, "main")
    @Test fun dark_main2() = capture(true, 2f, "main")
    @Test fun light_empty() = capture(false, 1f, "empty")
    @Test fun dark_empty() = capture(true, 1f, "empty")
    @Test fun light_group() = capture(false, 1f, "group")
    @Test fun dark_group() = capture(true, 1f, "group")
    @Test fun light_row() = capture(false, 1f, "row")
    @Test fun dark_row() = capture(true, 1f, "row")
    @Test fun light_row2() = capture(false, 2f, "row")
    @Test fun dark_row2() = capture(true, 2f, "row")
    @Test fun light_tag() = capture(false, 1f, "tag")
    @Test fun dark_tag() = capture(true, 1f, "tag")
    @Test fun light_form() = capture(false, 1f, "form")
    @Test fun dark_form() = capture(true, 1f, "form")
    private fun capture(dark: Boolean, scale: Float, kind: String) {
        val base = screenshotData()
        val data = if (kind == "empty") base.copy(courses = emptyList(), events = emptyList()) else base.copy(courses = listOf(
            CourseDto(90001, "GR1 - Advanced language studies with an exceptionally long course title", tracked = false),
            CourseDto(90002, "GR2 - Advanced language studies with an exceptionally long course title")) + base.courses,
            events = base.events + (1..123).map { base.events.first().copy(id = 100000L + it, course_id = 90001) })
        ui.setContent { val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = data), {}, {}, {}, initialRoute = "courses", now = 1793600000000, zone = java.time.ZoneId.of("Europe/Paris")) }
            }
        }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("courses-list").fetchSemanticsNodes().isNotEmpty() }
        if (kind == "group") { ui.onNodeWithText("GR2").performClick() }
        if (kind in listOf("row", "tag", "form")) {
            ui.onNodeWithTag("courses-list").performScrollToNode(hasTestTag("course-row-90001"))
            if (kind == "tag") ui.onAllNodesWithText("Default tag: Normal").onFirst().performClick()
            if (kind == "form") { ui.onAllNodesWithText("Add link").onFirst().performClick(); ui.mainClock.autoAdvance = false; ui.mainClock.advanceTimeBy(500) }
        }
        val target = if (kind in listOf("group", "tag", "form")) ui.onNode(isDialog()) else ui.onRoot()
        target.captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_courses_${kind}_${scale.toInt()}.png")
    }
}
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneCoursesScreenshotTest : CoursesScreenshotTest() { override val device = "phone411x891" }
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletCoursesScreenshotTest : CoursesScreenshotTest() { override val device = "tablet840x1000" }
