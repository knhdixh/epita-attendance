package com.knhdixh.attendance80.focus

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.knhdixh.attendance80.App
import com.knhdixh.attendance80.logic.FocusSettings
import com.knhdixh.attendance80.logic.FocusTimer
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.ui.focus.TimerActions
import com.knhdixh.attendance80.ui.focus.TimerPanel
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import kotlinx.coroutines.launch

/**
 * The quick-focus sheet, opened by long-pressing the tile and from the widget: pick what you are working on (or add a
 * task), toggle tags, set the time, and start. The same panel as the Focus screen, in its own task so the app doesn't
 * come up behind it.
 */
class QuickFocusActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Draw the timer notification now that it may be shown.
        lifecycleScope.launch { (application as App).container.focusController?.restore() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        app.startData()
        val container = app.container
        val controller = container.focusController
        val store = container.focusStore
        if (controller == null || store == null) { finish(); return }
        val actions = object : TimerActions {
            private val inner = ControllerActions(controller, store, lifecycleScope, WorkOutboxScheduler(this@QuickFocusActivity)) { finish() }
            override fun toggle() {
                lifecycleScope.launch { askNotificationsOnce(this@QuickFocusActivity, store) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) } }
                inner.toggle()
            }
            override fun skip() = inner.skip()
            override fun stop() = inner.stop()
            override fun adjust(min: Int) = inner.adjust(min)
            override fun setLength(min: Int) = inner.setLength(min)
            override fun setTarget(target: com.knhdixh.attendance80.logic.FocusTarget?) = inner.setTarget(target)
            override fun addTask(label: String) = inner.addTask(label)
        }
        setContent {
            val timer by controller.timer.collectAsState()
            val settings by controller.settings.collectAsState()
            val data by container.dataRepository.snapshot.collectAsState()
            val pending by store.pendingTasksFlow.collectAsState(emptyList())
            LaunchedEffect(Unit) { controller.load() }
            Attendance80Theme { QuickFocusSheet(timer, settings, data, pending, container.clock::now, actions, onClose = ::finish) }
        }
    }
}

@Composable
fun QuickFocusSheet(
    timer: FocusTimer, settings: FocusSettings, data: AppData?, pendingTasks: List<String>,
    now: () -> Long, actions: TimerActions, onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)).clickable(onClick = onClose).testTag("quick-focus-scrim"), contentAlignment = Alignment.BottomCenter) {
        Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = false) {}.testTag("quick-focus")) {
            Column(Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimerPanel(timer, settings, data, pendingTasks, now, actions)
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).testTag("quick-focus-close")) { Text("Close") }
            }
        }
    }
}
