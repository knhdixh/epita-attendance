package com.knhdixh.attendance80.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.knhdixh.attendance80.App
import com.knhdixh.attendance80.ui.theme.Attendance80Theme
import kotlinx.coroutines.launch

/** Shown when a task list widget is placed: which list should it show? Backing out cancels the widget. */
class TasksWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val app = application as App
        app.startData()
        setContent {
            Attendance80Theme {
                Surface(Modifier.fillMaxSize()) {
                    val data by app.container.dataRepository.snapshot.collectAsState()
                    Column(Modifier.systemBarsPadding().padding(16.dp)) {
                        Text("Which tasks should this widget show?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
                        val choices = data?.let(::taskSourceChoices) ?: listOf("Today" to TaskSource.TODAY, "Inbox" to TaskSource.INBOX)
                        LazyColumn {
                            items(choices) { (label, source) ->
                                Text(label.replace("  ", " "), style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth().clickable { choose(widgetId, source) }.padding(vertical = 14.dp).testTag("widget-source-${source.kind}-${source.id}"))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(widgetId: Int, source: TaskSource) {
        taskWidgetPrefs(this).set(widgetId, source)
        lifecycleScope.launch {
            redrawTaskWidgets(this@TasksWidgetConfigActivity)
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}
