package com.knhdixh.attendance80

import android.content.Context
import androidx.credentials.CredentialManager
import com.knhdixh.attendance80.auth.AndroidKeystoreCipher
import com.knhdixh.attendance80.auth.AuthRepository
import com.knhdixh.attendance80.auth.EncryptedTokenStore
import com.knhdixh.attendance80.auth.TokenStore
import com.knhdixh.attendance80.auth.authDataStore
import com.knhdixh.attendance80.net.Api
import com.knhdixh.attendance80.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

interface AppContainer {
    val context: Context
    val clock: Clock
    val tokenStore: TokenStore
    val api: Api
    val authRepository: AuthRepository
}

class DefaultAppContainer(
    override val context: Context,
    override val clock: Clock = SystemClock(),
    baseUrl: String = BuildConfig.BASE_URL,
) : AppContainer {
    private val scope = CoroutineScope(Dispatchers.IO)

    override val tokenStore: TokenStore by lazy {
        EncryptedTokenStore(
            dataStore = context.authDataStore,
            cipher = AndroidKeystoreCipher(),
            scope = scope,
        )
    }

    override val api: Api by lazy {
        ApiClient.createApi(
            baseUrl = baseUrl,
            tokenProvider = { tokenStore.currentToken() },
            onUnauthorized = {
                scope.launch { tokenStore.clearToken() }
            },
        )
    }

    override val authRepository: AuthRepository by lazy {
        AuthRepository(
            api = api,
            tokenStore = tokenStore,
            credentialManager = CredentialManager.create(context),
        )
    }
}
