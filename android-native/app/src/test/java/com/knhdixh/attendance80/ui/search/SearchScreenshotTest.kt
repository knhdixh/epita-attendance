package com.knhdixh.attendance80.ui.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.ui.todayScreenshotData
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

abstract class SearchScreenshotTest {
    abstract val device: String
    @get:Rule val compose = createComposeRule()
    @Test fun light() = capture("light", 1f)
    @Test fun dark() = capture("dark", 1f)
    @Test fun lightLarge() = capture("light", 2f)
    @Test fun darkLarge() = capture("dark", 2f)
    private fun capture(theme: String, scale: Float) {
        var baseline = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Attendance80Theme(darkTheme = theme == "dark") {
                    baseline = rememberTextMeasurer().measure("Search", style = MaterialTheme.typography.titleLarge,
                        density = Density(density.density, 1f)).size.height
                    Shell(ShellState(data = todayScreenshotData()), {}, {}, {}, now = 1793600000000,
                        zone = java.time.ZoneId.of("Europe/Paris"))
                }
            }
        }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true; compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-list").fetchSemanticsNodes().isNotEmpty() }; compose.mainClock.autoAdvance = false
        if (scale == 2f) {
            val height = compose.onNodeWithTag("search-title").fetchSemanticsNode().boundsInRoot.height
            assertTrue("200% font must increase search title ($height vs $baseline)", height > baseline)
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/${device}_${theme}_search_font${scale.toInt()}.png")
        if (scale == 2f) {
            compose.onNodeWithTag("search-query").performTextInput("read")
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("result-habit:1").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNode(isDialog()).assertDoesNotExist()
        }
    }
}
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PhoneSearchScreenshotTest : SearchScreenshotTest() { override val device = "phone411x891" }
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h1000dp-mdpi")
class TabletSearchScreenshotTest : SearchScreenshotTest() { override val device = "tablet840x1000" }
