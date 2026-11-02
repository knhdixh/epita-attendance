package com.knhdixh.attendance80.ui.settings

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.knhdixh.attendance80.BuildConfig
import com.knhdixh.attendance80.logic.sanitizeScope
import com.knhdixh.attendance80.logic.toggleScope
import com.knhdixh.attendance80.net.*
import com.knhdixh.attendance80.ui.theme.LocalExtraColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** Plan 5.4: refuse files over the server's limit before sending them. */
const val MAX_ICS_BYTES = 5 * 1024 * 1024

private val LEADS = listOf(0 to "When it starts", 5 to "5 min before", 10 to "10 min before", 15 to "15 min before", 20 to "20 min before",
    30 to "30 min before", 45 to "45 min before", 60 to "1 hour before", 90 to "1 h 30 before", 120 to "2 hours before")
private val TRIGGERS = mapOf("cron" to "Automatic", "manual" to "Update now", "connect" to "Added", "upload" to "Uploaded")

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
private fun day(ms: Long, zone: ZoneId) = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(ms).atZone(zone))
private fun shortDay(ms: Long, zone: ZoneId) = DateTimeFormatter.ofPattern("EEE d MMM").format(Instant.ofEpochMilli(ms).atZone(zone))
private fun ago(ms: Long, now: Long, zone: ZoneId): String {
    val min = ((now - ms) / 60000.0).roundToInt()
    return when { min < 1 -> "just now"; min < 60 -> "$min min ago"; min < 1440 -> "${(min / 60.0).roundToInt()} h ago"; else -> shortDay(ms, zone) }
}

/** Up to [limit] bytes, so a huge file is never read whole (InputStream.readNBytes needs API 33). */
internal fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** A question before an action, as the web's confirm(). */
private data class Confirm(val text: String, val action: String, val run: suspend () -> Unit)

private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/** The timetable feed link for some calendars, as timetableFeedUrl in public/homework-ui.js. */
fun timetableFeedUrl(feedUrl: String, ids: List<Long>): String {
    val base = feedUrl.replace(Regex("\\.ics$"), "/timetable.ics")
    return if (ids.isEmpty()) base else "$base?groups=${ids.joinToString(",")}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(data: AppData?, allEvents: List<EventDto>?, scope: List<Long>, now: Long, zone: ZoneId,
                   actions: SettingsActions, onSignOut: () -> Unit, onNavigate: (String) -> Unit,
                   baseUrl: String = BuildConfig.BASE_URL,
                   request: com.knhdixh.attendance80.ui.search.PaletteAction.OpenForm? = null, onRequestConsumed: () -> Unit = {}) {
    if (data == null) { Box(Modifier.fillMaxSize().padding(16.dp)) { CircularProgressIndicator() }; return }
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var deletingAccount by rememberSaveable { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) { coroutines.launch { busy = true; try { block() } finally { busy = false } } }
    fun open(url: String) = runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
        .onFailure { actions.message("No browser to open the link.") }
    fun copy(label: String, text: String, done: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        actions.message(done)
    }
    fun share(text: String) = context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))

    // Calendars: which one is being edited, and whether the add box is open.
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var replacing by rememberSaveable { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val replaceId = replacing
        replacing = null
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
        val bytes = runCatching { resolver.openInputStream(uri)?.use { readAtMost(it, MAX_ICS_BYTES + 1) } }.getOrNull()
        when {
            bytes == null -> actions.message("Could not read that file.")
            bytes.size > MAX_ICS_BYTES -> actions.message("That file is over 5 MB.")
            else -> run { if (actions.uploadFile(bytes.toString(Charsets.UTF_8), name, replaceId)) adding = false }
        }
    }
    fun pickFile(replaceId: Long? = null) { replacing = replaceId; picker.launch(arrayOf("text/calendar", "text/plain", "application/octet-stream")) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var focusLink by remember { mutableIntStateOf(0) }
    // Saved palette requests: "Upload .ics file" opens the picker, "Add calendar" opens the add box and focuses its link field.
    // Each is cleared as soon as it is acted on, so restoring the screen never replays it.
    LaunchedEffect(request) {
        when (request?.form) {
            "calendar-upload" -> { onRequestConsumed(); pickFile() }
            "calendar" -> {
                adding = true
                // account, rules, notify, the Calendars heading, then one item per calendar, then the add box.
                listState.scrollToItem(4 + data.calendars.size)
                onRequestConsumed(); focusLink++
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("settings-list"), listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("account") { AccountPanel(data.user, onSignOut) }
        item("rules") { RulesPanel(data.user, busy) { t, u -> run { actions.saveRules(t, u) } } }
        item("notify") { NotifyPanel(data.notifications.settings, busy) { body -> run { actions.saveNotify(body) } } }
        calendarsPanel(data, allEvents ?: data.events, now, zone, busy, editing, adding,
            onEdit = { editing = it }, onAdding = { adding = it },
            onSave = { id, name, attendance, from -> run { if (actions.editCalendar(id, name, attendance, from)) editing = null } },
            onSync = { run { actions.syncCalendar(it) } }, onReplace = { pickFile(it) },
            onRemove = { c, text -> confirm = Confirm(text, "Remove") { actions.removeCalendar(c.id) } },
            onAddLink = { url, name, from -> run { if (actions.addLink(url, name, from)) adding = false } },
            onPick = { pickFile() }, onOpen = { open(baseUrl + it) }, focusLink = focusLink)
        item("signin") {
            SignInPanel(data.account, data.user, now, zone, busy,
                onAdd = { context.activity()?.let { a -> run { actions.addPasskey(a) } } },
                onRemove = { id -> confirm = Confirm("Remove this passkey? You won't be able to sign in with it any more.", "Remove") { actions.removePasskey(id) } })
        }
        item("tokens") { TokensPanel(actions, zone, busy, { run(it) }, { confirm = it }, { t -> copy("Token", t, "Token copied.") }) }
        item("feeds") {
            FeedsPanel(data, scope, busy, onCopy = { copy("Calendar link", it, "Link copied.") }, onShare = ::share, onOpen = { open(it) },
                onRotate = { confirm = Confirm("Create new feed links? Calendars subscribed to the old homework and timetable links will stop updating.", "Create") { actions.rotateFeed() } })
        }
        item("gcal") {
            GcalPanel(data.integrations, busy,
                onConnect = { run { actions.gcalConnectUrl()?.let { open(it) } } },
                onResync = { run { actions.gcalResync() } },
                onDisconnect = { confirm = Confirm("Disconnect Google Calendar? Existing events stay in your Google calendar \"Homework (Attendance80)\", and you can delete that calendar in Google Calendar.", "Disconnect") { actions.gcalDisconnect() } })
        }
        item("danger") {
            Panel("Delete data") {
                Text("Remove all imported sessions, courses and attendance marks from your account. Homework is kept.")
                OutlinedButton(enabled = !busy, onClick = {
                    val n = data.calendars.size
                    confirm = Confirm("Delete all your sessions, attendance marks and breaks?${if (n > 0) " Your ${if (n == 1) "calendar is" else "$n calendars are"} removed too." else ""} This cannot be undone.", "Delete") {
                        if (actions.deleteData()) onNavigate("today")
                    }
                }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("settings-wipe")) { Text("Delete all my data") }
                Text("Delete my account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text("Permanently delete your account and everything in it: calendar, attendance, homework, breaks, passkeys, habits, countdowns and Google Calendar connection (access is revoked; the \"Homework (Attendance80)\" calendar stays in your Google account for you to delete).")
                if (!deletingAccount) OutlinedButton(enabled = !busy, onClick = { deletingAccount = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("settings-delete-account")) { Text("Delete my account") }
                else {
                    // The web's prompt(), inline: type DELETE to confirm.
                    var typed by rememberSaveable { mutableStateOf("") }
                    Text("This permanently deletes your account and all its data. It cannot be undone.", fontWeight = FontWeight.Bold)
                    OutlinedTextField(typed, { typed = it }, label = { Text("Type DELETE to confirm") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("delete-account-confirm"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { deletingAccount = false; typed = "" }) { Text("Cancel") }
                        Button(enabled = !busy, onClick = {
                            if (typed.trim() != "DELETE") actions.message("Account not deleted: you need to type DELETE.")
                            else { deletingAccount = false; run { actions.deleteAccount() } }
                            typed = ""
                        }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag("delete-account-go")) { Text("Delete my account") }
                    }
                }
            }
        }
        item("legal") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open("$baseUrl/privacy") }) { Text("Privacy policy") }
                Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { open("$baseUrl/terms") }) { Text("Terms of service") }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(onDismissRequest = { confirm = null }, text = { Text(c.text) },
            confirmButton = { TextButton(onClick = { confirm = null; run { c.run() } }) { Text(c.action) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Panel(title: String, count: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            count?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        content()
    }
}

@Composable private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun AccountPanel(user: UserDto, onSignOut: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val initial = (user.name ?: user.email ?: "?").take(1).uppercase()
        Box(Modifier.size(44.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {}
            Text(initial, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            if (!user.picture.isNullOrBlank()) AsyncImage(user.picture, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(user.name ?: user.email.orEmpty(), fontWeight = FontWeight.Bold, modifier = Modifier.testTag("settings-name"))
            if (!user.email.isNullOrBlank()) Text(user.email, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
    }
}

@Composable
private fun RulesPanel(user: UserDto, busy: Boolean, onSave: (Double, String) -> Unit) {
    var percent by rememberSaveable(user.threshold) { mutableStateOf((user.threshold * 100).roundToInt().toString()) }
    var unit by rememberSaveable(user.unit) { mutableStateOf(user.unit) }
    Panel("Attendance rules") {
        val value = percent.toIntOrNull()
        OutlinedTextField(percent, { percent = it.filter(Char::isDigit).take(3) }, label = { Text("Minimum attendance") }, suffix = { Text("%") },
            isError = value !in 1..100, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("settings-threshold"))
        Text("Count attendance by", style = MaterialTheme.typography.bodyMedium)
        Column {
            listOf("sessions" to "Number of sessions", "hours" to "Hours (longer sessions weigh more)").forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().clickable { unit = k }.testTag("settings-unit-$k"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = unit == k, onClick = { unit = k }); Text(label)
                }
            }
        }
        Button(enabled = !busy && value in 1..100, onClick = { onSave(value!! / 100.0, unit) }, modifier = Modifier.testTag("settings-rules-save")) { Text("Save") }
        Hint("Unmarked future sessions are assumed attended. Mark a future session ✗ to plan a skip and see its effect. Excused (E) sessions are removed from the total, as are sessions tagged Optional.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotifyPanel(s: NotifySettingsDto, busy: Boolean, onSave: (kotlinx.serialization.json.JsonObject) -> Unit) {
    fun save(classes: Boolean = s.classes, lead: Int = s.classLead, habits: Boolean = s.habits, focus: Boolean = s.focus) =
        onSave(buildJsonObject { put("classes", classes); put("classLead", lead); put("habits", habits); put("focus", focus) })
    Panel("Notifications") {
        @Composable fun toggle(label: String, value: Boolean, tag: String, change: (Boolean) -> Unit) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                Switch(value, change, enabled = !busy, modifier = Modifier.testTag(tag))
            }
        }
        toggle("Classes", s.classes, "notify-classes") { save(classes = it) }
        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(open, { if (s.classes && !busy) open = it }) {
            OutlinedTextField(LEADS.find { it.first == s.classLead }?.second ?: "${s.classLead} min before", {}, readOnly = true, enabled = s.classes,
                label = { Text("When to remind you of a class") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("notify-lead"))
            ExposedDropdownMenu(open, { open = false }) {
                LEADS.forEach { (m, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; save(lead = m) }) }
            }
        }
        toggle("Habit reminders, at the time set on each habit", s.habits, "notify-habits") { save(habits = it) }
        toggle("Focus timer: when a focus or break ends", s.focus, "notify-focus") { save(focus = it) }
        Hint("Classes are the tracked courses you're going to: sessions marked absent or excused are skipped. These settings are shared with the website; this app will post them itself in a coming update.")
    }
}

private fun LazyListScope.calendarsPanel(
    data: AppData, allEvents: List<EventDto>, now: Long, zone: ZoneId, busy: Boolean, editing: Long?, adding: Boolean,
    onEdit: (Long?) -> Unit, onAdding: (Boolean) -> Unit, onSave: (Long, String, Boolean, String?) -> Unit,
    onSync: (Long) -> Unit, onReplace: (Long) -> Unit, onRemove: (CalendarDto, String) -> Unit,
    onAddLink: (String, String, String?) -> Unit, onPick: () -> Unit, onOpen: (String) -> Unit, focusLink: Int = 0,
) {
    val list = data.calendars
    item("calendars-heading") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Text("Calendars", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); if (list.isNotEmpty()) Text("${list.size}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    list.forEach { c ->
        item("calendar:${c.id}") {
            if (editing == c.id) CalendarEdit(c, busy, onCancel = { onEdit(null) }, onSave = { n, a, f -> onSave(c.id, n, a, f) })
            else CalendarRow(c, now, zone, busy, onSync = { onSync(c.id) }, onReplace = { onReplace(c.id) }, onEdit = { onEdit(c.id) }, onRemove = {
                // Only sessions no other calendar has are deleted; shared ones stay.
                val only = allEvents.count { it.cals.size == 1 && it.cals[0] == c.id }
                val shared = c.sessions - only
                onRemove(c, "Remove \"${c.name}\"? ${if (only > 0) "${plural(only, "session")} only in it, and their attendance marks, are deleted." else "No sessions are deleted."}" +
                    (if (shared > 0) " ${plural(shared, "shared session")} stay in your other calendars." else "") + (if (c.kind == "link") " It stops updating." else ""))
            })
        }
    }
    item("calendar-add") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (adding || list.isEmpty()) AddBox(busy, cancel = list.isNotEmpty(), onCancel = { onAdding(false) }, onAddLink = onAddLink, onPick = onPick, onOpen = onOpen, focusRequest = focusLink)
            else TextButton(onClick = { onAdding(true) }, modifier = Modifier.testTag("calendar-add-open")) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Add calendar") }
            Hint("A session in several calendars (like a lecture for every group) is stored and counted once, and labelled with each calendar's name. Use Count at the top to count only some of them.")
            SyncLog(data.calendarSyncs, list, zone)
        }
    }
}

@Composable
private fun CalendarRow(c: CalendarDto, now: Long, zone: ZoneId, busy: Boolean, onSync: () -> Unit, onReplace: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    val status = c.error ?: if (c.kind == "link") "updated ${c.okAt?.let { ago(it, now, zone) } ?: "never"}" else "uploaded ${c.okAt?.let { ago(it, now, zone) }.orEmpty()}".trim()
    val where = if (c.kind == "link") c.site else c.fileName
    Row(Modifier.fillMaxWidth().testTag("calendar-${c.id}"), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (c.kind == "link") Icons.Outlined.Link else Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name)
            val meta = listOfNotNull(where?.takeIf { it.isNotBlank() }, plural(c.sessions, "session"), "attendance not counted".takeIf { !c.attendance }).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (c.error != null) LocalExtraColors.current.red else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (c.kind == "link") IconButton(onSync, enabled = !busy) { Icon(Icons.Outlined.Refresh, "Update now") }
        else IconButton(onReplace, enabled = !busy) { Icon(Icons.Outlined.Upload, "Upload a newer version") }
        IconButton(onEdit, enabled = !busy) { Icon(Icons.Outlined.Edit, "Edit ${c.name}") }
        IconButton(onRemove, enabled = !busy) { Icon(Icons.Outlined.Delete, "Remove ${c.name}") }
    }
}

@Composable
private fun DateButton(label: String, value: String, onChange: (String) -> Unit, tag: String) {
    val context = LocalContext.current
    val theme = if (MaterialTheme.colorScheme.background.luminance() < .5f) com.knhdixh.attendance80.R.style.HomeworkPickerDark else com.knhdixh.attendance80.R.style.HomeworkPickerLight
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val d = runCatching { LocalDate.parse(value) }.getOrElse { LocalDate.now() }
            DatePickerDialog(context, theme, { _, y, m, dd -> onChange(LocalDate.of(y, m + 1, dd).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
        }, modifier = Modifier.testTag(tag)) {
            Text("$label ${value.ifBlank { "the start" }.let { v -> runCatching { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(LocalDate.parse(v)) }.getOrDefault(v) }}")
        }
        if (value.isNotBlank()) TextButton(onClick = { onChange("") }) { Text("Clear") }
    }
}

@Composable
private fun CalendarEdit(c: CalendarDto, busy: Boolean, onCancel: () -> Unit, onSave: (String, Boolean, String?) -> Unit) {
    var name by rememberSaveable(c.id) { mutableStateOf(c.name) }
    var from by rememberSaveable(c.id) { mutableStateOf(c.from.orEmpty()) }
    var attendance by rememberSaveable(c.id) { mutableStateOf(c.attendance) }
    Column(Modifier.fillMaxWidth().testTag("calendar-edit-${c.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("calendar-edit-name"))
        if (c.kind == "link") DateButton("From", from, { from = it }, "calendar-edit-from")
        Row(Modifier.clickable { attendance = !attendance }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(attendance, { attendance = it }, modifier = Modifier.testTag("calendar-edit-attendance")); Text("Count attendance")
        }
        Hint("Off for a school that doesn't take attendance: its courses stay in your timetable and plan, without the 80% maths.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(enabled = !busy && name.isNotBlank(), onClick = { onSave(name, attendance, from) }, modifier = Modifier.testTag("calendar-edit-save")) { Text("Save") }
        }
    }
}

@Composable
private fun AddBox(busy: Boolean, cancel: Boolean, onCancel: () -> Unit, onAddLink: (String, String, String?) -> Unit, onPick: () -> Unit, onOpen: (String) -> Unit, focusRequest: Int = 0) {
    val linkFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(focusRequest) { if (focusRequest > 0) runCatching { linkFocus.requestFocus() } }
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("calendar-add")) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Link, null); Spacer(Modifier.width(6.dp)); Text("From a link", fontWeight = FontWeight.Bold); Spacer(Modifier.width(6.dp)); Hint("updates every hour") }
        OutlinedTextField(url, { url = it }, placeholder = { Text("Zeus, Sisu (Export into external calendar) or any webcal:// link") }, label = { Text("Calendar link") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(linkFocus).testTag("calendar-link-url"))
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("calendar-link-name"))
        DateButton("Import classes from", from, { from = it }, "calendar-link-from")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onOpen("/ics-guide#link") }) { Text("How to get your Zeus link") }
            Spacer(Modifier.weight(1f))
            Button(enabled = !busy && url.isNotBlank(), onClick = { onAddLink(url, name, from.ifBlank { null }) }, modifier = Modifier.testTag("calendar-link-add")) { Text(if (busy) "Adding…" else "Add link") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Upload, null); Spacer(Modifier.width(6.dp)); Text("From a file", fontWeight = FontWeight.Bold); Spacer(Modifier.width(6.dp)); Hint(".ics") }
        OutlinedButton(enabled = !busy, onClick = onPick, modifier = Modifier.testTag("calendar-file-pick")) { Text("Choose a file") }
        Hint("Upload it again when the school publishes changes: your marks are kept.")
        TextButton(onClick = { onOpen("/ics-guide") }) { Text("Export it from Zeus") }
        if (cancel) TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun SyncLog(rows: List<CalendarSyncDto>, calendars: List<CalendarDto>, zone: ZoneId) {
    if (rows.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val names = calendars.associate { it.id to it.name }
    val fmt = DateTimeFormatter.ofPattern("d MMM, HH:mm")
    TextButton(onClick = { open = !open }, modifier = Modifier.testTag("sync-log")) { Text(if (open) "Hide recent updates" else "Recent updates") }
    if (open) rows.forEach { r ->
        val what = if (r.ok == 1) plural(r.sessions ?: 0, "session") + (r.added?.takeIf { it > 0 }?.let { ", $it new" } ?: "") + (r.removed?.takeIf { it > 0 }?.let { ", $it removed" } ?: "")
            else r.error ?: "Failed"
        Column(Modifier.padding(vertical = 2.dp)) {
            Text("${fmt.format(Instant.ofEpochMilli(r.at).atZone(zone))} · ${names[r.source_id] ?: "Calendar"} · ${TRIGGERS[r.source] ?: r.source.orEmpty()} · $what",
                style = MaterialTheme.typography.bodySmall, color = if (r.ok == 1) MaterialTheme.colorScheme.onSurface else LocalExtraColors.current.red)
            Hint("${r.http_status?.let { "HTTP $it · " }.orEmpty()}${"%.1f".format((r.duration_ms ?: 0) / 1000.0)} s")
        }
    }
}

@Composable
private fun SignInPanel(account: AccountDto, user: UserDto, now: Long, zone: ZoneId, busy: Boolean, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Panel("Sign-in methods") {
        if (account.google) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AccountCircle, null); Spacer(Modifier.width(10.dp))
            Column { Text("Google", fontWeight = FontWeight.Bold); Hint(user.email.orEmpty()) }
        }
        account.passkeys.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("passkey-${p.id}")) {
                Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name?.ifBlank { null } ?: "Passkey", fontWeight = FontWeight.Bold)
                        if (p.backed_up != 0) { Spacer(Modifier.width(6.dp)); Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) { Text("synced", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall) } }
                    }
                    Hint("Added ${day(p.created_at, zone)}${p.last_used_at?.let { " · last used ${day(it, zone)}" }.orEmpty()}")
                }
                TextButton(enabled = !busy, onClick = { onRemove(p.id) }) { Text("Remove") }
            }
        }
        OutlinedButton(enabled = !busy, onClick = onAdd, modifier = Modifier.testTag("passkey-add")) {
            Icon(Icons.Outlined.Key, null); Spacer(Modifier.width(6.dp)); Text("Add a passkey${if (account.passkeys.isNotEmpty()) " on another device" else ""}")
        }
        Hint(if (account.google) "Add a passkey to sign in with Face ID, fingerprint or PIN instead of Google."
            else "Add a passkey on a second device (or use a synced one) so you can always get back in.")
    }
}

@Composable
private fun TokensPanel(actions: SettingsActions, zone: ZoneId, busy: Boolean, run: (suspend () -> Unit) -> Unit, confirm: (Confirm) -> Unit, copy: (String) -> Unit) {
    // Not part of /api/data: loaded when Settings opens. A new token is kept in memory only and shown once.
    var tokens by remember { mutableStateOf<List<PersonalTokenDto>?>(null) }
    var fresh by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(actions) { tokens = actions.loadTokens() ?: emptyList() }
    var name by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf("write") }
    Panel("Claude Code access") {
        Hint("A token lets a script read your plan (and, if you allow it, add homework and catch-ups or mark sessions). Put it in the .env of your plate repo, then ask Claude Code “what should I do today?”. It can't change calendars, tokens or your account.")
        fresh?.let { t ->
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(10.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Copy it now, it won't be shown again:")
                    Text(t, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("token-fresh"))
                    OutlinedButton(onClick = { copy(t) }) { Text("Copy") }
                }
            }
        }
        if (tokens == null) CircularProgressIndicator(Modifier.size(20.dp))
        tokens.orEmpty().forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("token-${t.id}")) {
                Icon(Icons.Outlined.Keyboard, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name)
                    Hint("${if (t.scope == "write") "read and write" else "read only"} · created ${day(t.created_at, zone)} · ${t.last_used_at?.let { "used ${day(it, zone)}" } ?: "never used"}")
                }
                IconButton(enabled = !busy, onClick = { confirm(Confirm("Revoke \"${t.name}\"? Scripts using it stop working.", "Revoke") {
                    if (actions.revokeToken(t.id)) { tokens = tokens.orEmpty().filterNot { it.id == t.id }; fresh = null }
                }) }) { Icon(Icons.Outlined.Delete, "Revoke ${t.name}") }
            }
        }
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name, e.g. MacBook Claude Code") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("token-name"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("write" to "Read and write", "read" to "Read only").forEach { (k, l) -> FilterChip(scope == k, { scope = k }, { Text(l) }, modifier = Modifier.testTag("token-scope-$k")) }
        }
        Box {
            Button(enabled = !busy && name.isNotBlank(), onClick = { run {
                actions.createToken(name, scope)?.let { created ->
                    tokens = tokens.orEmpty() + PersonalTokenDto(created.id, created.name, created.scope, created.created_at, created.last_used_at)
                    fresh = created.token; name = ""
                }
            } }, modifier = Modifier.testTag("token-create")) { Text("Create token") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedsPanel(data: AppData, scope: List<Long>, busy: Boolean, onCopy: (String) -> Unit, onShare: (String) -> Unit, onOpen: (String) -> Unit, onRotate: () -> Unit) {
    val cals = data.calendars
    // Starts as the Count selection; an empty list means every group and gives the plain link.
    var groups by rememberSaveable { mutableStateOf(sanitizeScope(scope, cals)) }
    val on = sanitizeScope(groups, cals)
    @Composable fun feed(title: String, hint: String, url: String, tag: String, extra: @Composable () -> Unit = {}) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag(tag)) {
            Text(title, fontWeight = FontWeight.Bold)
            Hint(hint)
            extra()
            Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, modifier = Modifier.testTag("$tag-url"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onCopy(url) }) { Text("Copy") }
                OutlinedButton(onClick = { onShare(url) }) { Text("Share") }
                OutlinedButton(onClick = { onOpen("https://calendar.google.com/calendar/r?cid=" + Uri.encode(url.replace(Regex("^https?:"), "webcal:"))) }) { Text("Add to Google Calendar") }
            }
        }
    }
    Panel("Subscribe in your calendar app") {
        Hint("Private links that Google Calendar, Apple Calendar and Outlook keep up to date, including Zeus updates. Apple Calendar and Outlook check them often; Google Calendar every few hours.")
        val hint = when {
            cals.size < 2 -> "Every session of the groups you picked, as on Zeus."
            on.isNotEmpty() -> "Only the sessions of ${cals.filter { it.id in on }.joinToString(" + ") { it.name }}, as on Zeus. Each choice is its own link."
            else -> "Every session of all your groups, as on Zeus. Pick groups to get a link with only theirs."
        }
        feed("Timetable", hint, timetableFeedUrl(data.integrations.feedUrl, cals.map { it.id }.filter { it in on }), "feed-timetable") {
            if (cals.size >= 2) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(on.isEmpty(), { groups = emptyList() }, { Text("All") }, modifier = Modifier.testTag("feed-group-all"))
                cals.forEach { c -> FilterChip(c.id in on, { groups = toggleScope(c.id.toString(), on, cals) }, { Text(c.name) }, modifier = Modifier.testTag("feed-group-${c.id}")) }
            }
        }
        feed("Homework (optional)", "Your homework, on its due date.", data.integrations.feedUrl, "feed-homework")
        TextButton(enabled = !busy, onClick = onRotate, modifier = Modifier.testTag("feed-rotate")) { Text("Reset links") }
    }
}

@Composable
private fun GcalPanel(integ: IntegrationsDto, busy: Boolean, onConnect: () -> Unit, onResync: () -> Unit, onDisconnect: () -> Unit) {
    val g = integ.gcal
    Panel("Google Calendar sync (homework)") {
        when {
            !integ.gcalAvailable -> Text("Not configured on this server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            g != null -> {
                Text("Connected as ${g.email ?: "your Google account"}. Homework is kept in a calendar called Homework (Attendance80). Changes appear immediately, and each reminder is a Google Calendar notification. Use this instead of the homework link above in Google Calendar.")
                g.error?.let { Text(it, color = LocalExtraColors.current.red) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (g.error != null) Button(enabled = !busy, onClick = onConnect) { Text("Reconnect") }
                    OutlinedButton(enabled = !busy, onClick = onResync, modifier = Modifier.testTag("gcal-resync")) { Text("Sync now") }
                    TextButton(enabled = !busy, onClick = onDisconnect, modifier = Modifier.testTag("gcal-disconnect")) { Text("Disconnect") }
                }
            }
            else -> {
                Text("For Google users, an alternative to the homework link: homework is pushed into its own calendar in your Google account, changes appear immediately, and reminders arrive as Google Calendar notifications. The app can only see the calendar it creates.")
                OutlinedButton(enabled = !busy, onClick = onConnect, modifier = Modifier.testTag("gcal-connect")) { Text("Connect Google Calendar") }
            }
        }
    }
}
