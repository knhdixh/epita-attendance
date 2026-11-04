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
import com.knhdixh.attendance80.notify.notifyDataStore
import com.knhdixh.attendance80.widget.WidgetStore
import com.knhdixh.attendance80.widget.redrawWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

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
    val focusStore: FocusStore? get() = null
    /** Reminders posted by the app itself (Phase 7). Null in tests that don't need them. */
    val notifyStore: com.knhdixh.attendance80.notify.NotifyStore? get() = null
    val reminders: com.knhdixh.attendance80.notify.ReminderEngine? get() = null
    /** Calendar view and layers, dismissed exam suggestions. Null in tests that don't need them. */
    val uiPrefs: com.knhdixh.attendance80.data.UiPrefs? get() = null
    val reminderActions: com.knhdixh.attendance80.notify.ReminderActions? get() = null
    val baseUrl: String get() = BuildConfig.BASE_URL
    /** Rebuilds the widget's plan part (zone or clock changed). Nothing in tests that don't have a widget. */
    suspend fun rebuildWidgetPlan() {}
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

    /** What the widget shows, next to the account snapshot (so backup rules exclude it too). */
    val widgetStore: WidgetStore by lazy { com.knhdixh.attendance80.widget.WidgetStores.get(context) }

    override val focusStore: FocusStore by lazy { FocusStore(context.focusDataStore) }

    override val focusController: FocusController by lazy {
        FocusController(
            store = focusStore, clock = clock, alarm = AndroidPhaseAlarm(context), notifier = FocusNotifications(context),
            surfaces = AndroidFocusSurfaces(context, widgetStore),
            outbox = WorkOutboxScheduler(context),
            // The TWA's timer, sessions and tasks move over before anything reads the store: the receiver and the tile get here first.
            beforeFirstLoad = { runCatching { LegacyFocusMigration(context.getSharedPreferences("focus", Context.MODE_PRIVATE), focusStore, WorkOutboxScheduler(context)).run() } },
        )
    }

    override val notifyStore: com.knhdixh.attendance80.notify.NotifyStore by lazy {
        com.knhdixh.attendance80.notify.NotifyStore(context.notifyDataStore)
    }

    override val reminders: com.knhdixh.attendance80.notify.ReminderEngine by lazy {
        com.knhdixh.attendance80.notify.ReminderEngine(clock, notifyStore, com.knhdixh.attendance80.notify.AndroidReminderAlarm(context), reminderNotifier)
    }

    private val reminderNotifier by lazy { com.knhdixh.attendance80.notify.AndroidReminderNotifier(context) }

    override val reminderActions: com.knhdixh.attendance80.notify.ReminderActions by lazy {
        com.knhdixh.attendance80.notify.ReminderActions(clock, reminderNotifier,
            snapshot = {
                // A tap can start a cold process: wait for the token and publish the cached snapshot first.
                if (tokenStore.awaitToken() == null) null else { dataRepository.start(); dataRepository.snapshot.value }
            },
            // The change joins the outbox (and WorkManager sends it); nothing for another account than the signed-in one.
            enqueue = { m, account ->
                if (tokenStore.awaitToken() == null) false else { dataRepository.start(); dataRepository.enqueue(m, expectedAccount = account) != null }
            },
            focusOn = { target, data ->
                // Don't wait for the snapshot collector: the timer needs this account's lengths now.
                focusController.updateSettings(data.focus.settings.toLogic())
                data.user.email?.let { focusController.setAccount(it) }
                focusController.focusOn(target)
            },
            snooze = { owner, id, until -> notifyStore.snooze(owner, id, until) })
    }

    override val focusOutbox: Outbox by lazy {
        Outbox(focusStore, api, recorded = { dataRepository.recordFocus(it) }, sessionEmail = { dataRepository.snapshot.value?.user?.email })
    }

    override val uiPrefs: com.knhdixh.attendance80.data.UiPrefs by lazy { com.knhdixh.attendance80.data.UiPrefs(context) }

    override val dataRepository: DataRepository by lazy {
        // Own directory, so backup rules can exclude it (they take no wildcards). Older builds wrote data-*.json to filesDir itself.
        context.filesDir.listFiles()?.forEach { if (it.name.startsWith("data-") && it.name.endsWith(".json")) it.delete() }
        DataRepository(api, java.io.File(context.filesDir, "snapshots"), clock, onQueued = { WorkOutboxScheduler(context).requestFlush() }) { tokenStore.currentToken() }
    }

    private val widgetPlanWriter by lazy { com.knhdixh.attendance80.widget.WidgetPlanWriter(tokenStore, widgetStore, clock) { redrawWidget(context) } }

    /** The widget's "Do next" list is built with the zone of the moment: rebuild it when the time zone or the clock changes. */
    override suspend fun rebuildWidgetPlan() = widgetPlanWriter.onSnapshot(dataRepository.snapshot.value)

    fun startData() {
        scope.launch {
            dataRepository.snapshot.collect { data ->
                if (data != null) calendarScope.reconcile(data.calendars)
                if (data != null) {
                    focusController.updateSettings(data.focus.settings.toLogic())
                    data.user.email?.let { focusController.setAccount(it) }
                }
            }
        }
        scope.launch {
            // The widget's plan part follows the snapshot, whoever changed it (screen, refresh, cache on start, sign-out).
            dataRepository.snapshot.collectLatest { data -> widgetPlanWriter.onSnapshot(data) }
        }
        scope.launch {
            // Reminders follow the snapshot and the three device switches, whoever changed them.
            // A null snapshot is only "signed out" once the token store says so: on a cold start it is just the cache not loaded yet.
            dataRepository.snapshot.combine(notifyStore.choices) { data, _ -> data }.collectLatest { data ->
                reminders.onSnapshot(data) { tokenStore.awaitToken() == null }
            }
        }
        scope.launch {
            focusController.restore() // loads (and so migrates), then finishes what is due or re-arms
        }
        WorkOutboxScheduler(context).requestFlush()
        scope.launch {
            tokenStore.awaitToken()
            tokenStore.token.collectLatest { token ->
                // Signed out, however it happened (button, 401, account deletion): the timer stops and forgets the account.
                if (token == null) { focusController.signedOut(); reminders.signedOut(); dataRepository.signedOut(); notifyStore.signedOut(); com.knhdixh.attendance80.widget.taskWidgetPrefs(context).clear() }
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
