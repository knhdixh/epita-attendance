package com.knhdixh.attendance80.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.AuthenticatedPlaceholder
import com.knhdixh.attendance80.net.AuthConfigResponse
import com.knhdixh.attendance80.ui.signin.SignInContent
import com.knhdixh.attendance80.ui.signin.SignInUiState
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SignInUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadingAndConfiguration() {
        var state by mutableStateOf(SignInUiState(isLoading = true))
        compose.setContent { Attendance80Theme { SignInContent(state, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Sign in with a passkey").assertDoesNotExist()
        compose.runOnIdle { state = SignInUiState(config = AuthConfigResponse()) }
        compose.onNodeWithText("Sign in with a passkey").assertIsDisplayed()
        compose.onNodeWithText("Continue with Google").assertDoesNotExist()
        compose.onNodeWithText("Create an account with a passkey").assertDoesNotExist()
        compose.onNodeWithText("Developer Login").assertDoesNotExist()
        compose.runOnIdle { state = SignInUiState(config = AuthConfigResponse(google = true, signup = true, dev = true)) }
        compose.onNodeWithText("Continue with Google").assertExists()
        compose.onNodeWithText("Create an account with a passkey").assertExists()
        compose.onNodeWithText("Developer Login").assertExists()
    }

    @Test fun actionsAndDialog() {
        compose.mainClock.autoAdvance = false
        var dialog by mutableStateOf(false)
        var action = ""
        compose.setContent {
            Attendance80Theme {
                SignInContent(SignInUiState(config = AuthConfigResponse(google = true, signup = true, dev = true), showCreateAccountDialog = dialog),
                    { action = "google" }, { action = "passkey" }, { action = "dev:$it" },
                    { dialog = true }, { dialog = false }, { action = "create:$it"; dialog = false }, {})
            }
        }
        compose.onNodeWithText("Continue with Google").performClick()
        compose.runOnIdle { assertEquals("google", action) }
        compose.onNodeWithText("Sign in with a passkey").performClick()
        compose.runOnIdle { assertEquals("passkey", action) }
        compose.onNodeWithText("Create an account with a passkey").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Create account").assertIsDisplayed()
        compose.onNodeWithText("Create").performClick()
        compose.runOnIdle { assertEquals("passkey", action) }
        compose.onNodeWithText("Name").performTextInput("  Ada  ")
        compose.onNodeWithText("Create").performClick()
        compose.runOnIdle { assertEquals("create:Ada", action) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Create account").assertDoesNotExist()
        compose.onNodeWithText("Create an account with a passkey").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Cancel").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Create account").assertDoesNotExist()
        compose.onNodeWithText("Email").performTextClearance()
        compose.onNodeWithText("Email").performTextInput("student@example.com")
        compose.onNodeWithText("Sign in with dev account").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("dev:student@example.com", action) }
    }

    @Test fun authenticatedPlaceholder() {
        compose.setContent { Attendance80Theme { Surface { AuthenticatedPlaceholder() } } }
        compose.onNodeWithText("Welcome to Attendance80").assertIsDisplayed()
    }
}
