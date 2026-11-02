package com.knhdixh.attendance80.ui.shell

import androidx.activity.compose.BackHandler
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.knhdixh.attendance80.logic.sanitizeScope
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.net.AppData
import com.knhdixh.attendance80.ui.search.SearchSheet
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.net.ApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.LocalDate
import com.knhdixh.attendance80.logic.Dates

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

private fun owningRoute(route: String): String = when {
    route.startsWith("session/") -> "sessions"
    route.startsWith("course/") -> "courses"
    route.startsWith("homework/") -> "homework"
    route.startsWith("habits/") -> "habits"
    route.startsWith("countdowns/") -> "countdowns"
    else -> route
}

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
    onAction: (PaletteAction) -> Unit = {},
    recents: List<String> = emptyList(),
    onRecent: (String) -> Unit = {},
    onErrorConsumed: (String) -> Unit = {},
    accountKey: String? = state.session,
    pendingDelete: PendingHomeworkDelete? = null,
    onDeleteHomework: (com.knhdixh.attendance80.net.HomeworkDto) -> Unit = {},
    onFinishHomeworkDelete: (Boolean, PendingHomeworkDelete) -> Unit = { _, _ -> },
    onSaveHomework: suspend (Long?, kotlinx.serialization.json.JsonObject) -> Boolean = { _, _ -> false },
    focusHistory: com.knhdixh.attendance80.data.FocusHistoryStatus = com.knhdixh.attendance80.data.FocusHistoryStatus(),
    onFocusHistory: suspend (Long) -> Unit = {},
    onSaveFocus: suspend (kotlinx.serialization.json.JsonObject) -> Boolean = { false },
    onSaveFocusList: suspend (kotlinx.serialization.json.JsonObject) -> com.knhdixh.attendance80.net.FocusSettingsDto? = { null },
    onDeleteFocus: suspend (Long) -> Boolean = { false },
    onSaveBreak: suspend (String, String, String) -> Boolean = { _, _, _ -> false },
    onRemoveBreak: suspend (Long) -> Boolean = { false },
    settingsActions: com.knhdixh.attendance80.ui.settings.SettingsActions = com.knhdixh.attendance80.ui.settings.NoSettingsActions,
    calendarActions: com.knhdixh.attendance80.ui.calendar.CalendarActions = com.knhdixh.attendance80.ui.calendar.NoCalendarActions,
    habitActions: com.knhdixh.attendance80.ui.habits.HabitActions = com.knhdixh.attendance80.ui.habits.NoHabitActions,
    navController: NavHostController = rememberNavController(),
) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.let { pattern ->
        if (pattern.contains("{id}")) pattern.replace("{id}", entry?.arguments?.getString("id").orEmpty()) else pattern
    } ?: initialRoute
    val owner = owningRoute(route)
    fun navigate(destination: String, topLevel: Boolean = false) {
        navController.navigate(destination) {
            if (topLevel) popUpTo(navController.graph.startDestinationId) { inclusive = true }
            launchSingleTop = true
        }
        if (pendingRoute != null) onPendingConsumed()
    }
    fun signOut() {
        if (pendingRoute != null) onPendingConsumed()
        onSignOut()
    }
    LaunchedEffect(pendingRoute, state.session, entry != null) {
        if (pendingRoute != null && entry != null) {
            if (route != pendingRoute) navController.navigate(pendingRoute) { launchSingleTop = true }
            if (state.session != null) onValidatePending(pendingRoute)
        }
    }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var sessionCourse by rememberSaveable(accountKey) { mutableStateOf<Long?>(null) }
    var sessionWhen by rememberSaveable(accountKey) { mutableStateOf("upcoming") }
    var sessionQuery by rememberSaveable(accountKey) { mutableStateOf("") }
    var sessionHidden by rememberSaveable(accountKey) { mutableStateOf(false) }
    var upcomingWeek by rememberSaveable(accountKey) { mutableIntStateOf(0) }
    var habitsUi by rememberSaveable(accountKey) { mutableStateOf(ApiClient.json.encodeToString(com.knhdixh.attendance80.ui.habits.HabitsUi())) }
    var calendarUi by rememberSaveable(accountKey) { mutableStateOf(ApiClient.json.encodeToString(com.knhdixh.attendance80.ui.calendar.CalendarUi())) }
    var breakPick by rememberSaveable(accountKey) { mutableStateOf(ApiClient.json.encodeToString(com.knhdixh.attendance80.ui.breaks.BreakPick())) }
    var lastHomeworkCourse by rememberSaveable(accountKey) { mutableStateOf<Long?>(null) }
    var homeworkDrafts by rememberSaveable(accountKey) { mutableStateOf(hashMapOf<String, String>()) }
    val focusOwnerKey = remember(accountKey) { accountKey?.let { token -> java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) } } }
    var focusDraftOwner by rememberSaveable { mutableStateOf(focusOwnerKey) }
    var focusDraft by rememberSaveable { mutableStateOf<String?>(null) }
    val focusListState = rememberLazyListState()
    val currentFocusOwner by rememberUpdatedState(accountKey)
    val currentFocusData by rememberUpdatedState(state.data)
    val currentFocusHistory by rememberUpdatedState(focusHistory)
    val currentFocusTime by rememberUpdatedState(now)
    val currentFocusZone by rememberUpdatedState(zone)
    val currentHistoryAction by rememberUpdatedState(onFocusHistory)
    val currentSaveFocus by rememberUpdatedState(onSaveFocus)
    val currentSaveFocusList by rememberUpdatedState(onSaveFocusList)
    val currentDeleteFocus by rememberUpdatedState(onDeleteFocus)
    LaunchedEffect(focusOwnerKey) {
        if (focusDraftOwner != focusOwnerKey) {
            focusDraft = null
            focusListState.scrollToItem(0)
            runCatching { navController.getBackStackEntry("focus").savedStateHandle["paletteAction"] = null }
            focusDraftOwner = focusOwnerKey
        }
    }
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
    LaunchedEffect(state.error) { state.error?.let { snack.showSnackbar(it); onErrorConsumed(it) } }
    LaunchedEffect(pendingDelete) {
        if (pendingDelete != null) {
            val result = snack.showSnackbar("Homework deleted.", actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Indefinite)
            onFinishHomeworkDelete(result == SnackbarResult.ActionPerformed, pendingDelete)
        }
    }
    val visibleData = state.data?.let { d -> pendingDelete?.let { p -> d.copy(homework = d.homework.filterNot { it.id == p.item.id }) } ?: d }
    fun pending(action: PaletteAction, destination: String) {
        navigate(destination)
        navController.currentBackStackEntry?.savedStateHandle?.set("paletteAction", ApiClient.json.encodeToString(action))
    }
    fun deleteHomework(item: com.knhdixh.attendance80.net.HomeworkDto) {
        snack.currentSnackbarData?.dismiss()
        onDeleteHomework(item)
    }
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
                        FilterChip(selected = state.selected.isEmpty(), onClick = { onScope("all") }, label = { Text("All") }, modifier = Modifier.testTag("scope-all"), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary))
                        state.data?.calendars?.forEach { calendar ->
                            FilterChip(selected = calendar.id in state.selected, onClick = { onScope(calendar.id.toString()) }, label = { Text(calendar.name) }, modifier = Modifier.testTag("scope-${calendar.id}"), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                phone.forEach { item ->
                    val selected = owner == item.route || (item == more && owner in destinations.drop(4).map { it.route })
                    NavigationBarItem(selected = selected, onClick = { navigate(item.route, topLevel = true) }, modifier = Modifier.testTag("nav-${item.route}"),
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
                            TextButton(onClick = { navigate(item.route, topLevel = true) }, modifier = Modifier.fillMaxWidth().testTag("nav-${item.route}").semantics { selected = owner == item.route },
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (owner == item.route) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (owner == item.route) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                                Icon(item.icon, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(item.label, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh, modifier = Modifier.weight(1f).fillMaxHeight()) {
                NavHost(navController, startDestination = initialRoute) {
                    (destinations.map { it.route } + "more" + listOf("homework/new", "habits/new", "countdowns/new", "session/{id}", "course/{id}", "homework/{id}", "habits/{id}", "countdowns/{id}")).forEach { destination ->
                        composable(destination) { backStackEntry ->
                            val route = backStackEntry.destination.route?.let { pattern ->
                                if (pattern.contains("{id}")) pattern.replace("{id}", backStackEntry.arguments?.getString("id").orEmpty()) else pattern
                            } ?: destination
                            val encoded by backStackEntry.savedStateHandle.getStateFlow<String?>("paletteAction", null).collectAsState()
                            val action = encoded?.let { runCatching { ApiClient.json.decodeFromString<PaletteAction>(it) }.getOrNull() }
                            BackHandler(enabled = navController.previousBackStackEntry != null) {
                                if (pendingRoute != null) onPendingConsumed()
                                navController.popBackStack()
                            }
                            if (route == "today") com.knhdixh.attendance80.ui.today.TodayScreen(visibleData, now, zone, onNavigate = { navigate(it) }, onAction = onAction, allEvents = state.allEvents, onDelete = ::deleteHomework, onPending = ::pending)
                            else if (route == "focus") com.knhdixh.attendance80.ui.focus.FocusScreen(currentFocusData, currentFocusOwner, currentFocusTime, currentFocusZone, currentFocusHistory, focusListState, focusDraft, { focusDraft = it }, currentHistoryAction, currentSaveFocus, currentDeleteFocus, { snack.showSnackbar(it) }, action as? PaletteAction.Focus, { pending(it, "focus") }, currentSaveFocusList)
                            else if (route == "settings") key(accountKey) {
                                com.knhdixh.attendance80.ui.settings.SettingsScreen(state.data, state.allEvents, state.selected, now, zone, settingsActions,
                                    onSignOut = ::signOut, onNavigate = { navigate(it, topLevel = true) })
                            }
                            else if (route == "habits" || route == "habits/new") key(accountKey) {
                                val ui = runCatching { ApiClient.json.decodeFromString<com.knhdixh.attendance80.ui.habits.HabitsUi>(habitsUi) }.getOrDefault(com.knhdixh.attendance80.ui.habits.HabitsUi())
                                com.knhdixh.attendance80.ui.habits.HabitsScreen(visibleData, now, zone, ui, { habitsUi = ApiClient.json.encodeToString(it) }, habitActions,
                                    onOpen = { navigate("habits/$it") },
                                    pendingAdd = route == "habits/new" || (action as? PaletteAction.OpenForm)?.form == "habit",
                                    onPendingConsumed = { backStackEntry.savedStateHandle["paletteAction"] = null })
                            }
                            else if (route.startsWith("habits/")) key(accountKey) {
                                com.knhdixh.attendance80.ui.habits.HabitDetail(visibleData, route.substringAfter('/').toLongOrNull() ?: -1, now, zone, habitActions,
                                    onBack = { if (pendingRoute != null) onPendingConsumed(); if (!navController.popBackStack()) navigate("habits") })
                            }
                            else if (route == "calendar") key(accountKey) {
                                val ui = runCatching { ApiClient.json.decodeFromString<com.knhdixh.attendance80.ui.calendar.CalendarUi>(calendarUi) }.getOrDefault(com.knhdixh.attendance80.ui.calendar.CalendarUi())
                                com.knhdixh.attendance80.ui.calendar.CalendarScreen(visibleData, now, zone, ui, { calendarUi = ApiClient.json.encodeToString(it) },
                                    calendarActions, onAction, onNavigate = { navigate(it) },
                                    onFocus = { id -> pending(PaletteAction.Focus("target", homeworkId = id), "focus") },
                                    pending = (action as? PaletteAction.OpenForm)?.takeIf { it.form.startsWith("calendar-") },
                                    onPendingConsumed = { backStackEntry.savedStateHandle["paletteAction"] = null })
                            }
                            else if (route == "breaks") key(accountKey) {
                                val pick = runCatching { ApiClient.json.decodeFromString<com.knhdixh.attendance80.ui.breaks.BreakPick>(breakPick) }.getOrDefault(com.knhdixh.attendance80.ui.breaks.BreakPick())
                                com.knhdixh.attendance80.ui.breaks.BreaksScreen(visibleData, now, zone, pick, { breakPick = ApiClient.json.encodeToString(it) },
                                    onSave = { p -> onSaveBreak(p.from, p.to, p.label) }, onRemove = { b -> onRemoveBreak(b.id) })
                            }
                            else if (route == "attendance") com.knhdixh.attendance80.ui.attendance.AttendanceScreen(visibleData, now, zone, onNavigate = { navigate(it) }, onAction = onAction, allEvents = state.allEvents, onDelete = ::deleteHomework, onPending = ::pending)
                            else if (route == "upcoming") key(accountKey) { com.knhdixh.attendance80.ui.upcoming.UpcomingScreen(visibleData, now, zone, upcomingWeek, { upcomingWeek = it }, onNavigate = { navigate(it) }, onAction = onAction, allEvents = state.allEvents, onDelete = ::deleteHomework, onPending = ::pending) }
                            else if (route == "homework") key(accountKey) { com.knhdixh.attendance80.ui.homework.HomeworkScreen(visibleData, state.allEvents, now, zone, { navigate(it) }, onAction, ::deleteHomework, ::pending) }
                            else if (route.startsWith("homework/")) key(accountKey) {
                                val id = route.substringAfter('/').takeUnless { it == "new" }?.toLongOrNull()
                                val draftKey = id?.toString() ?: "new"
                                var input by remember { mutableStateOf("") }
                                LaunchedEffect(encoded) {
                                    val form = action as? PaletteAction.OpenForm
                                    if (form != null && ((route == "homework/new" && form.form == "homework") || (id != null && form.form == "homework-edit" && form.entityId == id))) {
                                        input = form.input
                                        backStackEntry.savedStateHandle["paletteAction"] = null
                                    }
                                }
                                com.knhdixh.attendance80.ui.homework.HomeworkForm(visibleData, id, now, zone, input,
                                        onBack = {
                                            homeworkDrafts = HashMap(homeworkDrafts).apply { remove(draftKey) }
                                            if (pendingRoute != null) onPendingConsumed()
                                            if (!navController.popBackStack()) navigate("homework")
                                        }, onSave = onSaveHomework,
                                        lastCourse = lastHomeworkCourse, onLastCourse = { lastHomeworkCourse = it },
                                        storedDraft = homeworkDrafts[draftKey], onDraft = { draft ->
                                            if (homeworkDrafts[draftKey] != draft) homeworkDrafts = HashMap(homeworkDrafts).apply { put(draftKey, draft) }
                                        })
                            }
                            else if (route == "sessions") key(state.session) { com.knhdixh.attendance80.ui.sessions.SessionsScreen(state.data, now, zone,
                                com.knhdixh.attendance80.ui.sessions.SessionFilter(sessionCourse, sessionWhen, sessionQuery, sessionHidden),
                                { filter -> sessionCourse = filter.course; sessionWhen = filter.whenFilter; sessionQuery = filter.query; sessionHidden = filter.showHidden },
                                (action as? PaletteAction.SessionSearch)?.query, { backStackEntry.savedStateHandle["paletteAction"] = null }, onNavigate = { navigate(it) }, onAction = onAction) }
                            else if (route.startsWith("session/")) key(state.session) { com.knhdixh.attendance80.ui.sessions.SessionDetail(state.data?.copy(events = state.allEvents ?: state.data.events), route.substringAfter('/').toLongOrNull(), now, zone, onBack = { if (pendingRoute != null) onPendingConsumed(); if (!navController.popBackStack()) navigate("sessions") }, onNavigate = { navigate(it) }, onAction = onAction) }
                            else if (route == "courses") key(state.session) { com.knhdixh.attendance80.ui.courses.CoursesScreen(state.data, state.allEvents, now, zone, onNavigate = { navigate(it) }, onAction = onAction) }
                            else if (route.startsWith("course/")) key(state.session) { com.knhdixh.attendance80.ui.course.CourseScreen(state.data, state.allEvents, route.substringAfter('/').toLongOrNull(), now, zone, onNavigate = { navigate(it) }, onBack = { if (!navController.popBackStack()) navigate("courses") }, onAction = onAction) }
                            else ShellContent(route, state.data, now, zone, action = action, onNavigate = { navigate(it) }, onSignOut = ::signOut)
                        }
                    }
                }
            }
        }
    }
    val context = LocalContext.current
    if (showSearch) SearchSheet(state.data, onDismiss = { showSearch = false },
        onRoute = { navigate(it) }, onSignOut = ::signOut, now = now, zone = zone, recents = recents, onRecent = onRecent,
        onAction = { action, destination ->
            when (action) {
                is PaletteAction.CopyFeed -> (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Timetable", action.url + sanitizeScope(state.selected.distinct(), state.data?.calendars.orEmpty()).let { ids -> if (ids.isEmpty()) "" else "?groups=${ids.joinToString(",")}" }))
                is PaletteAction.OpenForm, is PaletteAction.Focus, is PaletteAction.SessionSearch -> {
                    if (destination != null) {
                        navigate(destination)
                        navController.currentBackStackEntry?.savedStateHandle?.set("paletteAction", ApiClient.json.encodeToString(action))
                    }
                }
                else -> onAction(action)
            }
        })
}

private data class RowData(val title: String, val detail: String = "", val route: String? = null, val key: String = route ?: title)
internal fun date(ms: Long, zone: ZoneId): String = "${Dates.formatDate(ms, zone)}, ${Dates.formatTime(ms, zone)}"
internal fun dateOnly(value: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(LocalDate.parse(value))
}.getOrDefault(value)

@Composable
private fun ShellContent(route: String, data: AppData?, now: Long, zone: ZoneId, action: PaletteAction?, onNavigate: (String) -> Unit, onSignOut: () -> Unit) {
    val events = data?.events.orEmpty().sortedBy { it.start_at }
    val courses = data?.courses.orEmpty()
    val rows: List<RowData> = when {
        route == "more" -> destinations.drop(4).map { RowData(it.label, route = it.route) }
        route == "today" -> events.filter { Instant.ofEpochMilli(it.start_at).atZone(zone).toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }
            .map { RowData(it.summary, date(it.start_at, zone), "session/${it.id}") }
        route == "attendance" -> courses.filter { it.tracked }.map { RowData(it.name, route = "course/${it.id}") }
        route == "upcoming" -> events.filter { it.end_at >= now }.map { RowData(it.summary, date(it.start_at, zone), "session/${it.id}") }
        route == "sessions" -> events.filter { event -> action !is PaletteAction.SessionSearch || (courses.any { it.id == event.course_id && it.tracked } && "${event.summary} ${event.location} ${event.note}".contains(action.query, ignoreCase = true)) }.map { RowData(it.summary, date(it.start_at, zone), "session/${it.id}") }
        route == "courses" -> courses.map { RowData(it.name, route = "course/${it.id}") }
        route == "focus" -> data?.focus?.sessions.orEmpty().map { RowData(it.label ?: "Focus", date(it.started_at, zone), key = "focus:${it.client_id}") }
        (route == "homework" || route == "homework/new") -> data?.homework.orEmpty().map { RowData(it.title, it.due_date?.let(::dateOnly).orEmpty(), "homework/${it.id}") }
        route.startsWith("homework/") -> data?.homework.orEmpty().filter { "homework/${it.id}" == route }.map { RowData(it.title, listOfNotNull(it.due_date?.let(::dateOnly), it.notes.takeIf(String::isNotBlank)).joinToString(" · "), key = "homework:${it.id}") }
        route == "habits" || route == "habits/new" -> data?.habits?.list.orEmpty().filterNot { it.archived }.map { RowData(it.name, route = "habits/${it.id}") }
        route.startsWith("habits/") -> data?.habits?.list.orEmpty().filter { "habits/${it.id}" == route }.map { RowData(it.name, key = "entity:${it.id}") }
        route == "countdowns" || route == "countdowns/new" -> data?.countdowns.orEmpty().map { RowData(it.title, date(it.at, zone), "countdowns/${it.id}") }
        route.startsWith("countdowns/") -> data?.countdowns.orEmpty().filter { "countdowns/${it.id}" == route }.map { RowData(it.title, date(it.at, zone), key = "countdown:${it.id}") }
        route.startsWith("session/") -> events.filter { "session/${it.id}" == route }.map { RowData(it.summary, listOf(date(it.start_at, zone), it.location).filter { s -> s.isNotBlank() }.joinToString(" · "), key = "session:${it.id}") }
        route.startsWith("course/") -> courses.filter { "course/${it.id}" == route }.map { RowData(it.name, key = "entity:${it.id}") }
        else -> emptyList()
    }
    val empty = when {
        route.startsWith("session/") -> "No sessions match."
        route.startsWith("course/") -> "No tracked courses."
        route.startsWith("homework/") && route != "homework/new" -> "Nothing due."
        route.startsWith("habits/") && route != "habits/new" -> "Build habits that stick. Add one, tick it off each day, and watch the grid fill in."
        route.startsWith("countdowns/") && route != "countdowns/new" -> "Count down to what matters, or count up from when something began."
        else -> when (route) {
        "today" -> "No classes today."
        "attendance", "courses" -> "No tracked courses."
        "sessions" -> "No sessions match."
        "upcoming" -> "Nothing scheduled"
        "homework", "homework/new" -> "Nothing due."
        "habits", "habits/new" -> "Build habits that stick. Add one, tick it off each day, and watch the grid fill in."
        "countdowns", "countdowns/new" -> "Count down to what matters, or count up from when something began."
        "focus" -> "Your focus sessions will show here."
        else -> ""
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (data == null && route != "more") item { CircularProgressIndicator() }
        else {
            if (action is PaletteAction.OpenForm || action is PaletteAction.Focus || route in setOf("homework/new", "habits/new", "countdowns/new")) item { Text("This action is not available yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (route == "today") item { Text("Classes", style = MaterialTheme.typography.titleMedium) }
            if (rows.isEmpty() && empty.isNotEmpty()) item { Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.key }) { row ->
                Column(Modifier.fillMaxWidth().then(if (row.route != null) Modifier.clickable { onNavigate(row.route) } else Modifier)) {
                    Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp)) {
                        Text(row.title, style = MaterialTheme.typography.bodyLarge)
                        if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
