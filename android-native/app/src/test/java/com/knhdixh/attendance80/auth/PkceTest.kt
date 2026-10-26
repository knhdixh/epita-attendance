package com.knhdixh.attendance80.auth

import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.TokenExchangeRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class PkceTest {
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
    fun verifierAndChallengeAreValidBase64UrlSha256() {
        val verifier = Pkce.generateVerifier()
        // 32 random bytes in base64url with no padding is exactly 43 characters
        assertEquals(43, verifier.length)
        assertTrue(verifier.matches(Regex("^[A-Za-z0-9_-]{43}$")))

        val challenge = Pkce.computeChallenge(verifier)
        // SHA-256 is 32 bytes; in base64url with no padding is exactly 43 characters
        assertEquals(43, challenge.length)
        assertTrue(challenge.matches(Regex("^[A-Za-z0-9_-]{43}$")))

        // Verify against standard Java MessageDigest and java.util.Base64 URL encoder
        val digest = MessageDigest.getInstance("SHA-256")
        val expectedBytes = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(expectedBytes)
        assertEquals(expectedChallenge, challenge)

        // Generating multiple verifiers produces distinct values
        val verifier2 = Pkce.generateVerifier()
        assertNotEquals(verifier, verifier2)
    }

    @Test
    fun pkceExchangeAgainstServer() = runBlocking {
        val verifier = Pkce.generateVerifier()
        val challenge = Pkce.computeChallenge(verifier)
        val code = "app_auth_code_12345"

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"token\":\"a80s_session_token_xyz987\"}")
        )

        val api = ApiClient.createApi(
            baseUrl = server.url("/").toString(),
            tokenProvider = { null },
            onUnauthorized = {},
        )

        val response = api.exchangeToken(TokenExchangeRequest(code, verifier))
        assertEquals("a80s_session_token_xyz987", response.token)

        val recorded = server.takeRequest()
        assertEquals("/auth/app/token", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"code\":\"$code\""))
        assertTrue(body.contains("\"verifier\":\"$verifier\""))
    }
}
