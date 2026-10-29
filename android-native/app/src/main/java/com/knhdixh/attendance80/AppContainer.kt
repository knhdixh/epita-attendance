package com.knhdixh.attendance80

import android.content.Context
import androidx.credentials.CredentialManager
import com.knhdixh.attendance80.auth.AndroidKeystoreCipher
import com.knhdixh.attendance80.auth.AuthRepository
import com.knhdixh.attendance80.auth.EncryptedTokenStore
import com.knhdixh.attendance80.auth.TokenStore
import com.knhdixh.attendance80.auth.authDataStore
import com.knhdixh.attendance80.net.Api
import com.knhdixh.attendance80.data.DataRepository
import com.knhdixh.attendance80.data.Scope
import com.knhdixh.attendance80.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

interface AppContainer {
    val context: Context
    val clock: Clock
    val tokenStore: TokenStore
    val api: Api
    val authRepository: AuthRepository
    val dataRepository: DataRepository
    val calendarScope: Scope
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
            tokenProvider = {
                kotlinx.coroutines.runBlocking { tokenStore.awaitToken() }
            },
            onUnauthorized = {
                scope.launch { tokenStore.clearToken() }
            },
        )
    }

    override val calendarScope: Scope by lazy { Scope(context.authDataStore) }

    override val dataRepository: DataRepository by lazy {
        DataRepository(api, context.filesDir, clock) { tokenStore.currentToken() }
    }

    fun startData() {
        scope.launch {
            dataRepository.snapshot.collect { data ->
                if (data != null) calendarScope.reconcile(data.calendars)
            }
        }
        scope.launch {
            tokenStore.awaitToken()
            tokenStore.token.collectLatest { token ->
                dataRepository.start()
                if (token != null) dataRepository.refresh()
            }
        }
    }

    override val authRepository: AuthRepository by lazy {
        AuthRepository(
            api = api,
            tokenStore = tokenStore,
            credentialManager = CredentialManager.create(context),
        )
    }
}
