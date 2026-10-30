package com.knhdixh.attendance80.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.test.core.app.ApplicationProvider
import com.knhdixh.attendance80.net.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogoutTest {
    @Test fun clearsLocalSessionBeforeRevocationAndPreservesNewSession() = runBlocking {
        val server = MockWebServer(); server.start()
        val store = object : TokenStore {
            override val token = MutableStateFlow<String?>("old-token")
            override val isLoaded = MutableStateFlow(true)
            override suspend fun awaitToken() = token.value
            override suspend fun setToken(token: String) { this.token.value = token }
            override suspend fun clearToken() { token.value = null }
        }
        try {
            server.enqueue(MockResponse().setBody("{\"ok\":true}").setBodyDelay(2, TimeUnit.SECONDS))
            val api = ApiClient.createApi(server.url("/").toString(), { store.currentToken() }, {})
            val auth = AuthRepository(api, store, CredentialManager.create(ApplicationProvider.getApplicationContext<Context>()))
            val logout = async(Dispatchers.IO) { auth.logout() }
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer old-token", request.getHeader("Authorization"))
            assertNull(store.token.value)
            assertFalse(logout.isCompleted)
            store.setToken("new-token")
            logout.await()
            assertEquals("new-token", store.token.value)
        } finally { server.shutdown() }
    }
}
