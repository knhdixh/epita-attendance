package com.knhdixh.attendance80.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.knhdixh.attendance80.net.AppData

@Composable
fun SearchSheet(data: AppData?, onDismiss: () -> Unit, onRoute: (String) -> Unit, onSignOut: () -> Unit, onAction: (PaletteAction, String?) -> Unit = { _, _ -> }, now: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
    var query by rememberSaveable { mutableStateOf("") }
    val prefs = LocalContext.current.getSharedPreferences("palette-recents", android.content.Context.MODE_PRIVATE)
    var recents by remember { mutableStateOf(prefs.getString("ids", "").orEmpty().split("\n").filter { it.isNotEmpty() }) }
    val results = remember(data, query, recents, now, zone) { searchPalette(data, query, recents, now, zone) }
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density) {
        Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 650.dp),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 0.dp, shadowElevation = 4.dp, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Search", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).testTag("search-title"))
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().testTag("search-query"),
                        label = { Text("Search") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    if (results.isEmpty()) {
                        Text(if (query.isBlank()) "Nothing found." else "Nothing found for “${query.trim()}”.", modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        val groups = results.groupBy { it.section }
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            groups.forEach { (section, entries) ->
                                item { Text(section, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                                items(entries, key = { it.id }) { item ->
                                    Column(Modifier.fillMaxWidth().clickable {
                                        if (item.recent) {
                                            recents = (listOf(item.id) + recents.filter { it != item.id }).take(5)
                                            prefs.edit().putString("ids", recents.joinToString("\n")).apply()
                                        }
                                        if (item.id == "logout") onSignOut() else if (item.action != null) onAction(item.action, item.route) else item.route?.let(onRoute)
                                        onDismiss()
                                    }.padding(vertical = 12.dp).testTag("result-${item.id}")) {
                                        Text(item.label)
                                        if (item.sub.isNotEmpty()) Text(item.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
