package com.knhdixh.attendance80.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.knhdixh.attendance80.ui.settings.MAX_ICS_BYTES
import com.knhdixh.attendance80.ui.settings.readAtMost

/** What another app handed us: a calendar file to add, or text to turn into homework. */
sealed interface Incoming {
    /** [text] is the file's content, null when it could not be read ([error] says why). */
    data class CalendarFile(val name: String, val text: String?, val error: String? = null) : Incoming
    data class SharedText(val text: String) : Incoming
}

private fun Uri.looksLikeIcs() = lastPathSegment?.endsWith(".ics", ignoreCase = true) == true

/**
 * VIEW and SEND of a calendar (`text/calendar` or a name ending `.ics`) open Settings → Calendars; SEND of plain text
 * opens Add homework. Anything else, including the app's own `a80://` and sign-in links, is not ours to handle here.
 */
fun readIncoming(intent: Intent, resolver: ContentResolver): Incoming? {
    val type = intent.type ?: intent.data?.let { resolver.getType(it) }
    when (intent.action) {
        Intent.ACTION_VIEW -> {
            val uri = intent.data ?: return null
            if (uri.scheme != "content" && uri.scheme != "file") return null
            if (type?.startsWith("text/calendar") != true && !uri.looksLikeIcs()) return null
            return readCalendar(uri, resolver)
        }
        Intent.ACTION_SEND -> {
            @Suppress("DEPRECATION") val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (stream != null && (type?.startsWith("text/calendar") == true || stream.looksLikeIcs() || displayName(stream, resolver)?.endsWith(".ics", true) == true))
                return readCalendar(stream, resolver)
            if (type?.startsWith("text/") != true) return null
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return null
            return Incoming.SharedText(text)
        }
    }
    return null
}

private fun displayName(uri: Uri, resolver: ContentResolver): String? =
    runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()

private fun readCalendar(uri: Uri, resolver: ContentResolver): Incoming.CalendarFile {
    val name = displayName(uri, resolver) ?: uri.lastPathSegment.orEmpty().substringAfterLast('/').ifBlank { "calendar.ics" }
    val bytes = runCatching { resolver.openInputStream(uri)?.use { readAtMost(it, MAX_ICS_BYTES + 1) } }.getOrNull()
    return when {
        bytes == null -> Incoming.CalendarFile(name, null, "Could not read that file.")
        bytes.size > MAX_ICS_BYTES -> Incoming.CalendarFile(name, null, "That file is over 5 MB.")
        else -> Incoming.CalendarFile(name, bytes.toString(Charsets.UTF_8))
    }
}

/** Shared text becomes the homework title (its first line, as long as the form allows) and the notes (the rest). */
fun splitSharedText(text: String): Pair<String, String> {
    val lines = text.trim().lines()
    val first = lines.firstOrNull { it.isNotBlank() }.orEmpty()
    val rest = lines.drop(lines.indexOf(first) + 1).joinToString("\n").trim()
    // A first line longer than the title field keeps its overflow in the notes, so nothing shared is lost.
    return if (first.trim().length > TITLE_MAX) first.trim().take(TITLE_MAX).trimEnd() to (first.trim().drop(TITLE_MAX) + if (rest.isEmpty()) "" else "\n$rest").trim().take(NOTES_MAX)
    else first.trim() to rest.take(NOTES_MAX)
}

const val TITLE_MAX = 200
const val NOTES_MAX = 4000
