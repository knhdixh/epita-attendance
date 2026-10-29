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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.knhdixh.attendance80.AuthenticatedPlaceholder
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

    @Test fun light_signin_font1() = capture("light", "signin", 1f)
    @Test fun light_signin_font2() = capture("light", "signin", 2f)
    @Test fun light_loading_font1() = capture("light", "loading", 1f)
    @Test fun light_placeholder_font1() = capture("light", "placeholder", 1f)
    @Test fun light_placeholder_font2() = capture("light", "placeholder", 2f)
    @Test fun light_dialog_font1() = capture("light", "dialog", 1f)
    @Test fun dark_signin_font1() = capture("dark", "signin", 1f)
    @Test fun dark_signin_font2() = capture("dark", "signin", 2f)
    @Test fun dark_loading_font1() = capture("dark", "loading", 1f)
    @Test fun dark_placeholder_font1() = capture("dark", "placeholder", 1f)
    @Test fun dark_placeholder_font2() = capture("dark", "placeholder", 2f)
    @Test fun dark_dialog_font1() = capture("dark", "dialog", 1f)

    private fun capture(theme: String, screen: String, scale: Float) {
        val dark = theme == "dark"
        var normalSignInHeight = 0
        var normalPlaceholderHeight = 0
        var action = ""
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                key(scale) {
                Attendance80Theme(darkTheme = dark) {
                    val measurer = rememberTextMeasurer()
                    normalSignInHeight = measurer.measure("Attendance80", style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold), density = Density(density.density, 1f)).size.height
                    normalPlaceholderHeight = measurer.measure("Welcome to Attendance80", style = MaterialTheme.typography.titleMedium, density = Density(density.density, 1f)).size.height
                    Surface {
                        if (screen == "placeholder") AuthenticatedPlaceholder()
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
        if (screen == "signin" || screen == "placeholder") {
            val label = if (screen == "signin") "Attendance80" else "Welcome to Attendance80"
            val height = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.height
            val baseline = if (screen == "signin") normalSignInHeight else normalPlaceholderHeight
            if (scale == 2f) assertTrue("200% font must increase text height ($height vs $baseline)", height > baseline)
        }
        val file = "src/test/screenshots/${device}_${theme}_${screen}_font${scale.toInt()}.png"
        if (screen == "dialog") compose.onNode(isDialog()).captureRoboImage(file)
        else compose.onRoot().captureRoboImage(file)
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
