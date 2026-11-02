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
import com.knhdixh.attendance80.focus.*
import com.knhdixh.attendance80.logic.FocusSettings
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
    /** The one focus timer on the device. Null in tests that don't need it. */
    val focusController: FocusController? get() = null
    val focusOutbox: Outbox? get() = null
    val baseUrl: String get() = BuildConfig.BASE_URL
}

class DefaultAppContainer(
    override val context: Context,
    override val clock: Clock = SystemClock(),
    override val baseUrl: String = BuildConfig.BASE_URL,
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
            onUnauthorized = { rejectedToken ->
                if (rejectedToken != null) scope.launch { tokenStore.clearTokenIfMatches(rejectedToken) }
            },
        )
    }

    override val calendarScope: Scope by lazy { Scope(context.authDataStore) }

    val focusStore: FocusStore by lazy { FocusStore(context.focusDataStore) }

    override val focusController: FocusController by lazy {
        FocusController(
            store = focusStore, clock = clock, alarm = AndroidPhaseAlarm(context), notifier = FocusNotifications(context),
            surfaces = object : FocusSurfaces { override fun refresh() {} },
            outbox = WorkOutboxScheduler(context),
        )
    }

    override val focusOutbox: Outbox by lazy {
        Outbox(focusStore, api, recorded = { dataRepository.recordFocus(it) })
    }

    override val dataRepository: DataRepository by lazy {
        DataRepository(api, context.filesDir, clock) { tokenStore.currentToken() }
    }

    fun startData() {
        scope.launch {
            dataRepository.snapshot.collect { data ->
                if (data != null) calendarScope.reconcile(data.calendars)
                if (data != null) focusController.updateSettings(data.focus.settings.toLogic())
            }
        }
        scope.launch { focusController.restore() }
        WorkOutboxScheduler(context).requestFlush()
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

fun com.knhdixh.attendance80.net.FocusSettingsDto.toLogic() = FocusSettings(
    focus = focus, short = short, long = long, longEvery = longEvery, goal = goal, autoBreaks = autoBreaks,
    autoFocus = autoFocus, sound = sound, notify = notify, tasks = tasks, tags = tags,
)
