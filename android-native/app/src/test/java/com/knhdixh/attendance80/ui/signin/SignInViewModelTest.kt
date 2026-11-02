package com.knhdixh.attendance80.ui.signin

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.auth.AuthRepository
import com.knhdixh.attendance80.auth.TokenStore
import com.knhdixh.attendance80.net.Api
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AuthConfigResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SignInViewModelTest {

    private class FakeTokenStore : TokenStore {
        override val token: StateFlow<String?> = MutableStateFlow(null)
        override val isLoaded: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun awaitToken(): String? = null
        override suspend fun setToken(token: String) {}
        override suspend fun clearToken() {}
    }

    @Test
    fun startDevSignInUrlEncodesEmailWithPlusAndSpecialChars() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val api = ApiClient.createApi("http://localhost:8787", { null }, {})
        val authRepo = AuthRepository(
            api = api,
            tokenStore = FakeTokenStore(),
            credentialManager = CredentialManager.create(context),
        )

        val vm = SignInViewModel(
            authRepository = authRepo,
            savedStateHandle = SavedStateHandle(),
            baseUrl = "https://epita.knhdixh.com",
        )

        var openedUrl: String? = null
        val testEmail = "test+user@epita.fr"
        vm.startDevSignIn(testEmail) { uri ->
            openedUrl = uri.toString()
        }

        assertTrue("URL should be opened", openedUrl != null)
        assertTrue("URL should contain encoded plus %2B", openedUrl!!.contains("email=test%2Buser%40epita.fr"))
    }
}
