package com.knhdixh.attendance80.auth

import android.app.Activity
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.knhdixh.attendance80.net.Api
import com.knhdixh.attendance80.net.ApiClient
import com.knhdixh.attendance80.net.AuthConfigResponse
import com.knhdixh.attendance80.net.PasskeyLoginVerifyRequest
import com.knhdixh.attendance80.net.PasskeyRegisterOptionsRequest
import com.knhdixh.attendance80.net.PasskeyRegisterVerifyRequest
import com.knhdixh.attendance80.net.TokenExchangeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthRepository(
    private val api: Api,
    val tokenStore: TokenStore,
    private val credentialManager: CredentialManager,
) {
    private val _config = MutableStateFlow<AuthConfigResponse?>(null)
    val config: StateFlow<AuthConfigResponse?> = _config.asStateFlow()

    var pendingVerifier: String? = null

    suspend fun loadConfig(): AuthConfigResponse {
        val cfg = api.authConfig()
        _config.value = cfg
        return cfg
    }

    suspend fun exchangeCode(code: String, verifier: String? = pendingVerifier): Result<String> {
        val v = verifier ?: return Result.failure(IllegalStateException("No code verifier present"))
        return try {
            val response = api.exchangeToken(TokenExchangeRequest(code, v))
            tokenStore.setToken(response.token)
            pendingVerifier = null
            Result.success(response.token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signInWithPasskey(activity: Activity): Result<Unit> {
        return try {
            val optionsJson = api.passkeyLoginOptions().toString()
            val getRequest = GetCredentialRequest.Builder()
                .addCredentialOption(GetPublicKeyCredentialOption(optionsJson))
                .build()
            val result = credentialManager.getCredential(activity, getRequest)
            val authResponse = result.credential.data.getString("androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON")
                ?: throw IllegalStateException("Missing passkey authentication response")
            val verifyRes = api.passkeyLoginVerify(
                PasskeyLoginVerifyRequest(ApiClient.json.parseToJsonElement(authResponse))
            )
            verifyRes.token?.let { tokenStore.setToken(it) }
            Result.success(Unit)
        } catch (_: NoCredentialException) {
            Result.failure(Exception("No passkey for Attendance80 on this device."))
        } catch (_: GetCredentialCancellationException) {
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerPasskey(activity: Activity, name: String): Result<Unit> {
        return try {
            val optionsJson = api.passkeyRegisterOptions(PasskeyRegisterOptionsRequest(name)).toString()
            val createRequest = CreatePublicKeyCredentialRequest(optionsJson)
            val result = credentialManager.createCredential(activity, createRequest)
            val regResponse = result.data.getString("androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON")
                ?: throw IllegalStateException("Missing passkey registration response")
            val verifyRes = api.passkeyRegisterVerify(
                PasskeyRegisterVerifyRequest(
                    response = ApiClient.json.parseToJsonElement(regResponse),
                    deviceName = Build.MODEL,
                )
            )
            verifyRes.token?.let { tokenStore.setToken(it) }
            Result.success(Unit)
        } catch (_: CreateCredentialCancellationException) {
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        try {
            api.logout()
        } catch (_: Exception) {
        } finally {
            tokenStore.clearToken()
        }
    }
}
