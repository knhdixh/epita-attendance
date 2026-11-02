package com.knhdixh.attendance80.ui.calendar

import android.app.DatePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.knhdixh.attendance80.logic.CalendarMath
import com.knhdixh.attendance80.logic.DAY_START
import com.knhdixh.attendance80.logic.TAGS
import com.knhdixh.attendance80.logic.countsAttendance
import com.knhdixh.attendance80.logic.effectiveTag
import com.knhdixh.attendance80.logic.effortOf
import com.knhdixh.attendance80.logic.plannedBlock
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.search.PaletteAction
import com.knhdixh.attendance80.ui.theme.LocalExtraColors
import com.knhdixh.attendance80.ui.theme.paletteColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** What the calendar asks the app to do. */
interface CalendarActions {
    /** Plans, moves, resizes or unplans (null) one to-do. */
    suspend fun setPlan(h: HomeworkDto, plannedAt: Long?, minutes: Int?): Boolean = false
    suspend fun keep(items: List<Proposal>): Boolean = false
    suspend fun create(body: kotlinx.serialization.json.JsonObject): Boolean = false
    fun message(text: String) {}
}
object NoCalendarActions : CalendarActions

private sealed interface Sheet {
    data class Session(val id: Long) : Sheet
    data class Plan(val id: Long) : Sheet
    data class New(val day: Long, val start: Long, val end: Long) : Sheet
}

private fun fmt(ms: Long, zone: ZoneId, pattern: String) = DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochMilli(ms).atZone(zone))
private fun time(ms: Long, zone: ZoneId) = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(ms).atZone(zone))
private fun span(s: Long, e: Long, zone: ZoneId) = "${time(s, zone)}–${time(e, zone)}"
private fun minutesOfDay(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }

/** The title over the grid, as `title()` on the web. */
fun calendarTitle(view: String, date: Long, p: CalendarMath.Period, zone: ZoneId): String = when (view) {
    "month" -> fmt(date, zone, "MMMM yyyy")
    "day" -> fmt(p.start, zone, "EEEE d MMMM")
    else -> {
        val a = p.days.first(); val b = p.days.last()
        if (fmt(a, zone, "MMMM") == fmt(b, zone, "MMMM")) fmt(a, zone, "MMMM yyyy") else "${fmt(a, zone, "MMM")} – ${fmt(b, zone, "MMM yyyy")}"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(data: AppData?, now: Long, zone: ZoneId, ui: CalendarUi, onUi: (CalendarUi) -> Unit,
                   actions: CalendarActions, onAction: (PaletteAction) -> Unit, onNavigate: (String) -> Unit,
                   onFocus: (Long) -> Unit, pending: PaletteAction.OpenForm? = null, onPendingConsumed: () -> Unit = {}) {
    if (data == null) { Box(Modifier.fillMaxSize().padding(16.dp)) { CircularProgressIndicator() }; return }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val view = ui.view.ifEmpty { if (wide) "week" else "3day" }
    val date = ui.date ?: now
    val coroutines = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val scroll = rememberScrollState()
    var scrollToNow by remember { mutableStateOf(true) }
    var sheetHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    fun set(next: CalendarUi) { onUi(next.copy(view = next.view.ifEmpty { view })) }
    fun nav(dir: Int) { sheet = null; scrollToNow = true; set(ui.copy(date = if (dir == 0) now else CalendarMath.shift(view, date, dir, zone))) }
    fun openDay(day: Long) { sheet = null; scrollToNow = true; set(ui.copy(view = "day", date = day)) }
    fun plan() {
        when (val r = autoPlanNow(data, now, zone)) {
            is AutoPlanResult.Nothing -> actions.message(r.message)
            is AutoPlanResult.Planned -> {
                // Show the week the plan is in.
                val p = CalendarMath.period(view, date, zone)
                val nextView = if (view == "month" || view == "day") (if (wide) "week" else "3day") else view
                val nextDate = if (view == "month" || view == "day" || now < p.start || now >= p.end) now else date
                sheet = null; scrollToNow = true
                onUi(ui.copy(view = nextView, date = nextDate, proposals = r.proposals, unplaced = r.unplaced, placing = null))
            }
        }
    }
    // Palette and other pages: "Plan my week", a view, or a planned to-do's day.
    LaunchedEffect(pending) {
        val form = pending ?: return@LaunchedEffect
        when (form.form) {
            "calendar-plan" -> plan()
            "calendar-view" -> { scrollToNow = true; set(ui.copy(view = form.input)) }
            "calendar-show" -> form.input.toLongOrNull()?.let { ms -> scrollToNow = true; set(ui.copy(date = ms, view = if (view == "month") (if (wide) "week" else "3day") else view)) }
        }
        onPendingConsumed()
    }
    fun placeAt(h: HomeworkDto, start: Long) {
        set(ui.copy(placing = null)); sheet = null
        val minutes = if (h.planned_at != null && (h.planned_minutes ?: 0) > 0) h.planned_minutes!! else dropLength(h)
        coroutines.launch { if (actions.setPlan(h, start, minutes)) actions.message("Planned “${h.title}” for ${fmt(start, zone, "EEE d MMM")} ${time(start, zone)}.") }
    }
    val hw = data.homework.associateBy { it.id }
    val placing = ui.placing?.let { hw[it] }
    fun slot(day: Long, start: Long) {
        if (placing != null) return placeAt(placing, start)
        if (sheet != null) { sheet = null; return }
        sheet = Sheet.New(day, start, minOf(CalendarMath.addDays(day, 1, zone), start + NEW_BLOCK * MIN))
    }

    val p = CalendarMath.period(view, date, zone)
    val d = remember(data, ui, p.start, p.end, zone) { collect(data, ui, p.start, p.end, zone) }
    val todos = unplannedTodos(data.homework, now).filter { h -> ui.proposals.orEmpty().none { it.id == h.id } }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // The bar: previous / today / next, the title (a date picker), the views, and Plan my week.
        val context = LocalContext.current
        val pickerTheme = if (MaterialTheme.colorScheme.background.luminance() < .5f) com.knhdixh.attendance80.R.style.HomeworkPickerDark else com.knhdixh.attendance80.R.style.HomeworkPickerLight
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ nav(-1) }, Modifier.testTag("calendar-prev")) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous") }
            TextButton({ nav(0) }, Modifier.testTag("calendar-today")) { Text("Today") }
            IconButton({ nav(1) }, Modifier.testTag("calendar-next")) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next") }
            TextButton(onClick = {
                val l = Instant.ofEpochMilli(date).atZone(zone).toLocalDate()
                DatePickerDialog(context, pickerTheme, { _, y, m, dd -> scrollToNow = true; set(ui.copy(date = LocalDate.of(y, m + 1, dd).atTime(12, 0).atZone(zone).toInstant().toEpochMilli())) },
                    l.year, l.monthValue - 1, l.dayOfMonth).show()
            }, modifier = Modifier.weight(1f).testTag("calendar-title")) {
                Text(calendarTitle(view, date, p, zone), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VIEWS.forEach { (v, l) -> FilterChip(view == v, { sheet = null; scrollToNow = true; set(ui.copy(view = v)) }, { Text(l) }, modifier = Modifier.testTag("calendar-view-$v")) }
            AssistChip({ plan() }, { Text("Plan my week") }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) }, modifier = Modifier.testTag("calendar-plan"))
        }
        ui.proposals?.let { props -> ProposalBanner(props, ui.unplaced.mapNotNull { hw[it] },
            onKeep = { coroutines.launch { if (actions.keep(props)) { onUi(ui.copy(proposals = null, unplaced = emptyList())); actions.message("Planned ${props.size} to-do${if (props.size == 1) "" else "s"}.") } } },
            onDiscard = { onUi(ui.copy(proposals = null, unplaced = emptyList())) }) }
        placing?.let { h ->
            Banner("calendar-placing") { Text("Tap a time to plan ", Modifier.alignByBaseline()); Text(h.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).alignByBaseline(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton({ onUi(ui.copy(placing = null)) }) { Text("Cancel") } }
        }
        // Swipe left or right for the next or previous period.
        val swipe = with(LocalDensity.current) { 60.dp.toPx() }
        val swipeModifier = Modifier.pointerInput(view, date) {
            var dx = 0f
            detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (abs(dx) > swipe) nav(if (dx < 0) 1 else -1) }) { _, delta -> dx += delta }
        }
        if (view != "month") Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { GridHeader(d, p, view, now, zone, onOpenDay = ::openDay, onSession = { sheet = Sheet.Session(it) }, onDue = { onNavigate("homework/$it") }, onChip = onNavigate) }
            if (wide) Spacer(Modifier.width(260.dp))
        }
        Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).testTag("calendar-list")) {
            Column(Modifier.weight(1f)) {
                if (view == "month") MonthView(d, p, date, now, zone, swipeModifier, onOpen = { day ->
                    if (placing != null) { scrollToNow = true; set(ui.copy(date = day, view = if (wide) "week" else "3day")) } else openDay(day) })
                else TimeGrid(data, d, p, view, now, zone, sheet, swipeModifier, scroll, scrollToNow, { scrollToNow = false },
                    onSlot = ::slot, onOpenDay = ::openDay,
                    onSession = { sheet = Sheet.Session(it) },
                    onPlan = { id -> if (placing != null) hw[id]?.let { h -> plannedBlock(h)?.let { slot(CalendarMath.startOfDay(it.first, zone), it.first) } } else sheet = Sheet.Plan(id) },
                    onGhost = { id -> onUi(ui.copy(proposals = ui.proposals?.filterNot { it.id == id })) },
                    onMoved = { id, start, minutes, ghost ->
                        if (ghost) onUi(ui.copy(proposals = ui.proposals?.map { if (it.id == id) it.copy(start = start, minutes = minutes) else it }))
                        else hw[id]?.let { h -> coroutines.launch { actions.setPlan(h, start, minutes) } }
                    },
                    onDue = { onNavigate("homework/$it") }, onChip = onNavigate)
                if (!wide && view != "month") Tray(todos, d, now, zone, ui, onUi)
            }
            if (wide && view != "month") Box(Modifier.width(260.dp)) { Tray(todos, d, now, zone, ui, onUi) }
        }
        // Room for the panel below, so the end of the grid stays reachable.
        if (sheet != null) Spacer(Modifier.height(sheetHeight))
    }
        // The popover: a panel at the bottom, as the web shows it on a phone. Back or a tap on the grid closes it.
        sheet?.let { s ->
        androidx.activity.compose.BackHandler { sheet = null }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 460.dp).imePadding()
            .onGloballyPositioned { sheetHeight = with(density) { it.size.height.toDp() } }.shadow(8.dp, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(LocalExtraColors.current.sidebar, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).testTag("calendar-sheet")) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth()) { IconButton({ sheet = null }, Modifier.align(Alignment.TopEnd).testTag("calendar-sheet-close")) {
                    Icon(androidx.compose.material.icons.Icons.Outlined.Close, "Close") } }
                when (s) {
                    is Sheet.Session -> data.events.find { it.id == s.id }?.let { SessionSheet(it, data, d, now, zone, onAction, onNavigate = { sheet = null; onNavigate(it) }) } ?: run { sheet = null }
                    is Sheet.Plan -> hw[s.id]?.takeIf { it.planned_at != null }?.let { h -> PlanSheet(h, d, data, now, zone,
                        onDone = { onAction(PaletteAction.ToggleHomework(h.id, !h.done)) },
                        onFocus = { sheet = null; onFocus(h.id) },
                        onLength = { m -> coroutines.launch { actions.setPlan(h, h.planned_at, m) } },
                        onMove = { sheet = null; onUi(ui.copy(placing = h.id)) },
                        onUnplan = { sheet = null; coroutines.launch { actions.setPlan(h, null, null) } },
                        onEdit = { sheet = null; onNavigate("homework/${h.id}") }) } ?: run { sheet = null }
                    is Sheet.New -> NewSheet(s, data, todos, zone,
                        onCreate = { title, course, asDue ->
                            val minutes = maxOf(SNAP, ((s.end - s.start) / MIN).toInt())
                            val body = buildJsonObject {
                                put("title", title); put("notes", ""); course?.let { put("course_id", it) } ?: put("course_id", JsonNull)
                                put("due_date", localDate(s.start, zone)); put("tz", zone.id)
                                if (asDue) { put("due_time", "%02d:%02d".format(minutesOfDay(s.start, zone) / 60, minutesOfDay(s.start, zone) % 60)); put("remind_minutes", 1440) }
                                else { put("due_time", JsonNull); put("remind_minutes", JsonNull); put("effort_minutes", minutes); put("planned_at", s.start); put("planned_minutes", minutes) }
                            }
                            coroutines.launch {
                                if (actions.create(body)) { sheet = null; actions.message(if (asDue) "Added “$title”, due ${time(s.start, zone)}." else "Planned “$title”.") }
                            }
                        },
                        onHere = { h -> sheet = null; coroutines.launch { actions.setPlan(h, s.start, dropLength(h)) } })
                }
            }
        }
        }
    }
}

@Composable
private fun Banner(tag: String, content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(tag)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun ProposalBanner(props: List<Proposal>, left: List<HomeworkDto>, onKeep: () -> Unit, onDiscard: () -> Unit) {
    val n = props.size
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("calendar-proposal")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (n > 0) "Planned $n to-do${if (n == 1) "" else "s"} in your free time, each before it's due." else "Nothing could be planned.")
            if (left.isNotEmpty()) Text("No room before the deadline for ${left.take(3).joinToString { "“${it.title}”" }}${if (left.size > 3) " and ${left.size - 3} more" else ""}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (n > 0) Text("Tap one to leave it out, or long-press and drag it to change it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (n > 0) Button(onKeep, Modifier.testTag("calendar-keep")) { Text("Keep") }
                OutlinedButton(onDiscard, Modifier.testTag("calendar-discard")) { Text(if (n > 0) "Discard" else "Close") }
            }
        }
    }
}

@Composable
private fun TimeGrid(
    data: AppData, d: Collected, p: CalendarMath.Period, view: String, now: Long, zone: ZoneId, sheet: Sheet?, swipe: Modifier,
    scroll: ScrollState, scrollToNow: Boolean, onScrolled: () -> Unit,
    onSlot: (Long, Long) -> Unit, onOpenDay: (Long) -> Unit, onSession: (Long) -> Unit, onPlan: (Long) -> Unit, onGhost: (Long) -> Unit,
    onMoved: (Long, Long, Int, Boolean) -> Unit, onDue: (Long) -> Unit, onChip: (String) -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val today = CalendarMath.startOfDay(now, zone)
    val days = p.days.map { dayItems(d, it, zone) }
    val (lo, hi) = hourRange(days, zone)
    val hh: Dp = if (wide) 48.dp else 44.dp
    val hhPx = with(LocalDensity.current) { hh.toPx() }
    val gutter = if (wide) 52.dp else 40.dp
    val weights = columnWeights(days, view, today, zone)
    fun y(ms: Long, day: Long): Float {
        val h = when { ms <= day -> 0.0; ms >= CalendarMath.addDays(day, 1, zone) -> 24.0; else -> minutesOfDay(ms, zone) / 60.0 }
        return ((h - lo) * hhPx).toFloat()
    }
    val line = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current


    // The grid: hour labels, then one column per day.
    val gridHeight = hh * (hi - lo)
    var gridTop by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(scrollToNow, p.start) {
        if (!scrollToNow) return@LaunchedEffect
        withFrameNanos { } // the grid's position is known after the first layout
        // Brings now into view (or the first item of the period, or 8:00), a third of the way down.
        val todayCol = days.find { it.day == today }
        val first = days.flatMap { x -> x.timed.map { y(it.start_at, x.day) } + x.blocks.map { y(it.start, x.day) } }.minOrNull()
        val target = when { todayCol != null -> y(now, today) - scroll.viewportSize / 3f; first != null -> first - 12; else -> (DAY_START - lo) * hhPx }
        scroll.scrollTo((gridTop + target).toInt().coerceIn(0, scroll.maxValue))
        onScrolled()
    }
    // When a panel opens, bring what it's about to the top, above the panel.
    val margin = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(sheet) {
        val at = when (sheet) {
            is Sheet.New -> sheet.start
            is Sheet.Session -> data.events.find { it.id == sheet.id }?.start_at
            is Sheet.Plan -> data.homework.find { it.id == sheet.id }?.planned_at
            null -> null
        } ?: return@LaunchedEffect
        val day = days.find { at >= it.day && at < CalendarMath.addDays(it.day, 1, zone) } ?: return@LaunchedEffect
        scroll.animateScrollTo((gridTop + y(at, day.day) - margin).toInt().coerceIn(0, scroll.maxValue))
    }
    Row(Modifier.fillMaxWidth().height(gridHeight).onGloballyPositioned { gridTop = it.positionInParent().y }.then(swipe)) {
        Box(Modifier.width(gutter).fillMaxHeight()) {
            for (i in 1 until (hi - lo)) {
                val label = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(java.time.LocalTime.of((lo + i) % 24, 0)).replace(":00", "")
                Text(label, Modifier.offset(y = hh * i - 7.dp).fillMaxWidth().padding(end = 4.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
            }
        }
        days.forEachIndexed { i, x ->
            DayColumn(x, d, Modifier.weight(weights[i]).fillMaxHeight(), lo, hi, hhPx, zone, now, today, sheet, line, ::y,
                onSlot, onSession, onPlan, onGhost, onMoved, onDue, density)
        }
    }
}

// An empty weekend day in the week view is narrow.
private fun columnWeights(days: List<DayItems>, view: String, today: Long, zone: ZoneId) =
    days.map { x -> if (view == "week" && Instant.ofEpochMilli(x.day).atZone(zone).dayOfWeek.value >= 6 && x.day != today && x.empty) .45f else 1f }

/** Day headers and the all-day row: pinned above the grid while it scrolls. */
@Composable
private fun GridHeader(d: Collected, p: CalendarMath.Period, view: String, now: Long, zone: ZoneId,
                       onOpenDay: (Long) -> Unit, onSession: (Long) -> Unit, onDue: (Long) -> Unit, onChip: (String) -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val today = CalendarMath.startOfDay(now, zone)
    val days = p.days.map { dayItems(d, it, zone) }
    val gutter = if (wide) 52.dp else 40.dp
    val weights = columnWeights(days, view, today, zone)
    val line = MaterialTheme.colorScheme.outlineVariant
    // Day headers and the all-day row.
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Box(Modifier.width(gutter), contentAlignment = Alignment.Center) { Text("W${CalendarMath.isoWeek(p.days.first(), zone)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        days.forEachIndexed { i, x ->
            Column(Modifier.weight(weights[i]).clickable { onOpenDay(x.day) }.padding(vertical = 4.dp).testTag("calendar-day-${localDate(x.day, zone)}"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmt(x.day, zone, "EEE"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                val isToday = x.day == today
                Box(Modifier.size(28.dp).background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                    Text(fmt(x.day, zone, "d"), fontWeight = FontWeight.Bold, color = if (isToday) MaterialTheme.colorScheme.onPrimary else if (x.day < today) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
                if (view == "day") {
                    val classHours = x.timed.sumOf { (minOf(it.end_at, CalendarMath.addDays(x.day, 1, zone)) - maxOf(it.start_at, x.day)).toDouble() / HOUR }
                    val half = Math.round(classHours * 2) / 2.0
                    val hours = if (half == floor(half)) half.toLong().toString() else half.toString()
                    val meta = listOfNotNull(if (classHours > 0) "$hours h class" else null, if (x.blocks.isNotEmpty()) "${x.blocks.size} planned" else null).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (days.any { it.allDay.isNotEmpty() || it.dueAllDay.isNotEmpty() || it.cds.isNotEmpty() || it.brks.isNotEmpty() }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Box(Modifier.width(gutter), contentAlignment = Alignment.Center) { Text("all-day", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            days.forEachIndexed { i, x ->
                Column(Modifier.weight(weights[i]).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    x.brks.forEach { b -> Chip(b.label.ifBlank { "Break" }, LocalExtraColors.current.orange) { onChip("breaks") } }
                    x.allDay.forEach { e -> Chip(e.summary, paletteColor(d.color(e.course_id))) { onSession(e.id) } }
                    x.dueAllDay.forEach { h -> Chip("Due: ${h.title}", if (!h.done && (h.due_at ?: 0) < now) LocalExtraColors.current.red else MaterialTheme.colorScheme.onSurfaceVariant, done = h.done) { onDue(h.id) } }
                    x.cds.forEach { c -> Chip(c.cd.title, paletteColor(c.cd.color)) { onChip("countdowns") } }
                }
            }
        }
    }
    HorizontalDivider(color = line)
}

@Composable
private fun Chip(text: String, color: Color, done: Boolean = false, onClick: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().background(color.copy(alpha = .16f), RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textDecoration = if (done) TextDecoration.LineThrough else null)
}

@Composable
private fun DayColumn(
    x: DayItems, d: Collected, modifier: Modifier, lo: Int, hi: Int, hhPx: Float, zone: ZoneId, now: Long, today: Long, sheet: Sheet?, line: Color,
    y: (Long, Long) -> Float, onSlot: (Long, Long) -> Unit, onSession: (Long) -> Unit, onPlan: (Long) -> Unit, onGhost: (Long) -> Unit,
    onMoved: (Long, Long, Int, Boolean) -> Unit, onDue: (Long) -> Unit, density: androidx.compose.ui.unit.Density,
) {
    val nowLine = MaterialTheme.colorScheme.error
    fun startAt(yPx: Float): Long {
        val m = ((yPx / hhPx) * 60 + lo * 60).toDouble()
        return CalendarMath.atMinutes(x.day, (floor(m / SNAP).toInt() * SNAP).coerceIn(0, 24 * 60 - SNAP), zone)
    }
    BoxWithConstraints(modifier
        .background(if (x.day < today) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f) else Color.Transparent)
        .drawBehind {
            for (i in 0..(hi - lo)) drawLine(line, Offset(0f, i * hhPx), Offset(size.width, i * hhPx), 1f)
            drawLine(line, Offset(0f, 0f), Offset(0f, size.height), 1f)
        }
        .pointerInput(x.day, lo) { detectTapGestures { o -> onSlot(x.day, startAt(o.y)) } }
        .testTag("calendar-col-${localDate(x.day, zone)}")) {
        val width = maxWidth
        // Focus time, under everything.
        x.fx.forEach { f ->
            val top = y(maxOf(f.started_at, x.day), x.day)
            val bottom = y(minOf(f.ended_at, CalendarMath.addDays(x.day, 1, zone)), x.day)
            Box(Modifier.offset(y = with(density) { top.toDp() }).width(4.dp).height(with(density) { maxOf(3f, bottom - top).toDp() })
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .5f)).semantics { contentDescription = "Focused ${(f.focus_seconds / 60.0).roundToInt()} min${f.label?.let { ": $it" }.orEmpty()}" })
        }
        // Sessions, planned blocks and proposals; overlapping items share the width.
        data class It(val key: Int, val kind: String, val start: Long, val end: Long, val e: EventDto? = null, val b: Block? = null)
        val items = x.timed.map { It(0, "ev", maxOf(it.start_at, x.day), minOf(it.end_at, CalendarMath.addDays(x.day, 1, zone)), e = it) } +
            x.blocks.map { It(0, "plan", it.start, it.end, b = it) } + x.ghosts.map { It(0, "ghost", it.start, it.end, b = it) }
        val keyed = items.mapIndexed { i, it -> it.copy(key = i) }
        // Short items still take 20 minutes of room, so they don't sit on top of the next one.
        val lay = CalendarMath.layout(keyed.map { CalendarMath.Item(it.key, it.start, maxOf(it.end, it.start + 20 * MIN)) })
        val clash = clashes(x.timed)
        keyed.forEach { it ->
            val pos = lay.getValue(it.key)
            val top = y(it.start, x.day)
            val height = maxOf(with(density) { 18.dp.toPx() }, y(it.end, x.day) - top - 2)
            val m = Modifier.offset(x = width * pos.col / pos.cols, y = with(density) { top.toDp() }).width(width / pos.cols).height(with(density) { height.toDp() }).padding(horizontal = 1.dp)
            if (it.e != null) SessionBlock(it.e, d, m, height, it.e.id in clash, now, zone) { onSession(it.e.id) }
            else PlanBlock(it.b!!, it.kind == "ghost", d, m, height, now, zone, hhPx, width, density,
                onTap = { if (it.kind == "ghost") onGhost(it.b.h.id) else onPlan(it.b.h.id) },
                onMoved = { start, minutes -> onMoved(it.b.h.id, start, minutes, it.kind == "ghost") })
        }
        // Timed deadlines: a line with the title; several at one time share the line.
        x.dueTimed.groupBy { it.due_at!! }.forEach { (at, list) ->
            val over = list.any { !it.done && at < now }
            val c = if (over) LocalExtraColors.current.red else MaterialTheme.colorScheme.onSurfaceVariant
            val titles = list.joinToString { it.title }
            Column(Modifier.offset(y = with(density) { y(at, x.day).toDp() } - 1.dp).fillMaxWidth().clickable { onDue(list.first().id) }
                .semantics(mergeDescendants = true) { contentDescription = "Due ${time(at, zone)}: $titles" }) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(c))
                Text(titles, Modifier.padding(horizontal = 2.dp), style = MaterialTheme.typography.labelSmall, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (list.all { it.done }) TextDecoration.LineThrough else null)
            }
        }
        // The slot a new to-do goes in.
        (sheet as? Sheet.New)?.takeIf { it.day == x.day }?.let { s ->
            val top = y(s.start, x.day)
            Box(Modifier.offset(y = with(density) { top.toDp() }).fillMaxWidth().height(with(density) { maxOf(18f, y(s.end, x.day) - top).toDp() })
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .18f), RoundedCornerShape(4.dp)).testTag("calendar-sel")) {
                Text(span(s.start, s.end, zone), Modifier.padding(2.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (x.day == today) Box(Modifier.offset(y = with(density) { y(now, x.day).toDp() } - 1.dp).fillMaxWidth().height(2.dp).background(nowLine).testTag("calendar-now"))
    }
}

@Composable
private fun SessionBlock(e: EventDto, d: Collected, modifier: Modifier, height: Float, clash: Boolean, now: Long, zone: ZoneId, onClick: () -> Unit) {
    val course = d.byId[e.course_id]
    val counts = course != null && countsAttendance(course)
    val tag = effectiveTag(e, course)
    val past = e.start_at <= now
    val st = if (!counts) "" else when (e.status) { "absent" -> if (past) "absent" else "skip"; "excused" -> "excused"; "present" -> "present"; else -> if (past) "unmarked" else "" }
    val label = mapOf("absent" to "missed", "skip" to "planned skip", "excused" to "excused", "present" to "attended", "unmarked" to "unmarked")[st].orEmpty()
    val color = paletteColor(d.color(e.course_id))
    val x = LocalExtraColors.current
    val tagLabel = if (tag != "normal") TAGS[tag]?.label.orEmpty() else ""
    val density = LocalDensity.current
    Box(modifier.alpha(if (st == "absent" || st == "skip" || st == "excused") .55f else 1f)
        .background(color.copy(alpha = if (past) .14f else .22f), RoundedCornerShape(4.dp))
        .then(if (clash || tag == "exam") Modifier.border(1.5.dp, if (tag == "exam") x.red else x.orange, RoundedCornerShape(4.dp)) else Modifier)
        .drawBehind { drawRect(color, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
        .clickable(onClick = onClick)
        .semantics { contentDescription = "${e.summary}, ${span(e.start_at, e.end_at, zone)}${if (label.isNotEmpty()) ", $label" else ""}" }
        .testTag("calendar-ev-${e.id}")
        .padding(start = 5.dp, end = 2.dp, top = 2.dp)) {
        Column {
            Text((if (tagLabel.isNotEmpty()) "$tagLabel " else "") + e.summary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                maxLines = if (height > with(density) { 50.dp.toPx() }) 2 else 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (st == "absent" || st == "skip") TextDecoration.LineThrough else null)
            if (height > with(density) { 34.dp.toPx() }) Text(span(e.start_at, e.end_at, zone) + if (e.location.isNotBlank()) " · ${e.location}" else "",
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (st == "unmarked" || st == "present") Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(7.dp)
            .background(if (st == "present") x.green else Color.Transparent, CircleShape).border(1.dp, if (st == "present") x.green else x.orange, CircleShape))
    }
}

@Composable
private fun PlanBlock(b: Block, ghost: Boolean, d: Collected, modifier: Modifier, height: Float, now: Long, zone: ZoneId, hhPx: Float, colWidth: Dp,
                      density: androidx.compose.ui.unit.Density, onTap: () -> Unit, onMoved: (Long, Int) -> Unit) {
    val h = b.h
    val missed = !ghost && !h.done && b.end <= now
    val color = paletteColor(d.color(h.course_id))
    val minutes = ((b.end - b.start) / MIN).toInt()
    // Long-press and drag moves the block (across days too); the grip at the bottom resizes it. Both snap to 15 minutes.
    var dragY by remember(b) { mutableFloatStateOf(0f) }
    var dragX by remember(b) { mutableFloatStateOf(0f) }
    var resize by remember(b) { mutableFloatStateOf(0f) }
    val snapPx = hhPx / 4
    val colPx = with(density) { colWidth.toPx() }
    fun moved(): Pair<Long, Int> {
        val steps = (dragY / snapPx).roundToInt()
        val dayShift = (dragX / colPx).roundToInt()
        val start = CalendarMath.addDays(b.start, dayShift, zone) + steps * SNAP * MIN
        return start to minutes
    }
    val shownHeight = height + resize
    Box(modifier
        .offset { androidx.compose.ui.unit.IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
        .height(with(density) { maxOf(with(density) { 18.dp.toPx() }, shownHeight).toDp() })
        .alpha(if (ghost) .75f else if (h.done) .55f else 1f)
        .background(if (ghost) Color.Transparent else color.copy(alpha = .14f), RoundedCornerShape(4.dp))
        .border(if (ghost || missed) 1.5.dp else 1.dp, if (missed) LocalExtraColors.current.red else color, RoundedCornerShape(4.dp))
        .clickable(onClickLabel = if (ghost) "Leave out" else "Open", onClick = onTap)
        // One gesture, so a small grip's widened touch target can't take the move: a long press on the bottom edge resizes.
        .then(if (h.done) Modifier else Modifier.pointerInput(b) {
            var resizing = false
            val edge = 14.dp.toPx()
            detectDragGesturesAfterLongPress(
                onDragStart = { o -> resizing = o.y >= size.height - edge },
                onDragEnd = {
                    if (resizing) {
                        val m = (minutes + (resize / snapPx).roundToInt() * SNAP).coerceIn(SNAP, 12 * 60); resize = 0f
                        if (m != minutes) onMoved(b.start, m)
                    } else {
                        val (start, m) = moved(); dragX = 0f; dragY = 0f
                        if (start != b.start) onMoved(start, m)
                    }
                },
                onDragCancel = { dragX = 0f; dragY = 0f; resize = 0f },
            ) { change, amount ->
                change.consume()
                if (resizing) resize = (resize + amount.y).coerceAtLeast(-(height - snapPx)) else { dragX += amount.x; dragY += amount.y }
            }
        })
        .semantics { contentDescription = "${if (ghost) "Proposed: " else "Planned: "}${h.title}, ${span(b.start, b.end, zone)}" }
        .testTag(if (ghost) "calendar-ghost-${h.id}" else "calendar-plan-${h.id}")
        .padding(horizontal = 4.dp, vertical = 2.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (h.done) Icon(Icons.Outlined.Check, null, Modifier.size(12.dp))
                Text(h.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (h.done) TextDecoration.LineThrough else null)
            }
            if (shownHeight > with(density) { 34.dp.toPx() }) {
                val end = b.start + ((minutes + (resize / snapPx).roundToInt() * SNAP) * MIN)
                Text(span(b.start, end, zone) + if (missed) " · not done" else "", style = MaterialTheme.typography.labelSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!h.done) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(10.dp).testTag("calendar-grip-${h.id}")) {
            Box(Modifier.align(Alignment.Center).width(16.dp).height(3.dp).background(color.copy(alpha = .6f), RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun MonthView(d: Collected, p: CalendarMath.Period, date: Long, now: Long, zone: ZoneId, swipe: Modifier, onOpen: (Long) -> Unit) {
    val today = CalendarMath.startOfDay(now, zone)
    val month = Instant.ofEpochMilli(date).atZone(zone).monthValue
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val x = LocalExtraColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).then(swipe)) {
        Row { p.days.take(7).forEach { Text(fmt(it, zone, if (wide) "EEE" else "EEEEE"), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        p.days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().height(if (wide) 104.dp else 84.dp)) {
                week.forEach { day ->
                    val items = dayItems(d, day, zone)
                    val exams = items.timed.filter { effectiveTag(it, d.byId[it.course_id]) == "exam" }
                    data class Line(val text: String, val color: Color, val done: Boolean = false)
                    val lines = items.brks.take(1).map { Line(it.label.ifBlank { "Break" }, x.orange) } +
                        exams.map { Line("${time(it.start_at, zone)} ${it.summary}", paletteColor(d.color(it.course_id))) } +
                        (items.dueTimed + items.dueAllDay).map { Line(it.title, if (!it.done && (it.due_at ?: 0) < now) x.red else MaterialTheme.colorScheme.onSurfaceVariant, it.done) } +
                        items.blocks.map { Line("${time(it.start, zone)} ${it.h.title}", paletteColor(d.color(it.h.course_id)), it.h.done) } +
                        items.cds.map { Line(it.cd.title, paletteColor(it.cd.color)) }
                    val max = if (wide) 3 else 2
                    val label = "${fmt(day, zone, "EEEE d MMMM")}: ${items.timed.size} session${if (items.timed.size == 1) "" else "s"}"
                    Column(Modifier.weight(1f).fillMaxHeight().border(.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        .background(if (items.brks.isNotEmpty()) x.orange.copy(alpha = .08f) else Color.Transparent)
                        .clickable { onOpen(day) }.semantics(mergeDescendants = true) { contentDescription = label }
                        .testTag("calendar-cell-${localDate(day, zone)}").padding(2.dp)
                        .alpha(if (Instant.ofEpochMilli(day).atZone(zone).monthValue != month) .45f else 1f)) {
                        Box(Modifier.size(20.dp).background(if (day == today) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                            Text(fmt(day, zone, "d"), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                color = if (day == today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                        val dots = items.timed.filterNot { it in exams }.take(8)
                        if (dots.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            dots.forEach { e -> Box(Modifier.size(5.dp).background(paletteColor(d.color(e.course_id)).copy(alpha = if (e.status == "absent") .3f else 1f), CircleShape)) }
                        }
                        lines.take(max).forEach { l -> Text(l.text, style = MaterialTheme.typography.labelSmall, color = l.color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textDecoration = if (l.done) TextDecoration.LineThrough else null) }
                        if (lines.size > max) Text("${lines.size - max} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Tray(todos: List<HomeworkDto>, d: Collected, now: Long, zone: ZoneId, ui: CalendarUi, onUi: (CalendarUi) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("calendar-tray"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row { Text("To plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); if (todos.isNotEmpty()) Text("${todos.size}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (todos.isEmpty()) Text("Every open to-do has a time. Tap an empty slot to add one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        todos.take(40).forEach { h ->
            val on = ui.placing == h.id
            Row(Modifier.fillMaxWidth().background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable { onUi(ui.copy(placing = if (on) null else h.id)) }.padding(vertical = 6.dp, horizontal = 4.dp).testTag("calendar-todo-${h.id}"),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(paletteColor(d.color(h.course_id)), CircleShape)); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val missed = h.planned_at?.let { "missed ${fmt(it, zone, "EEE")} · " }.orEmpty()
                    val due = h.due_at?.let { fmt(it, zone, "EEE d MMM") + if (h.due_time != null) " ${time(it, zone)}" else "" }.orEmpty()
                    Text("${missed}due $due · ${lenLabel(effortOf(h))}", style = MaterialTheme.typography.bodySmall,
                        color = if ((h.due_at ?: 0) < now) LocalExtraColors.current.red else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (todos.isNotEmpty()) Text("Tap one, then a time. Plan my week places them all.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Show", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        LAYERS.forEach { (k, l) ->
            Row(Modifier.fillMaxWidth().clickable { onUi(ui.copy(layers = ui.layers + (k to !ui.on(k)))) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ui.on(k), { onUi(ui.copy(layers = ui.layers + (k to it))) }, Modifier.testTag("calendar-layer-$k")); Text(l)
            }
        }
    }
}

@Composable
private fun SessionSheet(e: EventDto, data: AppData, d: Collected, now: Long, zone: ZoneId, onAction: (PaletteAction) -> Unit, onNavigate: (String) -> Unit) {
    val course = d.byId[e.course_id] ?: data.courses.find { it.id == e.course_id }
    val counts = course != null && countsAttendance(course)
    val tag = effectiveTag(e, course)
    val past = e.start_at <= now
    val x = LocalExtraColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(paletteColor(d.color(e.course_id)), CircleShape)); Spacer(Modifier.width(8.dp))
        Text(e.summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    Text("${fmt(e.start_at, zone, "EEEE d MMMM")}, ${span(e.start_at, e.end_at, zone)}")
    if (e.location.isNotBlank()) Text(e.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (tag != "normal") Text(TAGS[tag]?.label?.lowercase().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (e.note.isNotBlank()) Text(e.note, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    val clashing = data.events.filter { o -> o.id != e.id && o.course_id != e.course_id && d.byId[o.course_id]?.tracked == true && o.start_at < e.end_at && e.start_at < o.end_at }
    if (clashing.isNotEmpty()) Text("Overlaps ${clashing.take(2).joinToString { it.summary }}", color = x.red)
    if (counts) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Triple("present", "✓", if (past) "Attended" else "Will attend"), Triple("absent", "✗", if (past) "Absent" else "Plan to skip"), Triple("excused", "E", "Excused (doesn't count)")).forEach { (s, l, desc) ->
                val color = when (s) { "present" -> x.green; "absent" -> x.red; else -> x.purple }
                FilterChip(e.status == s, { onAction(PaletteAction.SessionStatus(e.id, if (e.status == s) null else s)) }, { Text(l) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = .2f), selectedLabelColor = color),
                    modifier = Modifier.semantics { contentDescription = desc }.testTag("calendar-status-$s"))
            }
        }
        Text(when (e.status) { "absent" -> if (past) "Missed" else "Planned skip"; "present" -> if (past) "Attended" else "Will attend"; "excused" -> "Excused"; else -> if (past) "Not marked yet" else "" },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // As homeworkForSession on the web: open homework of this course due at its start.
    val due = data.homework.filter { !it.done && it.course_id == e.course_id && it.due_at == e.start_at }
    if (due.isNotEmpty()) { Text("Due at this session", style = MaterialTheme.typography.titleSmall); due.forEach { h -> TextButton({ onNavigate("homework/${h.id}") }) { Text(h.title) } } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ onNavigate("session/${e.id}") }) { Text("Note, tag and catch-up") }
        TextButton({ onNavigate("course/${e.course_id}") }) { Text("All ${course?.name ?: "course"} sessions →", maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanSheet(h: HomeworkDto, d: Collected, data: AppData, now: Long, zone: ZoneId, onDone: () -> Unit, onFocus: () -> Unit,
                      onLength: (Int) -> Unit, onMove: () -> Unit, onUnplan: () -> Unit, onEdit: () -> Unit) {
    val (s, e) = plannedBlock(h)!!
    val minutes = ((e - s) / MIN).toInt()
    val course = d.byId[h.course_id] ?: data.courses.find { it.id == h.course_id }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(h.done, { onDone() }, Modifier.semantics { contentDescription = "Done: ${h.title}" }.testTag("calendar-plan-done"))
        Text(h.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    Text("${fmt(s, zone, "EEEE d MMMM")}, ${span(s, e, zone)}")
    h.due_at?.let { Text("Due ${fmt(it, zone, "EEE d MMM")}${if (h.due_time != null) " ${time(it, zone)}" else ""}${course?.let { c -> " · ${c.name}" }.orEmpty()}",
        color = if (!h.done && it < now) LocalExtraColors.current.red else MaterialTheme.colorScheme.onSurfaceVariant) }
    if (h.notes.isNotBlank()) Text(if (h.notes.length > 140) h.notes.take(140) + "…" else h.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!h.done) Button(onFocus, Modifier.testTag("calendar-plan-focus")) { Text("Focus now") }
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton({ open = true }, Modifier.testTag("calendar-plan-length")) { Text(lenLabel(minutes)) }
            DropdownMenu(open, { open = false }) {
                (if (minutes in LENGTHS) LENGTHS else (LENGTHS + minutes).sorted()).forEach { m ->
                    DropdownMenuItem({ Text(lenLabel(m)) }, { open = false; if (m != minutes) onLength(m) }, modifier = Modifier.testTag("calendar-length-$m"))
                }
            }
        }
        OutlinedButton(onMove, Modifier.testTag("calendar-plan-move")) { Text("Move") }
        OutlinedButton(onUnplan, Modifier.testTag("calendar-plan-unplan")) { Text("Unplan") }
    }
    TextButton(onEdit) { Text("Edit details") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSheet(s: Sheet.New, data: AppData, todos: List<HomeworkDto>, zone: ZoneId, onCreate: (String, Long?, Boolean) -> Unit, onHere: (HomeworkDto) -> Unit) {
    var title by remember(s) { mutableStateOf("") }
    var course by remember(s) { mutableStateOf<Long?>(null) }
    val tracked = data.courses.filter { it.tracked }
    Text("${fmt(s.start, zone, "EEEE d MMM")} · ${span(s.start, s.end, zone)}", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(title, { title = it.take(200) }, placeholder = { Text("What will you work on?") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("calendar-new-title"))
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(tracked.find { it.id == course }?.name ?: "No course", {}, readOnly = true, label = { Text("Course") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("calendar-new-course"))
        ExposedDropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("No course") }, { course = null; open = false })
            tracked.forEach { c -> DropdownMenuItem({ Text(c.name) }, { course = c.id; open = false }) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button({ onCreate(title.trim(), course, false) }, enabled = title.isNotBlank(), modifier = Modifier.testTag("calendar-new-add")) { Text("Add") }
        TextButton({ onCreate(title.trim(), course, true) }, enabled = title.isNotBlank(), modifier = Modifier.testTag("calendar-new-due")) { Text("Add as a deadline at ${time(s.start, zone)} instead") }
    }
    val few = todos.take(5)
    if (few.isNotEmpty()) {
        Text("Or plan one here", style = MaterialTheme.typography.titleSmall)
        few.forEach { h -> TextButton({ onHere(h) }, Modifier.testTag("calendar-here-${h.id}")) { Text("${h.title} · ${lenLabel(effortOf(h))}") } }
    }
}

/** The JSON body for keeping proposals (`POST /api/homework/plan`). */
fun keepBody(items: List<Proposal>) = buildJsonObject {
    put("items", JsonArray(items.map { buildJsonObject { put("id", it.id); put("planned_at", it.start); put("planned_minutes", it.minutes) } }))
}

/** The JSON body for planning, moving or unplanning a to-do. */
fun planBody(plannedAt: Long?, minutes: Int?) = buildJsonObject {
    put("planned_at", plannedAt?.let(::JsonPrimitive) ?: JsonNull)
    put("planned_minutes", if (plannedAt == null) JsonNull else minutes?.let(::JsonPrimitive) ?: JsonNull)
}
