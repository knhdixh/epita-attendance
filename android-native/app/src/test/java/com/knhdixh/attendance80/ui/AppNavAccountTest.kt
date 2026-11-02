package com.knhdixh.attendance80.ui

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.knhdixh.attendance80.*
import com.knhdixh.attendance80.auth.*
import com.knhdixh.attendance80.data.*
import com.knhdixh.attendance80.navigation.PendingRouteViewModel
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.ui.shell.ShellViewModel
import com.knhdixh.attendance80.ui.signin.SignInViewModel
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class AppNavAccountTest {
    @get:Rule val compose = createComposeRule()
    @Test fun switchingAccountsDiscardsSavedNavigationAndAction() {
        val server = MockWebServer(); server.start()
        // Opening Focus fetches a year of history; keep it off the queue the refreshes below rely on.
        server.dispatcher = object : okhttp3.mockwebserver.QueueDispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.path?.startsWith("/api/focus") == true) MockResponse().setBody("""{"sessions":[],"since":0}""")
                else super.dispatch(request)
        }
        val dir = Files.createTempDirectory("account-nav").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tokenStore = object : TokenStore {
                override val token = MutableStateFlow<String?>("account-a")
                override val isLoaded = MutableStateFlow(true)
                override suspend fun awaitToken() = token.value
                override suspend fun setToken(token: String) { this.token.value = token }
                override suspend fun clearToken() { token.value = null }
            }
            val clock = object : Clock { override fun now() = 1793600000000L; override fun zone() = java.time.ZoneId.of("Europe/Paris") }
            val api = ApiClient.createApi(server.url("/").toString(), { tokenStore.currentToken() }, {})
            val repo = DataRepository(api, dir, clock) { tokenStore.currentToken() }
            val store = PreferenceDataStoreFactory.create(scope = storeScope) { dir.resolve("scope.preferences_pb") }
            val auth = AuthRepository(api, tokenStore, CredentialManager.create(context))
            val container = object : AppContainer {
                override val context = context
                override val clock = clock
                override val tokenStore = tokenStore
                override val api = api
                override val dataRepository = repo
                override val authRepository = auth
                override val calendarScope = Scope(store)
            }
            val fixture = javaClass.getResource("/api/data.json")!!.readText().replace("\"status\": \"absent\"", "\"status\": \"present\"")
            runBlocking { repo.start(); server.enqueue(MockResponse().setBody(fixture)); Assert.assertTrue(repo.refresh()) }
            server.enqueue(MockResponse().setResponseCode(500))
            val signIn = SignInViewModel(auth, SavedStateHandle())
            var shell by mutableStateOf(ShellViewModel(container))
            val pending = PendingRouteViewModel(SavedStateHandle())
            var token by mutableStateOf<String?>("account-a")
            val restoration = StateRestorationTester(compose)
            restoration.setContent { Attendance80Theme { AppNav(token, signIn, shell, pending) } }
            compose.waitUntil(10_000) { shell.state.value.data != null }
            compose.onNodeWithContentDescription("Search").performClick()
            compose.onNodeWithTag("search-query").performTextInput("go to homework")
            compose.onNodeWithTag("result-nav:homework").performClick()
            compose.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); shell.recents.value.contains("nav:homework") }
            compose.runOnIdle { shell = ShellViewModel(container) }
            compose.onNodeWithContentDescription("Search").performClick()
            compose.waitUntil(10_000) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); shell.recents.value.contains("nav:homework") }
            compose.onNodeWithText("Recent").assertExists()
            compose.onNodeWithTag("result-nav:homework").assertExists()
            compose.onNodeWithTag("search-query").performTextInput("start focus")
            compose.onNodeWithTag("result-focus:start").performClick()
            compose.onNodeWithTag("shell-title").assertTextEquals("Focus")
            compose.onNodeWithText("Focus request saved for the timer.").assertExists()
            compose.runOnIdle { token = null; tokenStore.token.value = null }
            runBlocking {
                repo.start()
                tokenStore.setToken("account-b")
                repo.start()
                server.enqueue(MockResponse().setBody(fixture.replace("Mathématiques", "Account B class")))
                Assert.assertTrue(repo.refresh())
            }
            compose.runOnIdle { token = "account-b" }
            compose.waitUntil(10_000) { shell.state.value.session == "account-b" }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("shell-title").assertTextEquals("Today")
            compose.onNodeWithText("Focus request saved for the timer.").assertDoesNotExist()
            compose.onNodeWithText("Mathématiques").assertDoesNotExist()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("today-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("today-list").performScrollToNode(hasText("Account B class"))
            compose.onNodeWithText("Account B class").assertExists()
            compose.onNode(hasText("Focus") and hasClickAction()).performClick()
            compose.onNodeWithTag("shell-title").assertTextEquals("Focus")
            compose.onNodeWithText("This action is not available yet.").assertDoesNotExist()
        } finally { storeScope.cancel(); server.shutdown(); dir.deleteRecursively() }
    }
}
