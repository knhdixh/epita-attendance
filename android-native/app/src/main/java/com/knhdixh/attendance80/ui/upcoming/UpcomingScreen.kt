package com.knhdixh.attendance80.ui.upcoming

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knhdixh.attendance80.logic.Dates
import com.knhdixh.attendance80.logic.groupFamilies
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.sessions.SessionRow
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UpcomingDay(val date: LocalDate, val past: Boolean, val sessions: List<EventDto>, val homework: List<HomeworkDto>)
data class UpcomingState(val data: AppData, val today: LocalDate, val zone: ZoneId, val offset: Int, val days: List<UpcomingDay>)

class UpcomingViewModel : ViewModel() {
    private val mutable = MutableStateFlow<UpcomingState?>(null)
    val state = mutable.asStateFlow()
    private var generation = 0
    fun update(data: AppData?, clock: Clock, zone: ZoneId, offset: Int) {
        val today = LocalDate.now(clock.withZone(zone))
        val week = offset.coerceAtLeast(0)
        val version = ++generation
        if (data == null) { mutable.value = null; return }
        if (mutable.value?.let { it.data == data && it.today == today && it.zone == zone && it.offset == week } == true) return
        mutable.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { derive(data, today, zone, week) }
            if (generation == version) mutable.value = result
        }
    }
    companion object {
        fun derive(data: AppData, today: LocalDate, zone: ZoneId, offset: Int): UpcomingState {
            val week = offset.coerceAtLeast(0)
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(week.toLong())
            val tracked = data.courses.filter { it.tracked }.map { it.id }.toSet()
            val sessions = data.events.filter { it.course_id in tracked }.sortedBy { it.start_at }
            val days = (0L..6L).map { i ->
                val date = monday.plusDays(i)
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                UpcomingDay(date, date < today, sessions.filter { it.start_at >= start && it.start_at < end },
                    data.homework.filter { !it.done && it.due_at != null && it.due_at >= start && it.due_at < end })
            }
            return UpcomingState(data, today, zone, week, days)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpcomingScreen(data: AppData?, now: Long, zone: ZoneId, offset: Int, onOffset: (Int) -> Unit,
    onNavigate: (String) -> Unit, onAction: (PaletteAction) -> Unit, model: UpcomingViewModel = viewModel(), allEvents: List<EventDto>? = null, onDelete: (HomeworkDto) -> Unit = {}, onPending: (PaletteAction, String) -> Unit = { _, _ -> }) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var previousOffset by remember { mutableIntStateOf(offset) }
    LaunchedEffect(offset) {
        if (previousOffset != offset) {
            list.scrollToItem(0)
            previousOffset = offset
        }
    }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    LaunchedEffect(data, today, zone, offset) { model.update(data, Clock.fixed(Instant.ofEpochMilli(now), zone), zone, offset) }
    val state by model.state.collectAsState()
    val s = state?.takeIf { it.data == data && it.today == today && it.zone == zone && it.offset == offset }
    if (s == null || data == null) { CircularProgressIndicator(Modifier.padding(16.dp)); return }
    val families = groupFamilies(data.courses).let { fs -> if (fs.any { f -> f.courses.any { it.tracked } }) emptyList() else fs }
    val locale = Locale.getDefault()
    val first = s.days.first().date
    val last = s.days.last().date
    val range = if (first.year == last.year && first.month == last.month) first.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale))
        else if (first.year == last.year) "${first.format(DateTimeFormatter.ofPattern("MMM", locale))} – ${last.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))}"
        else "${first.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))} – ${last.format(DateTimeFormatter.ofPattern("MMM yyyy", locale))}"
    val visibleDays = s.days.filterNot { it.past }
    val headerCount = if (families.isNotEmpty()) 2 else 1
    val indices = mutableMapOf<LocalDate, Int>()
    var itemIndex = headerCount
    visibleDays.forEach { day ->
        indices[day.date] = itemIndex
        itemIndex += 1 + if (day.sessions.isEmpty() && day.homework.isEmpty()) 1 else day.sessions.size + day.homework.size
    }
    LazyColumn(Modifier.fillMaxSize().testTag("upcoming-list"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (families.isNotEmpty()) item("groups") { Column { Text("Pick your groups for ${families.joinToString { it.family }}."); TextButton(onClick = { onNavigate("courses") }) { Text("Choose groups") } } }
        item("header") { Column {
            Text(range, style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = offset > 0, onClick = { onOffset((offset - 1).coerceAtLeast(0)) }, modifier = Modifier.testTag("upcoming-prev")) { Text("Previous week") }
                TextButton(enabled = offset > 0, onClick = { onOffset(0) }, modifier = Modifier.testTag("upcoming-today")) { Text("Today") }
                TextButton(onClick = { onOffset(offset + 1) }, modifier = Modifier.testTag("upcoming-next")) { Text("Next week") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                s.days.forEach { day ->
                    val count = day.sessions.size
                    val label = "${day.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale))}: $count ${if (count == 1) "session" else "sessions"}"
                    TextButton(enabled = !day.past, onClick = {
                        indices[day.date]?.let { index -> scope.launch { list.animateScrollToItem(index) } }
                    }, modifier = Modifier.widthIn(min = 54.dp).testTag("upcoming-day-${day.date}").semantics { contentDescription = label }, contentPadding = PaddingValues(4.dp)) {
                        Column { Text(day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)); Text(day.date.dayOfMonth.toString(), color = if (day.date == s.today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Text("•".repeat(count.coerceAtMost(4))) }
                    }
                }
            }
        } }
        visibleDays.forEach { day ->
            item("day:${day.date}") {
                val relative = when (day.date) { s.today -> " · Today"; s.today.plusDays(1) -> " · Tomorrow"; else -> "" }
                Text(day.date.format(DateTimeFormatter.ofPattern("d MMM · EEEE", locale)) + relative, Modifier.testTag("upcoming-section-${day.date}"), style = MaterialTheme.typography.titleSmall)
            }
            if (day.sessions.isEmpty() && day.homework.isEmpty()) item("empty:${day.date}") { Text("Nothing scheduled", Modifier.padding(vertical = 8.dp)) }
            day.sessions.forEach { event -> item("session:${event.id}") { SessionRow(event, data, now, zone, onNavigate, onAction) } }
            day.homework.forEach { hw -> item("homework:${hw.id}") { Box(Modifier.testTag("upcoming-homework-${hw.id}")) { com.knhdixh.attendance80.ui.homework.HomeworkRow(hw, data, allEvents ?: data.events, now, zone, onNavigate, onAction, onDelete, onPending) } } }
        }
        item("more") { TextButton(onClick = { onOffset(offset + 1) }, modifier = Modifier.testTag("upcoming-more-next")) { Text("Next week") } }
    }
}
