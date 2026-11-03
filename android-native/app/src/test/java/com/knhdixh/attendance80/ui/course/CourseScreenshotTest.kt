package com.knhdixh.attendance80.ui.course

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.ui.shell.*
import com.knhdixh.attendance80.ui.screenshotData
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class CourseScreenshotTest {
    abstract val device: String
    @get:Rule val ui = createComposeRule()
    @Test fun light_main() = capture(false, 1f, "main")
    @Test fun dark_main() = capture(true, 1f, "main")
    @Test fun light_main2() = capture(false, 2f, "main")
    @Test fun dark_main2() = capture(true, 2f, "main")
    @Test fun light_form() = capture(false, 1f, "form")
    @Test fun dark_form() = capture(true, 1f, "form")
    @Test fun light_form2() = capture(false, 2f, "form")
    @Test fun dark_form2() = capture(true, 2f, "form")
    @Test fun light_group() = capture(false, 1f, "group")
    @Test fun dark_group() = capture(true, 1f, "group")
    @Test fun light_tag() = capture(false, 1f, "tag")
    @Test fun dark_tag() = capture(true, 1f, "tag")
    @Test fun light_empty() = capture(false, 1f, "empty")
    @Test fun dark_empty() = capture(true, 1f, "empty")
    @Test fun light_link() = capture(false, 1f, "link")
    @Test fun dark_link() = capture(true, 1f, "link")
    @Test fun light_missing() = capture(false, 1f, "missing")
    @Test fun dark_missing() = capture(true, 1f, "missing")
    /** On wide screens the Courses list sits beside the course, and has similar buttons: look in the course only. */
    private fun inDetail(text: String) = ui.onNode(hasText(text) and hasAnyAncestor(hasTestTag("course-detail")))
    private fun capture(dark: Boolean, scale: Float, kind: String) {
        val base = screenshotData()
        val first = base.courses.first()
        val data = if (kind in listOf("group", "tag", "empty", "link")) base.copy(
            courses = base.courses.map { if (it.id == first.id) it.copy(name = "GR1 - Advanced language studies with an exceptionally long course title", recording_url = "https://example.org/recordings") else it } + com.knhdixh.attendance80.net.CourseDto(99991, "GR2 - Advanced language studies with an exceptionally long course title"),
            events = if (kind == "empty") base.events.filterNot { it.course_id == first.id } else base.events) else base
        val id = if (kind == "missing") 999999 else data.courses.first().id
        ui.setContent { val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Attendance80Theme(darkTheme = dark) { Shell(ShellState(data = data), {}, {}, {}, initialRoute = "course/$id", now = 1793600000000, zone = java.time.ZoneId.of("Europe/Paris")) }
            }
        }
        if (kind == "missing") ui.waitUntil(10000) { ui.onAllNodesWithText("Course not found.").fetchSemanticsNodes().isNotEmpty() }
        else ui.waitUntil(10000) { ui.onAllNodesWithTag("course-detail").fetchSemanticsNodes().isNotEmpty() }
        if (kind == "form") {
            val label = if (data.courses.first().recording_url == null) "Add link" else "Edit link"
            ui.onNodeWithTag("course-detail").performScrollToNode(hasText(label))
            inDetail(label).performClick()
            ui.mainClock.autoAdvance = false
            ui.mainClock.advanceTimeBy(500)
            ui.onNode(isDialog()).captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_course_${kind}_${scale.toInt()}.png")
        } else {
            if (kind == "group") { ui.onNodeWithTag("course-detail").performScrollToNode(hasText("Several groups")); inDetail("Several groups").performClick() }
            if (kind == "tag") inDetail("Default tag: Normal").performClick()
            if (kind == "empty") ui.onNodeWithTag("course-detail").performScrollToNode(hasText("No sessions match."))
            if (kind == "link") ui.onNodeWithTag("course-detail").performScrollToNode(hasText("Open recordings ↗"))
            val target = if (kind == "group" || kind == "tag") ui.onNode(isDialog()) else ui.onRoot()
            target.captureRoboImage("src/test/screenshots/${device}_${if (dark) "dark" else "light"}_course_${kind}_${scale.toInt()}.png")
        }
    }
}
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneCourseScreenshotTest : CourseScreenshotTest() { override val device = "phone411x891" }
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletCourseScreenshotTest : CourseScreenshotTest() { override val device = "tablet840x1000" }
