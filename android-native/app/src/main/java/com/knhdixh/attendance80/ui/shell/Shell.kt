package com.knhdixh.attendance80.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.ui.search.SearchSheet
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("today", "Today", Icons.Outlined.Today),
    Destination("attendance", "Attendance", Icons.Outlined.Assessment),
    Destination("homework", "Homework", Icons.Outlined.Assignment),
    Destination("focus", "Focus", Icons.Outlined.Timer),
    Destination("upcoming", "Upcoming", Icons.Outlined.DateRange),
    Destination("sessions", "Sessions", Icons.AutoMirrored.Outlined.List),
    Destination("courses", "Courses", Icons.Outlined.School),
    Destination("calendar", "Calendar", Icons.Outlined.CalendarMonth),
    Destination("breaks", "Breaks", Icons.Outlined.BeachAccess),
    Destination("habits", "Habits", Icons.Outlined.CheckCircle),
    Destination("countdowns", "Countdowns", Icons.Outlined.HourglassBottom),
    Destination("settings", "Settings", Icons.Outlined.Settings),
)
private val more = Destination("more", "More", Icons.Outlined.MoreHoriz)
private val phone = destinations.take(4) + more

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Shell(
    state: ShellState,
    onRefresh: () -> Unit,
    onScope: (String) -> Unit,
    onSignOut: () -> Unit,
    initialRoute: String = "today",
    pendingRoute: String? = null,
    onPendingConsumed: () -> Unit = {},
    onValidatePending: suspend (String) -> Unit = {},
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var history by rememberSaveable { mutableStateOf(listOf(initialRoute)) }
    val route = history.last()
    fun navigate(destination: String, topLevel: Boolean = false) {
        history = if (topLevel) listOf(destination) else history + destination
        if (pendingRoute != null) onPendingConsumed()
    }
    fun signOut() {
        if (pendingRoute != null) onPendingConsumed()
        onSignOut()
    }
    LaunchedEffect(pendingRoute, state.session) {
        if (pendingRoute != null) {
            history = if (pendingRoute == "today") listOf("today") else listOf("today", pendingRoute)
            if (state.session != null) onValidatePending(pendingRoute)
        }
    }
    BackHandler(enabled = history.size > 1) { history = history.dropLast(1) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val largeText = LocalDensity.current.fontScale > 1.3f
    val title = when {
        route.startsWith("session/") -> "Session"
        route.startsWith("course/") -> "Course"
        route == "homework/new" -> "Add homework"
        route.startsWith("homework/") -> "Homework"
        route == "habits/new" -> "New habit"
        route.startsWith("habits/") -> "Habits"
        route == "countdowns/new" -> "New countdown"
        route.startsWith("countdowns/") -> "Countdowns"
        else -> (destinations + more).find { it.route == route }?.label ?: "Today"
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(state.error) { state.error?.let { snack.showSnackbar(it) } }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text(title, modifier = Modifier.testTag("shell-title")) }, actions = {
                    IconButton(onClick = { showSearch = true }) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
                })
                if ((state.data?.calendars?.size ?: 0) >= 2) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Count", modifier = Modifier.padding(top = 10.dp))
                        FilterChip(selected = state.selected.isEmpty(), onClick = { onScope("all") }, label = { Text("All") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary))
                        state.data?.calendars?.forEach { calendar ->
                            FilterChip(selected = calendar.id in state.selected, onClick = { onScope(calendar.id.toString()) }, label = { Text(calendar.name) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                phone.forEach { item ->
                    val selected = route == item.route || (item == more && route in destinations.drop(4).map { it.route })
                    NavigationBarItem(selected = selected, onClick = { navigate(item.route, topLevel = true) },
                        icon = { Icon(item.icon, contentDescription = if (largeText) item.label else null) },
                        label = if (largeText) null else {{ Text(item.label) }},
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (wide) {
                NavigationRail(modifier = Modifier.width(if (largeText) 220.dp else 160.dp), containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        destinations.forEach { item ->
                            TextButton(onClick = { navigate(item.route, topLevel = true) }, modifier = Modifier.fillMaxWidth().testTag("nav-${item.route}"),
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (route == item.route) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (route == item.route) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                                Icon(item.icon, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(item.label, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh, modifier = Modifier.weight(1f).fillMaxHeight()) {
                ShellContent(route, state.data, now, zone, onNavigate = { navigate(it) }, onSignOut = ::signOut)
            }
        }
    }
    if (showSearch) SearchSheet(state.data, onDismiss = { showSearch = false },
        onRoute = { navigate(it) }, onSignOut = ::signOut)
}

private data class RowData(val title: String, val detail: String = "", val route: String? = null)
private fun date(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", java.util.Locale.UK)
    .format(Instant.ofEpochMilli(ms).atZone(zone))

@Composable
private fun ShellContent(route: String, data: AppData?, now: Long, zone: ZoneId, onNavigate: (String) -> Unit, onSignOut: () -> Unit) {
    val events = data?.events.orEmpty().sortedBy { it.start_at }
    val courses = data?.courses.orEmpty()
    val rows: List<RowData> = when {
        route == "more" -> destinations.drop(4).map { RowData(it.label, route = it.route) }
        route == "today" -> events.filter { Instant.ofEpochMilli(it.start_at).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }
            .map { RowData(it.summary, date(it.start_at, zone), "session/${it.id}") }
        route == "attendance" -> courses.filter { it.tracked }.map { RowData(it.name, route = "course/${it.id}") }
        route == "upcoming" -> events.filter { it.end_at >= now }.map { RowData(it.summary, date(it.start_at, zone), "session/${it.id}") }
        route == "sessions" || route == "calendar" -> events.map { RowData(it.summary, date(it.start_at, zone), "session/${it.id}") }
        route == "courses" -> courses.map { RowData(it.name, route = "course/${it.id}") }
        route == "focus" -> data?.focus?.sessions.orEmpty().map { RowData(it.label ?: "Focus", date(it.started_at, zone)) }
        route == "settings" -> data?.user?.let { listOf(RowData(it.name ?: it.email.orEmpty(), it.email.orEmpty())) }.orEmpty()
        (route == "homework" || route == "homework/new") -> data?.homework.orEmpty().map { RowData(it.title, it.due_date.orEmpty(), "homework/${it.id}") }
        route.startsWith("homework/") -> data?.homework.orEmpty().filter { "homework/${it.id}" == route }.map { RowData(it.title, listOfNotNull(it.due_date, it.notes.takeIf(String::isNotBlank)).joinToString(" · ")) }
        route == "breaks" -> data?.breaks.orEmpty().map { RowData(it.label, "${it.start_date} – ${it.end_date}") }
        route == "habits" || route == "habits/new" -> data?.habits?.list.orEmpty().filterNot { it.archived }.map { RowData(it.name, route = "habits/${it.id}") }
        route.startsWith("habits/") -> data?.habits?.list.orEmpty().filter { "habits/${it.id}" == route }.map { RowData(it.name) }
        route == "countdowns" || route == "countdowns/new" -> data?.countdowns.orEmpty().map { RowData(it.title, date(it.at, zone), "countdowns/${it.id}") }
        route.startsWith("countdowns/") -> data?.countdowns.orEmpty().filter { "countdowns/${it.id}" == route }.map { RowData(it.title, date(it.at, zone)) }
        route.startsWith("session/") -> events.filter { "session/${it.id}" == route }.map { RowData(it.summary, listOf(date(it.start_at, zone), it.location).filter { s -> s.isNotBlank() }.joinToString(" · ")) }
        route.startsWith("course/") -> courses.filter { "course/${it.id}" == route }.map { RowData(it.name) }
        else -> emptyList()
    }
    val empty = when {
        route.startsWith("session/") -> "No sessions match."
        route.startsWith("course/") -> "No tracked courses."
        route.startsWith("homework/") && route != "homework/new" -> "Nothing due. Press Q to add homework."
        route.startsWith("habits/") && route != "habits/new" -> "Build habits that stick. Add one, tick it off each day, and watch the grid fill in."
        route.startsWith("countdowns/") && route != "countdowns/new" -> "Count down to what matters, or count up from when something began."
        else -> when (route) {
        "today" -> "No classes today."
        "attendance", "courses" -> "No tracked courses."
        "sessions" -> "No sessions match."
        "upcoming", "calendar" -> "Nothing scheduled"
        "homework", "homework/new" -> "Nothing due. Press Q to add homework."
        "habits", "habits/new" -> "Build habits that stick. Add one, tick it off each day, and watch the grid fill in."
        "countdowns", "countdowns/new" -> "Count down to what matters, or count up from when something began."
        "focus" -> "Your focus sessions will show here. Press F to start one."
        else -> ""
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (data == null && route != "more") item { CircularProgressIndicator() }
        else {
            if (route == "today") item { Text("Classes", style = MaterialTheme.typography.titleMedium) }
            if (route == "breaks") item { Text("Saved breaks", style = MaterialTheme.typography.titleMedium) }
            if (rows.isEmpty() && empty.isNotEmpty()) item { Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows) { row ->
                Column(Modifier.fillMaxWidth().then(if (row.route != null) Modifier.clickable { onNavigate(row.route) } else Modifier)) {
                    Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp)) {
                        Text(row.title, style = MaterialTheme.typography.bodyLarge)
                        if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (route == "settings") item { OutlinedButton(onClick = onSignOut) { Text("Sign out") } }
        }
    }
}
