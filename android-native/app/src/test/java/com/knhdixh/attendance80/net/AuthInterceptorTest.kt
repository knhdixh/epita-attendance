package com.knhdixh.attendance80.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AuthInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun bearerTokenAddedToApiRequests() {
        var token: String? = "a80s_test_token_1234567890"
        val unauthorizedCalled = AtomicBoolean(false)

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(
                tokenProvider = { token },
                onUnauthorized = { unauthorizedCalled.set(true) },
            ))
            .build()

        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))

        val req = Request.Builder()
            .url(server.url("/api/data"))
            .build()
        val res = client.newCall(req).execute()

        assertEquals(200, res.code)
        val recorded = server.takeRequest()
        assertEquals("Bearer a80s_test_token_1234567890", recorded.getHeader("Authorization"))
        assertTrue(!unauthorizedCalled.get())
    }

    @Test
    fun bearerTokenNotAddedToAuthRequests() {
        var token: String? = "a80s_test_token_1234567890"
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(
                tokenProvider = { token },
                onUnauthorized = {},
            ))
            .build()

        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))

        val req = Request.Builder()
            .url(server.url("/auth/config"))
            .build()
        val res = client.newCall(req).execute()

        assertEquals(200, res.code)
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun unauthorized401ClearsTokenOnApiRequests() {
        var token: String? = "a80s_expired_token_12345"
        val unauthorizedCalled = AtomicBoolean(false)

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(
                tokenProvider = { token },
                onUnauthorized = {
                    token = null
                    unauthorizedCalled.set(true)
                },
            ))
            .build()

        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"Not signed in.\"}"))

        val req = Request.Builder()
            .url(server.url("/api/data"))
            .build()
        val res = client.newCall(req).execute()

        assertEquals(401, res.code)
        assertTrue(unauthorizedCalled.get())
        assertNull(token)
    }
}
