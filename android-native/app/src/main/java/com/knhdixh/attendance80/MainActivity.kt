package com.knhdixh.attendance80

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.knhdixh.attendance80.ui.shell.Shell
import com.knhdixh.attendance80.ui.shell.ShellViewModel
import com.knhdixh.attendance80.navigation.PendingRouteViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.knhdixh.attendance80.ui.signin.SignInScreen
import com.knhdixh.attendance80.ui.signin.SignInViewModel
import com.knhdixh.attendance80.ui.theme.Attendance80Theme

class MainActivity : ComponentActivity() {
    private val signInViewModel: SignInViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as App
                SignInViewModel(
                    authRepository = app.container.authRepository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }

    private val shellViewModel: ShellViewModel by viewModels {
        viewModelFactory { initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as App
            ShellViewModel(app.container)
        } }
    }

    private val pendingRouteViewModel: PendingRouteViewModel by viewModels {
        viewModelFactory { initializer { PendingRouteViewModel(createSavedStateHandle()) } }
    }

    /** True while the system blocks this app's notifications (permission denied, or switched off in the settings). */
    private var notificationsBlocked by androidx.compose.runtime.mutableStateOf(false)

    private val notifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        afterNotificationChange()
    }

    /** After the permission dialog, and on return from the system settings (onResume). */
    private fun afterNotificationChange() {
        notificationsBlocked = !androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        // Draw the timer notification now that it may be shown.
        lifecycleScope.launch {
            val container = (application as App).container
            container.focusController?.restore()
            // Reminders that were due while the permission was missing go out now. (A snapshot that has not loaded yet is no sign-out.)
            container.dataRepository.snapshot.value?.let { container.reminders?.run(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        afterNotificationChange()
    }

    /** The "Allow" button in Settings: asks if the system still will, otherwise opens the app's notification settings. Not limited to once. */
    private fun fixNotifications() {
        val store = (application as App).container.focusStore
        lifecycleScope.launch {
            val ask = com.knhdixh.attendance80.focus.canAskNotifications(
                missing = com.knhdixh.attendance80.focus.notificationsMissing(this@MainActivity),
                askedBefore = store?.notificationsAsked() ?: true,
                shouldShowRationale = android.os.Build.VERSION.SDK_INT >= 33 && shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS),
            )
            if (ask) {
                store?.markNotificationsAsked()
                notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** Asked in context, the first time a timer starts, and at most once. Never at launch. */
    private fun askNotifications() {
        val store = (application as App).container.focusStore ?: return
        lifecycleScope.launch { com.knhdixh.attendance80.focus.askNotificationsOnce(this@MainActivity, store) { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        (application as App).startData()
        if (savedInstanceState == null) {
            handleIntent(intent)
        }

        setContent {
            Attendance80Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val app = application as App
                    val isLoaded by app.container.tokenStore.isLoaded.collectAsStateWithLifecycle()
                    val token by app.container.tokenStore.token.collectAsStateWithLifecycle()
                    if (isLoaded) {
                        AppNav(
                            token = token,
                            signInViewModel = signInViewModel,
                            shellViewModel = shellViewModel,
                            pendingRouteViewModel = pendingRouteViewModel,
                            onAskNotifications = ::askNotifications,
                            notificationsBlocked = notificationsBlocked,
                            onFixNotifications = ::fixNotifications,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // A calendar file or shared text from another app: Settings → Calendars, or Add homework.
        when (val incoming = com.knhdixh.attendance80.share.readIncoming(intent, contentResolver)) {
            is com.knhdixh.attendance80.share.Incoming.CalendarFile -> { pendingRouteViewModel.shareFile(incoming); return }
            is com.knhdixh.attendance80.share.Incoming.SharedText -> { pendingRouteViewModel.shareText(incoming.text); return }
            null -> Unit
        }
        val data = intent.data ?: return
        if (data.scheme == "a80") pendingRouteViewModel.accept(data.toString())
        if (data.scheme == "com.knhdixh.attendance80") {
            if (data.path == "/gcal") {
                pendingRouteViewModel.go("settings")
                shellViewModel.gcalReturned(data.getQueryParameter("result"), data.getQueryParameter("error"))
            }
            if (data.path == "/auth") {
                val code = data.getQueryParameter("code")
                if (!code.isNullOrBlank()) {
                    signInViewModel.handleAuthCode(code)
                }
            }
        }
        intent.data = null
    }
}

@Composable
fun AppNav(token: String?, signInViewModel: SignInViewModel, shellViewModel: ShellViewModel, pendingRouteViewModel: PendingRouteViewModel, onAskNotifications: () -> Unit = {},
           notificationsBlocked: Boolean = false, onFixNotifications: () -> Unit = {}) {
    if (token.isNullOrBlank()) {
        SignInScreen(viewModel = signInViewModel)
    } else {
        key(token) {
            val state by shellViewModel.state.collectAsStateWithLifecycle()
            val pendingRoute by pendingRouteViewModel.pending.collectAsStateWithLifecycle()
            val sharedFile by pendingRouteViewModel.sharedFile.collectAsStateWithLifecycle()
            val sharedText by pendingRouteViewModel.sharedText.collectAsStateWithLifecycle()
            val recents by shellViewModel.recents.collectAsStateWithLifecycle()
            val pendingDelete by shellViewModel.pendingHomeworkDelete.collectAsStateWithLifecycle()
            val now by produceState(initialValue = shellViewModel.clock.now()) {
                while (true) {
                    value = shellViewModel.clock.now()
                    kotlinx.coroutines.delay(60_000)
                }
            }
            val focusHistory by shellViewModel.focusHistory.collectAsState()
            val focusTimer by shellViewModel.focusTimer.collectAsStateWithLifecycle()
            Shell(if (state.session == token) state else com.knhdixh.attendance80.ui.shell.ShellState(),
                shellViewModel::refresh, shellViewModel::toggleScope, shellViewModel::signOut,
                pendingRoute = pendingRoute, onPendingConsumed = pendingRouteViewModel::clear,
                onValidatePending = { route ->
                    if (shellViewModel.validatePendingSession()) pendingRouteViewModel.clearIfMatches(route)
                },
                now = now, zone = shellViewModel.clock.zone(), onAction = { shellViewModel.dispatch(it, token) },
                recents = recents, onRecent = shellViewModel::recordRecent, onErrorConsumed = { shellViewModel.consumeError(it, token) }, accountKey = token,
                pendingDelete = pendingDelete?.takeIf { it.owner == token },
                onDeleteHomework = { shellViewModel.deleteHomework(token, it) },
                onFinishHomeworkDelete = { undo, expected -> shellViewModel.finishHomeworkDelete(undo, expected) },
                onSaveHomework = { id, body -> shellViewModel.saveHomework(token, id, body) },
                focusHistory = focusHistory,
                onFocusHistory = { since -> shellViewModel.ensureFocusHistory(token, since) },
                onSaveFocus = { body -> shellViewModel.saveFocusSettings(token, body) },
                onSaveFocusList = { body -> shellViewModel.saveFocusList(token, body) },
                onDeleteFocus = { id -> shellViewModel.deleteFocus(token, id) },
                onSaveBreak = { from, to, label -> shellViewModel.saveBreak(token, from, to, label) },
                onRemoveBreak = { id -> shellViewModel.removeBreak(token, id) },
                settingsActions = androidx.compose.runtime.remember(token) { shellViewModel.settingsActions(token) },
                calendarActions = androidx.compose.runtime.remember(token) { shellViewModel.calendarActions(token) },
                habitActions = androidx.compose.runtime.remember(token) { shellViewModel.habitActions(token) },
                countdownActions = androidx.compose.runtime.remember(token) { shellViewModel.countdownActions(token) },
                focusTimer = focusTimer,
                timerNow = { shellViewModel.clock.now() },
                onAskNotifications = onAskNotifications,
                notificationsBlocked = notificationsBlocked, onFixNotifications = onFixNotifications,
                sharedFile = sharedFile, onSharedFileHandled = pendingRouteViewModel::fileHandled,
                sharedText = sharedText, onSharedTextHandled = pendingRouteViewModel::textHandled,
                timerActions = androidx.compose.runtime.remember(token, onAskNotifications) { shellViewModel.timerActions?.let { com.knhdixh.attendance80.focus.AskingActions(it, onAskNotifications) } })
        }
    }
}
